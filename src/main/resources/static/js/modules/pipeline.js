// ============================================
// 平台原生 Pipeline 视图模块
// ============================================

let currentPipelineJobId = null;
let pipelinePollTimer = null;
let pipelineStageViewPage = 1;
/** 用于丢弃过期的列表/阶段视图渲染，避免轮询把详情页盖回列表 */
let pipelineListRenderToken = 0;
let pipelineStageRenderToken = 0;

/** 当前选中的流水线视图（null = 全部任务） */
let currentPipelineViewId = null;
let currentPipelineViewEnvId = null;
let pipelineViewCache = [];
const PIPELINE_VIEW_STORAGE_KEY = 'opsflow_pipeline_view_id';

const PIPELINE_PAGE_SIZE_KEY = 'opsflow_pipeline_page_size';
const PIPELINE_PAGE_SIZE_OPTIONS = [10, 20, 50];
const PIPELINE_GIT_TYPE_OPTIONS = [
    { value: 'branch', label: '分支' },
    { value: 'tag', label: 'Tag' }
];

function renderPipelineGitTypeOptions(selected) {
    const current = selected === 'tag' ? 'tag' : 'branch';
    return PIPELINE_GIT_TYPE_OPTIONS.map(opt => {
        const sel = current === opt.value ? ' selected' : '';
        return `<option value="${opt.value}"${sel}>${opt.label}</option>`;
    }).join('');
}

function getPipelineGitTypeLabel(gitType) {
    return gitType === 'tag' ? 'Tag' : '分支';
}

const pipelineListGitRefsCache = new Map();

function normalizePipelineGitType(gitType) {
    return gitType === 'tag' ? 'tag' : 'branch';
}

function buildPipelineListGitRefsCacheKey(serviceId, gitType) {
    return `${serviceId}|${normalizePipelineGitType(gitType)}`;
}

async function fetchPipelineListGitRefs(serviceId, gitType) {
    const key = buildPipelineListGitRefsCacheKey(serviceId, gitType);
    if (pipelineListGitRefsCache.has(key)) {
        return pipelineListGitRefsCache.get(key);
    }
    const type = normalizePipelineGitType(gitType);
    const response = await fetch(`/api/build/service/${serviceId}/branches?type=${encodeURIComponent(type)}`);
    let refs = response.ok ? await response.json() : [];
    if (!Array.isArray(refs)) {
        refs = [];
    }
    const list = [...new Set(refs.filter(r => r && String(r).trim()))];
    pipelineListGitRefsCache.set(key, list);
    return list;
}

function canRetryCdPipelineJob(job) {
    return !!job.fromDeployTask && job.retryable === true;
}

function resolvePipelineRunJobId(job) {
    if (job.fromDeployTask) {
        return job.latestBuildId || job.id;
    }
    return job.id;
}

function isPipelineRunDisabled(job) {
    if (typeof hasPermission === 'function' && !hasPermission('pipeline:run')) {
        return true;
    }
    if (job.building) {
        return true;
    }
    if (job.fromDeployTask) {
        return !canRetryCdPipelineJob(job);
    }
    return false;
}

function getPipelineRunButtonLabel(job) {
    if (typeof hasPermission === 'function' && !hasPermission('pipeline:run')) {
        return '无权限';
    }
    if (job.building) {
        return 'Running...';
    }
    if (job.fromDeployTask) {
        if (canRetryCdPipelineJob(job)) {
            return '重试';
        }
        if (job.status === 'SUCCESS') {
            return '已完成';
        }
        if (job.blockedReason) {
            return job.blockedReason;
        }
        return '等待';
    }
    return 'Run';
}

function getPipelineRunButtonTitle(job) {
    if (typeof hasPermission === 'function' && !hasPermission('pipeline:run')) {
        return '无运行权限';
    }
    if (job.fromDeployTask && job.blockedReason && !canRetryCdPipelineJob(job)) {
        return job.blockedReason;
    }
    if (job.fromDeployTask && canRetryCdPipelineJob(job)) {
        return '重试当前失败模块的 CD 部署';
    }
    return '';
}

function canRollbackPipelineJob(job) {
    if (typeof hasPermission === 'function' && !hasPermission('pipeline:rollback')) {
        return false;
    }
    return !!job.rollbackable && !job.building;
}

function getPipelineRollbackButtonTitle(job) {
    if (typeof hasPermission === 'function' && !hasPermission('pipeline:rollback')) {
        return '无回滚权限';
    }
    if (job.building) {
        return '任务运行中，暂不可回滚';
    }
    if (job.rollbackable) {
        return '回滚到历史成功镜像版本';
    }
    return '暂无可回滚的历史镜像版本（需同服务/环境下至少 2 个成功镜像，且流水线为 CD 或 CI/CD）';
}

function renderPipelineGitRefCell(job) {
    if (job.fromDeployTask) {
        const tag = escapeHtml(job.branch || '-');
        return `
        <td class="pipeline-git-ref-cell" title="上线任务自动创建的 CD 任务使用镜像 Tag，不能手动修改">
            <div style="width:fit-content;min-width:110px;max-width:150px;">
                <span style="display:inline-block;padding:4px 8px;font-size:12px;border:1px solid #e5e7eb;border-radius:4px;background:#f9fafb;color:#374151;">
                    ${tag}
                </span>
            </div>
        </td>
    `;
    }
    const gitType = normalizePipelineGitType(job.gitType);
    const editable = job.editable !== false && !job.building;
    const disabledAttr = editable ? '' : ' disabled';
    const cellStyle = 'width:fit-content;min-width:110px;max-width:150px;';
    const selectStyle = 'width:100%;padding:3px 24px 3px 6px;font-size:12px;border:1px solid #e5e7eb;border-radius:4px;background:#fff;';
    return `
        <td class="pipeline-git-ref-cell"
            data-job-id="${job.id}"
            data-service-id="${job.serviceId || ''}"
            data-env-id="${job.envId || ''}"
            data-pipeline-template-id="${job.pipelineTemplateId || ''}"
            data-task-name="${escapeHtml(job.taskName || job.serviceName || '')}"
            data-git-type="${gitType}"
            data-editable="${editable}">
            <div style="${cellStyle}">
                <select class="pipeline-list-git-ref" style="${selectStyle}"${disabledAttr}
                        data-current-branch="${escapeHtml(job.branch || '')}">
                    <option value="${escapeHtml(job.branch || '')}" selected>${escapeHtml(job.branch || '-')}</option>
                </select>
            </div>
        </td>
    `;
}

async function populatePipelineListGitRefSelect(refSelect, serviceId, gitType, selectedRef, editable) {
    if (!refSelect || !serviceId) return;
    refSelect.disabled = true;
    refSelect.innerHTML = '<option value="">加载中...</option>';
    try {
        const refs = await fetchPipelineListGitRefs(serviceId, gitType);
        const current = (selectedRef || '').trim();
        const selected = refs.includes(current) ? current : (current || refs[0] || '');
        if (!refs.length) {
            refSelect.innerHTML = selected
                ? `<option value="${escapeHtml(selected)}" selected>${escapeHtml(selected)}</option>`
                : `<option value="">无可用${getPipelineGitTypeLabel(gitType)}</option>`;
        } else {
            refSelect.innerHTML = refs.map(ref => {
                const sel = ref === selected ? ' selected' : '';
                return `<option value="${escapeHtml(ref)}"${sel}>${escapeHtml(ref)}</option>`;
            }).join('');
        }
        if (selected && refSelect.value !== selected) {
            refSelect.value = selected;
        }
    } catch (error) {
        console.error('Load pipeline list git refs error:', error);
        const fallback = (selectedRef || '').trim();
        refSelect.innerHTML = fallback
            ? `<option value="${escapeHtml(fallback)}" selected>${escapeHtml(fallback)}</option>`
            : '<option value="">加载失败</option>';
    }
    refSelect.disabled = !editable;
}

async function initPipelineListGitRefCells(jobs) {
    const cells = document.querySelectorAll('.pipeline-git-ref-cell');
    cells.forEach(cell => {
        const serviceId = parseInt(cell.getAttribute('data-service-id'), 10);
        const editable = cell.getAttribute('data-editable') === 'true';
        const refSelect = cell.querySelector('.pipeline-list-git-ref');
        if (!serviceId || !refSelect) return;
        const gitType = normalizePipelineGitType(cell.getAttribute('data-git-type'));
        const selectedRef = refSelect.value || refSelect.getAttribute('data-current-branch') || '';
        populatePipelineListGitRefSelect(refSelect, serviceId, gitType, selectedRef, editable);
    });
}

async function savePipelineJobGitRef(cell, gitType, branch) {
    const jobId = parseInt(cell.getAttribute('data-job-id'), 10);
    const serviceId = parseInt(cell.getAttribute('data-service-id'), 10);
    const envId = parseInt(cell.getAttribute('data-env-id'), 10);
    const pipelineTemplateId = parseInt(cell.getAttribute('data-pipeline-template-id'), 10);
    const taskName = (cell.getAttribute('data-task-name') || '').trim();
    if (!jobId || !serviceId || !envId || !pipelineTemplateId || !branch) {
        return false;
    }

    const response = await fetch(`/api/pipeline-run/${jobId}`, {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
            taskName,
            serviceId,
            envId,
            pipelineTemplateId,
            branch,
            gitType: normalizePipelineGitType(gitType)
        })
    });

    let result = {};
    try {
        result = await response.json();
    } catch (e) {
        result = {};
    }

    if (!response.ok) {
        throw new Error(result.message || result.error || response.statusText || '保存失败');
    }
    return true;
}

async function onPipelineListGitRefChange(refSelect) {
    const cell = refSelect.closest('.pipeline-git-ref-cell');
    if (!cell || cell.getAttribute('data-editable') !== 'true') return;

    const gitType = normalizePipelineGitType(cell.getAttribute('data-git-type'));
    const branch = (refSelect.value || '').trim();
    const previous = refSelect.getAttribute('data-current-branch') || '';
    if (!branch || branch === previous) return;

    refSelect.disabled = true;
    try {
        await savePipelineJobGitRef(cell, gitType, branch);
        refSelect.setAttribute('data-current-branch', branch);
    } catch (error) {
        console.error('Update pipeline git ref error:', error);
        alert('更新 Git 引用失败: ' + error.message);
        refSelect.value = previous;
    } finally {
        refSelect.disabled = false;
    }
}

function bindPipelineListGitRefDelegation() {
    const container = document.getElementById('pipelineJobsView');
    if (!container || container.dataset.gitRefBound === 'true') {
        return;
    }
    container.addEventListener('change', (event) => {
        if (event.target.classList.contains('pipeline-list-git-ref')) {
            onPipelineListGitRefChange(event.target);
        }
    });
    container.dataset.gitRefBound = 'true';
}

function getPipelinePageSize() {
    const saved = parseInt(localStorage.getItem(PIPELINE_PAGE_SIZE_KEY), 10);
    return PIPELINE_PAGE_SIZE_OPTIONS.includes(saved) ? saved : 10;
}

function setPipelinePageSize(size) {
    const normalized = PIPELINE_PAGE_SIZE_OPTIONS.includes(size) ? size : 10;
    localStorage.setItem(PIPELINE_PAGE_SIZE_KEY, String(normalized));
    return normalized;
}

function renderPageSizeSelect(currentSize, onChangeFnName) {
    return `
        <label style="font-size:13px;color:#6b7280;display:inline-flex;align-items:center;gap:6px;">
            每页
            <select onchange="${onChangeFnName}(parseInt(this.value, 10))" style="padding:4px 8px;">
                ${PIPELINE_PAGE_SIZE_OPTIONS.map(n => `
                    <option value="${n}" ${n === currentSize ? 'selected' : ''}>${n}</option>
                `).join('')}
            </select>
            条
        </label>
    `;
}

function renderPaginationBar(page, totalPages, total, onPageFnName) {
    const safeTotalPages = Math.max(totalPages || 0, 1);
    const safePage = Math.min(Math.max(page || 1, 1), safeTotalPages);
    return `
        <div style="display:flex;justify-content:space-between;align-items:center;margin-top:12px;flex-wrap:wrap;gap:8px;">
            <span style="font-size:13px;color:#6b7280;">共 ${total || 0} 条，第 ${safePage} / ${safeTotalPages} 页</span>
            <div style="display:flex;gap:8px;">
                <button class="btn-secondary" style="padding:4px 12px;font-size:12px;"
                    ${safePage <= 1 ? 'disabled' : ''}
                    onclick="${onPageFnName}(${safePage - 1})">上一页</button>
                <button class="btn-secondary" style="padding:4px 12px;font-size:12px;"
                    ${safePage >= safeTotalPages ? 'disabled' : ''}
                    onclick="${onPageFnName}(${safePage + 1})">下一页</button>
            </div>
        </div>
    `;
}

async function fetchPipelineJobsPage(page, pageSize) {
    const params = new URLSearchParams();
    params.set('page', String(page || 1));
    params.set('pageSize', String(pageSize || 10));
    if (currentPipelineViewEnvId) {
        params.set('envId', String(currentPipelineViewEnvId));
    }
    const response = await fetch(`/api/pipeline-run/jobs?${params.toString()}`);
    if (!response.ok) {
        throw new Error(await response.text() || response.statusText);
    }
    return response.json();
}

async function loadPipelineViews(force) {
    if (!force && pipelineViewCache.length > 0) {
        return pipelineViewCache;
    }
    try {
        const response = await fetch('/api/pipeline-view/list');
        if (response.ok) {
            pipelineViewCache = await response.json() || [];
        } else {
            pipelineViewCache = [];
        }
    } catch (e) {
        console.warn('Load pipeline views failed', e);
        pipelineViewCache = [];
    }
    return pipelineViewCache;
}

function canManagePipelineViews() {
    return typeof hasPermission === 'function' && hasPermission('pipeline_config:manage');
}

function restoreSelectedPipelineView() {
    // 进入流水线页：优先恢复上次选择（仍有权限），否则展示排序第一位的视图
    if (pipelineViewCache.length > 0) {
        const saved = localStorage.getItem(PIPELINE_VIEW_STORAGE_KEY);
        if (saved && saved !== 'all') {
            const matched = pipelineViewCache.find(v => String(v.id) === String(saved));
            if (matched) {
                currentPipelineViewId = matched.id;
                currentPipelineViewEnvId = matched.envId;
                return;
            }
        }
        if (saved === 'all' && canManagePipelineViews()) {
            currentPipelineViewId = null;
            currentPipelineViewEnvId = null;
            return;
        }
        const first = pipelineViewCache[0];
        currentPipelineViewId = first.id;
        currentPipelineViewEnvId = first.envId;
        localStorage.setItem(PIPELINE_VIEW_STORAGE_KEY, String(first.id));
        return;
    }
    currentPipelineViewId = null;
    currentPipelineViewEnvId = null;
    localStorage.removeItem(PIPELINE_VIEW_STORAGE_KEY);
}

function selectPipelineView(viewId) {
    if (viewId == null || viewId === '' || viewId === 'all') {
        if (!canManagePipelineViews()) {
            return;
        }
        currentPipelineViewId = null;
        currentPipelineViewEnvId = null;
        localStorage.setItem(PIPELINE_VIEW_STORAGE_KEY, 'all');
    } else {
        const view = pipelineViewCache.find(v => String(v.id) === String(viewId));
        if (!view) {
            return;
        }
        currentPipelineViewId = view.id;
        currentPipelineViewEnvId = view.envId;
        localStorage.setItem(PIPELINE_VIEW_STORAGE_KEY, String(view.id));
    }
    renderPipelineViewTabs();
    if (!currentPipelineJobId) {
        renderPipelineListPage();
    }
}
window.selectPipelineView = selectPipelineView;

function renderPipelineViewTabs() {
    const tabs = document.getElementById('pipelineViewTabs');
    if (!tabs) return;
    if (currentPipelineJobId) {
        tabs.style.display = 'none';
        return;
    }
    tabs.style.display = 'flex';
    const manageViews = canManagePipelineViews();
    const allActive = currentPipelineViewId == null && manageViews ? ' active' : '';
    let html = '';
    pipelineViewCache.forEach(view => {
        const active = String(currentPipelineViewId) === String(view.id) ? ' active' : '';
        const dragAttrs = manageViews ? 'draggable="true"' : '';
        const dragClass = manageViews ? ' draggable-tab' : '';
        const title = view.envName
            ? (`关联环境: ${view.envName}${manageViews ? ' · 拖动可调整顺序' : ''}`)
            : (view.name + (manageViews ? ' · 拖动可调整顺序' : ''));
        html += `
            <button type="button" class="pipeline-view-tab${dragClass}${active}"
                data-view-id="${view.id}"
                ${dragAttrs}
                onclick="window.selectPipelineView(${view.id})"
                title="${escapeHtml(title)}">
                ${manageViews ? '<span class="pipeline-view-tab-grip" aria-hidden="true">⋮⋮</span>' : ''}
                <span>${escapeHtml(view.name)}</span>
            </button>
        `;
    });
    if (manageViews) {
        html += `
            <button type="button" class="pipeline-view-tab${allActive}" onclick="window.selectPipelineView('all')">
                全部
            </button>
        `;
    }
    if (!pipelineViewCache.length && !manageViews) {
        html = `<div style="padding:8px 4px;color:#9ca3af;font-size:13px;">暂无可访问的流水线视图，请联系管理员分配</div>`;
    }
    tabs.innerHTML = html;
    if (manageViews) {
        bindPipelineViewTabDragDrop(tabs);
    }
}

let pipelineViewDragId = null;

function bindPipelineViewTabDragDrop(container) {
    if (!container || container.dataset.dragBound === 'true') {
        return;
    }
    container.dataset.dragBound = 'true';

    container.addEventListener('dragstart', (event) => {
        const tab = event.target.closest('.pipeline-view-tab[data-view-id]');
        if (!tab) {
            return;
        }
        pipelineViewDragId = tab.getAttribute('data-view-id');
        tab.classList.add('dragging');
        event.dataTransfer.effectAllowed = 'move';
        event.dataTransfer.setData('text/plain', pipelineViewDragId);
    });

    container.addEventListener('dragend', (event) => {
        const tab = event.target.closest('.pipeline-view-tab[data-view-id]');
        if (tab) {
            tab.classList.remove('dragging');
        }
        pipelineViewDragId = null;
        container.querySelectorAll('.pipeline-view-tab.drag-over').forEach(el => el.classList.remove('drag-over'));
    });

    container.addEventListener('dragover', (event) => {
        const tab = event.target.closest('.pipeline-view-tab[data-view-id]');
        if (!tab || !pipelineViewDragId) {
            return;
        }
        event.preventDefault();
        event.dataTransfer.dropEffect = 'move';
        container.querySelectorAll('.pipeline-view-tab.drag-over').forEach(el => {
            if (el !== tab) {
                el.classList.remove('drag-over');
            }
        });
        tab.classList.add('drag-over');
    });

    container.addEventListener('dragleave', (event) => {
        const tab = event.target.closest('.pipeline-view-tab[data-view-id]');
        if (tab) {
            tab.classList.remove('drag-over');
        }
    });

    container.addEventListener('drop', async (event) => {
        event.preventDefault();
        const tab = event.target.closest('.pipeline-view-tab[data-view-id]');
        if (!tab || !pipelineViewDragId) {
            return;
        }
        tab.classList.remove('drag-over');
        const targetId = tab.getAttribute('data-view-id');
        if (!targetId || targetId === pipelineViewDragId) {
            return;
        }
        await reorderPipelineViews(pipelineViewDragId, targetId);
    });
}

async function reorderPipelineViews(sourceId, targetId) {
    const sourceIndex = pipelineViewCache.findIndex(v => String(v.id) === String(sourceId));
    const targetIndex = pipelineViewCache.findIndex(v => String(v.id) === String(targetId));
    if (sourceIndex < 0 || targetIndex < 0 || sourceIndex === targetIndex) {
        return;
    }

    const next = pipelineViewCache.slice();
    const [moved] = next.splice(sourceIndex, 1);
    next.splice(targetIndex, 0, moved);
    pipelineViewCache = next;

    try {
        const response = await fetch('/api/pipeline-view/reorder', {
            method: 'PUT',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(next.map(v => v.id))
        });
        if (!response.ok) {
            throw new Error(await response.text() || response.statusText);
        }
        renderPipelineViewTabs();
    } catch (error) {
        console.error('Reorder pipeline views failed', error);
        await loadPipelineViews(true);
        renderPipelineViewTabs();
        alert('调整视图顺序失败: ' + error.message);
    }
}

async function loadPipelineViewsAdmin() {
    const list = document.getElementById('pipelineViewList');
    if (!list) return;
    list.innerHTML = '<tr><td colspan="4" style="text-align:center;padding:40px;color:#999;">加载中...</td></tr>';
    const views = await loadPipelineViews(true);
    renderPipelineViewsAdminList(views);
    renderPipelineViewTabs();
}
window.loadPipelineViewsAdmin = loadPipelineViewsAdmin;

function renderPipelineViewsAdminList(views) {
    const list = document.getElementById('pipelineViewList');
    if (!list) return;
    if (!views || !views.length) {
        list.innerHTML = '<tr><td colspan="5" style="text-align:center;padding:40px;color:#999;">暂无视图，请点击「添加视图」创建</td></tr>';
        return;
    }
    list.innerHTML = views.map(view => {
        const roles = (view.roleNames && view.roleNames.length)
            ? view.roleNames.join('、')
            : '<span style="color:#9ca3af;">仅管理员可见</span>';
        return `
        <tr>
            <td>${escapeHtml(view.name || '-')}</td>
            <td>${escapeHtml(view.envName || (view.envId != null ? ('env-' + view.envId) : '-'))}</td>
            <td>${roles}</td>
            <td>${escapeHtml(view.description || '-')}</td>
            <td>
                <button class="btn-edit" onclick="window.showEditPipelineViewModal(${view.id})">修改</button>
                <button class="btn-danger" onclick="window.deletePipelineView(${view.id})">删除</button>
            </td>
        </tr>`;
    }).join('');
}

async function loadPipelineEnvsForView() {
    try {
        const response = await fetch('/api/env/list');
        if (response.ok) {
            return await response.json() || [];
        }
    } catch (e) {
        console.warn('Load envs for pipeline view failed', e);
    }
    return [];
}

async function loadRolesForPipelineView() {
    try {
        const response = await fetch('/api/role/list');
        if (response.ok) {
            return await response.json() || [];
        }
    } catch (e) {
        console.warn('Load roles for pipeline view failed', e);
    }
    return [];
}

function renderPipelineViewRoleCheckboxes(roles, selectedIds) {
    const render = typeof window.renderCheckboxList === 'function'
        ? window.renderCheckboxList
        : null;
    const escapeFn = typeof window.escapeUserHtml === 'function' ? window.escapeUserHtml : escapeHtml;
    if (render) {
        return render(roles, 'roleIds', selectedIds || [], r => escapeFn(r.name || r.code || String(r.id)));
    }
    if (!roles || !roles.length) {
        return '<div class="checkbox-list"><div class="checkbox-list-empty">暂无角色</div></div>';
    }
    const selected = new Set((selectedIds || []).map(Number));
    return `<div class="checkbox-list">${roles.map(r => `
        <label class="checkbox-list-item">
            <input type="checkbox" name="roleIds" value="${r.id}" ${selected.has(Number(r.id)) ? 'checked' : ''}>
            <span>${escapeFn(r.name || r.code || String(r.id))}</span>
        </label>`).join('')}</div>`;
}

function collectPipelineViewRoleIds(form) {
    if (typeof window.collectCheckedIds === 'function') {
        return window.collectCheckedIds(form, 'roleIds');
    }
    return Array.from(form.querySelectorAll('input[name="roleIds"]:checked'))
        .map(el => parseInt(el.value, 10))
        .filter(id => !Number.isNaN(id));
}

function renderPipelineViewEnvOptions(envs, selectedId) {
    let html = '<option value="">请选择环境</option>';
    (envs || []).forEach(env => {
        const selected = selectedId != null && String(selectedId) === String(env.id) ? ' selected' : '';
        html += `<option value="${env.id}"${selected}>${escapeHtml(env.name || ('env-' + env.id))}</option>`;
    });
    return html;
}

async function showCreatePipelineViewModal() {
    if (typeof window.showModal !== 'function') {
        alert('模态框未加载');
        return;
    }
    const [envs, roles] = await Promise.all([loadPipelineEnvsForView(), loadRolesForPipelineView()]);
    if (!envs.length) {
        alert('暂无环境，请先在「环境配置」中创建环境');
        return;
    }
    const content = `
        <form id="createPipelineViewForm">
            <div class="form-item">
                <label>视图名称 *</label>
                <input type="text" name="name" placeholder="例如: dev" required>
            </div>
            <div class="form-item">
                <label>关联环境 *</label>
                <select name="envId" required>${renderPipelineViewEnvOptions(envs)}</select>
                <small style="color:#666;">该视图下仅显示所选环境的流水线任务</small>
            </div>
            <div class="form-item">
                <label>可见角色</label>
                ${renderPipelineViewRoleCheckboxes(roles, [])}
                <small style="color:#666;">不勾选则仅管理员可见；勾选后对应角色可在流水线页看到此视图</small>
            </div>
            <div class="form-item">
                <label>描述</label>
                <textarea name="description" rows="2" placeholder="可选"></textarea>
            </div>
        </form>
    `;
    window.showModal('新建视图', content, async () => {
        const form = document.getElementById('createPipelineViewForm');
        if (!form) return;
        const name = (form.querySelector('[name="name"]').value || '').trim();
        const envId = form.querySelector('[name="envId"]').value;
        const description = (form.querySelector('[name="description"]').value || '').trim();
        const roleIds = collectPipelineViewRoleIds(form);
        if (!name) {
            alert('请填写视图名称');
            return;
        }
        if (!envId) {
            alert('请选择关联环境');
            return;
        }
        try {
            const response = await fetch('/api/pipeline-view/create', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ name, envId: parseInt(envId, 10), description, status: 1, roleIds })
            });
            if (!response.ok) {
                throw new Error(await response.text() || '创建失败');
            }
            const created = await response.json();
            window.closeModal();
            await loadPipelineViews(true);
            renderPipelineViewsAdminList(pipelineViewCache);
            renderPipelineViewTabs();
            if (document.getElementById('pipeline-section') &&
                document.getElementById('pipeline-section').classList.contains('active') &&
                !currentPipelineJobId) {
                selectPipelineView(created.id);
            }
        } catch (e) {
            alert('创建失败: ' + e.message);
        }
    });
}
window.showCreatePipelineViewModal = showCreatePipelineViewModal;

async function showEditPipelineViewModal(viewId) {
    if (typeof window.showModal !== 'function') {
        alert('模态框未加载');
        return;
    }
    try {
        const [viewResp, envs, roles] = await Promise.all([
            fetch(`/api/pipeline-view/${viewId}`),
            loadPipelineEnvsForView(),
            loadRolesForPipelineView()
        ]);
        if (!viewResp.ok) {
            throw new Error(await viewResp.text() || '加载失败');
        }
        const view = await viewResp.json();
        const content = `
            <form id="editPipelineViewForm">
                <div class="form-item">
                    <label>视图名称 *</label>
                    <input type="text" name="name" value="${escapeHtml(view.name || '')}" required>
                </div>
                <div class="form-item">
                    <label>关联环境 *</label>
                    <select name="envId" required>${renderPipelineViewEnvOptions(envs, view.envId)}</select>
                </div>
                <div class="form-item">
                    <label>可见角色</label>
                    ${renderPipelineViewRoleCheckboxes(roles, view.roleIds || [])}
                    <small style="color:#666;">不勾选则仅管理员可见</small>
                </div>
                <div class="form-item">
                    <label>描述</label>
                    <textarea name="description" rows="2">${escapeHtml(view.description || '')}</textarea>
                </div>
            </form>
        `;
        window.showModal('编辑视图', content, async () => {
            const form = document.getElementById('editPipelineViewForm');
            if (!form) return;
            const name = (form.querySelector('[name="name"]').value || '').trim();
            const envId = form.querySelector('[name="envId"]').value;
            const description = (form.querySelector('[name="description"]').value || '').trim();
            const roleIds = collectPipelineViewRoleIds(form);
            if (!name || !envId) {
                alert('请填写视图名称并选择环境');
                return;
            }
            try {
                const response = await fetch(`/api/pipeline-view/${viewId}`, {
                    method: 'PUT',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ name, envId: parseInt(envId, 10), description, status: 1, roleIds })
                });
                if (!response.ok) {
                    throw new Error(await response.text() || '更新失败');
                }
                window.closeModal();
                await loadPipelineViews(true);
                renderPipelineViewsAdminList(pipelineViewCache);
                if (String(currentPipelineViewId) === String(viewId)) {
                    selectPipelineView(viewId);
                } else {
                    renderPipelineViewTabs();
                }
            } catch (e) {
                alert('更新失败: ' + e.message);
            }
        });
    } catch (e) {
        alert('加载失败: ' + e.message);
    }
}
window.showEditPipelineViewModal = showEditPipelineViewModal;

async function deletePipelineView(viewId) {
    if (!confirm('确定删除该视图？不会删除任务，仅移除筛选入口。')) {
        return;
    }
    try {
        const response = await fetch(`/api/pipeline-view/${viewId}`, { method: 'DELETE' });
        if (!response.ok) {
            throw new Error(await response.text() || '删除失败');
        }
        if (String(currentPipelineViewId) === String(viewId)) {
            currentPipelineViewId = null;
            currentPipelineViewEnvId = null;
            localStorage.removeItem(PIPELINE_VIEW_STORAGE_KEY);
        }
        await loadPipelineViews(true);
        renderPipelineViewsAdminList(pipelineViewCache);
        renderPipelineViewTabs();
        if (!currentPipelineJobId) {
            await renderPipelineListPage();
        }
    } catch (e) {
        alert('删除失败: ' + e.message);
    }
}
window.deletePipelineView = deletePipelineView;

async function refreshPipelineView() {
    if (currentPipelineJobId) {
        await loadPipelineStageView(currentPipelineJobId, pipelineStageViewPage);
    } else {
        await loadPipelineJobsView();
    }
}
window.refreshPipelineView = refreshPipelineView;

function setPipelineSectionMode(mode) {
    const title = document.getElementById('pipelineSectionTitle');
    const createBtn = document.getElementById('pipelineCreateBtn');
    const headerRefreshBtn = document.getElementById('pipelineHeaderRefreshBtn');
    const detailRefreshBtn = document.getElementById('pipelineDetailRefreshBtn');
    const backToListBtn = document.getElementById('pipelineBackToListBtn');
    const viewTabs = document.getElementById('pipelineViewTabs');

    const isDetail = mode === 'detail';
    if (title) title.textContent = isDetail ? '阶段视图' : '流水线视图';
    if (createBtn) createBtn.style.display = isDetail ? 'none' : '';
    if (headerRefreshBtn) headerRefreshBtn.style.display = isDetail ? 'none' : '';
    if (detailRefreshBtn) detailRefreshBtn.style.display = isDetail ? '' : 'none';
    if (backToListBtn) backToListBtn.style.display = isDetail ? '' : 'none';
    if (viewTabs) viewTabs.style.display = isDetail ? 'none' : 'flex';
}

async function loadPipelineJobsView() {
    currentPipelineJobId = null;
    pipelineListRenderToken += 1;
    stopPipelinePolling();
    stopPipelineListPolling();
    setPipelineSectionMode('list');
    await loadPipelineViews(true);
    restoreSelectedPipelineView();
    renderPipelineViewTabs();
    await renderPipelineListPage();
}
window.loadPipelineJobsView = loadPipelineJobsView;

async function renderPipelineListPage() {
    const container = document.getElementById('pipelineJobsView');
    if (!container) return;

    if (!canManagePipelineViews() && (!pipelineViewCache || !pipelineViewCache.length)) {
        container.innerHTML = `
            <div style="padding: 48px; text-align: center; color: #6b7280;">
                <div style="font-size:15px;margin-bottom:8px;">暂无可访问的流水线视图</div>
                <div style="font-size:13px;">请联系管理员在「流水线配置 → 视图管理」中为你的角色分配视图权限</div>
            </div>`;
        return;
    }

    container.innerHTML = `
        <div style="padding: 40px; text-align: center; color: #999;">正在加载流水线任务...</div>
    `;

    try {
        const data = await fetchPipelineJobsPage(1, 10);
        renderPipelineJobsTable(data, container);
    } catch (error) {
        console.error('Load pipeline jobs error:', error);
        container.innerHTML = `<div style="padding: 40px; text-align: center; color: #dc2626;">加载失败: ${escapeHtml(error.message)}</div>`;
    }
}

function isPipelineModalOpen() {
    const container = document.getElementById('modalContainer');
    return !!(container && container.querySelector('.modal-overlay'));
}

function bindPipelineJobsViewDelegation() {
    const container = document.getElementById('pipelineJobsView');
    if (!container || container.dataset.pipelineClickBound === 'true') {
        return;
    }
    container.addEventListener('click', (event) => {
        const detailEl = event.target.closest('[data-pipeline-action="detail"]');
        if (detailEl) {
            event.preventDefault();
            const jobId = parseInt(detailEl.getAttribute('data-job-id'), 10);
            if (jobId) {
                showPipelineDetail(jobId);
            }
            return;
        }
        const stageEl = event.target.closest('[data-pipeline-action="stage-log"]');
        if (stageEl) {
            event.preventDefault();
            event.stopPropagation();
            const jobId = stageEl.getAttribute('data-job-id');
            const stageId = stageEl.getAttribute('data-stage-id');
            const stageName = stageEl.getAttribute('data-stage-name') || '阶段';
            if (jobId && stageId) {
                showPipelineStageLog(jobId, stageId, stageName);
            }
        }
    });
    container.dataset.pipelineClickBound = 'true';
}

function renderMiniStagesForJob(job) {
    const stages = job.latestStages || [];
    const buildId = job.latestBuildId || job.id;
    if (!stages.length) {
        return `<span style="font-size:12px;color:#9ca3af;">暂无构建记录</span>`;
    }
    return `
        <div style="display:flex;flex-wrap:nowrap;gap:6px;align-items:center;white-space:nowrap;overflow-x:auto;overflow-y:hidden;">
            <span style="font-size:11px;color:#6b7280;white-space:nowrap;margin-right:2px;"
                  title="构建 ${escapeHtml(job.jobNumber || buildId)} · ${escapeHtml(job.lastDurationText || '-')}">
                ${getStatusIcon(job.status, job.building)}
            </span>
            ${stages.map(stage => `
                <div title="${escapeHtml(stage.name || '')} · ${escapeHtml(stage.durationText || '-')}"
                     data-pipeline-action="stage-log"
                     data-job-id="${escapeHtml(buildId)}"
                     data-stage-id="${escapeHtml(stage.id)}"
                     data-stage-name="${escapeHtml(stage.name || '阶段')}"
                     style="flex:0 0 auto;min-width:76px;max-width:120px;padding:5px 6px;border-radius:6px;font-size:11px;text-align:center;
                            background:${getStageBg(stage.status)};border:1px solid #e5e7eb;cursor:pointer;">
                    <div style="font-weight:600;white-space:nowrap;overflow:hidden;text-overflow:clip;line-height:1.2;">
                        ${escapeHtml(stage.name || '阶段')}
                    </div>
                    <div style="margin-top:2px;line-height:1;">${getStageStatusIcon(stage.status)}</div>
                </div>
            `).join('')}
        </div>
    `;
}

function renderPipelineCreatorCell(job) {
    const name = job.creatorName ? escapeHtml(job.creatorName) : '-';
    return `<td class="pipeline-col-creator" title="${name}">${name}</td>`;
}

function renderPipelineJobsTable(pageData, container) {
    const jobs = pageData?.items || [];
    const currentView = pipelineViewCache.find(v => String(v.id) === String(currentPipelineViewId));
    const viewHint = currentView
        ? `当前视图：${escapeHtml(currentView.name)}（环境 ${escapeHtml(currentView.envName || '-')}）`
        : '当前视图：全部';

    if (!jobs.length) {
        container.innerHTML = `
            <div style="padding: 40px; text-align: center; color: #999;">
                <p>${viewHint}</p>
                <p style="margin-top:12px;">暂无 Job</p>
                <p style="font-size: 13px; margin-top: 8px;">可点击「新建 Job」创建，或切换其他视图查看</p>
            </div>
        `;
        return;
    }

    container.innerHTML = `
        <div class="pipeline-jobs-table">
            <div style="font-size: 13px; color: #6b7280; margin-bottom: 10px;">${viewHint} · 点击服务名称进入阶段视图</div>
            <table class="pipeline-table">
                <thead>
                    <tr>
                        <th style="width:84px;">部署环境</th>
                        <th style="width:180px;">服务名称</th>
                        <th style="width:260px;">仓库地址</th>
                        <th style="width:130px;">tag/branch</th>
                        <th>最近构建</th>
                        <th style="width:96px;">构建用户</th>
                        <th style="width: 210px;">操作</th>
                    </tr>
                </thead>
                <tbody>
                    ${jobs.map(job => `
                        <tr data-job-id="${job.id}">
                            <td class="pipeline-col-env">${escapeHtml(job.envName || '-')}</td>
                            <td class="pipeline-col-service">
                                <a href="javascript:void(0)" data-pipeline-action="detail" data-job-id="${job.id}"
                                   style="font-weight: 500; color: #2563eb; text-decoration: none;"
                                   onmouseover="this.style.textDecoration='underline'"
                                   onmouseout="this.style.textDecoration='none'">
                                    ${escapeHtml(job.serviceName || job.taskName || job.name || '-')}
                                </a>
                            </td>
                            <td class="pipeline-col-repo" style="font-size:12px;" title="${escapeHtml(job.gitRepo || '')}">
                                ${escapeHtml(job.gitRepo || '-')}
                            </td>
                            ${renderPipelineGitRefCell(job)}
                            <td class="pipeline-col-stages">${renderMiniStagesForJob(job)}</td>
                            ${renderPipelineCreatorCell(job)}
                            <td>
                                <div class="pipeline-job-actions">
                                    <button class="btn-success" onclick="window.triggerPipelineBuild(${resolvePipelineRunJobId(job)}, ${job.id})" ${isPipelineRunDisabled(job) ? 'disabled' : ''} title="${escapeHtml(getPipelineRunButtonTitle(job) || (job.fromDeployTask ? '重试上线任务 CD 部署' : ''))}">${getPipelineRunButtonLabel(job)}</button>
                                    ${(typeof hasPermission !== 'function' || hasPermission('pipeline:rollback')) ? `<button class="btn-secondary btn-rollback" onclick="window.showPipelineRollbackModal(${resolvePipelineRunJobId(job)}, ${job.id})" ${canRollbackPipelineJob(job) ? '' : 'disabled'} title="${escapeHtml(getPipelineRollbackButtonTitle(job))}">回滚</button>` : ''}
                                    ${(typeof hasPermission !== 'function' || hasPermission('pipeline:edit')) ? `<button class="btn-edit" onclick="window.showEditPipelineTaskModal(${job.id})" ${job.editable === false ? 'disabled' : ''}>编辑</button>` : ''}
                                    ${(typeof hasPermission !== 'function' || hasPermission('pipeline:delete')) ? `<button class="btn-danger" onclick="window.deletePipelineTask(${job.fromDeployTask ? (job.latestBuildId || job.id) : job.id})" ${job.deletable === false ? 'disabled' : ''} title="${job.fromDeployTask ? '删除本次上线任务触发的 CD 记录' : ''}">删除</button>` : ''}
                                </div>
                            </td>
                        </tr>
                    `).join('')}
                </tbody>
            </table>
        </div>
    `;

    bindPipelineJobsViewDelegation();
    bindPipelineListGitRefDelegation();
    initPipelineListGitRefCells(jobs);

    if (jobs.some(j => j.building)) {
        startPipelineListPolling();
    } else {
        // 如果是用户刚点击了 Run 的“强制轮询”场景，
        // 前端可能在 Job 标记 building 之前就渲染了一次旧数据，
        // 这时不应该立刻停掉轮询，否则“看不到任何反应”会更明显。
        if (!pipelineListForcePollingJobId) {
            stopPipelineListPolling();
        }
    }
}

/**
 * 轮询时尽量只更新阶段列和 Run 按钮，避免整表重绘打断点击。
 */
function patchPipelineJobsTable(pageData, container) {
    const tbody = container.querySelector('.pipeline-table tbody');
    if (!tbody) {
        renderPipelineJobsTable(pageData, container);
        return;
    }
    const jobs = pageData?.items || [];
    const existingIds = Array.from(tbody.querySelectorAll('tr[data-job-id]'))
        .map(tr => tr.getAttribute('data-job-id'));
    const newIds = jobs.map(j => String(j.id));
    if (existingIds.join(',') !== newIds.join(',')) {
        renderPipelineJobsTable(pageData, container);
        return;
    }
    jobs.forEach(job => {
        const tr = tbody.querySelector(`tr[data-job-id="${job.id}"]`);
        if (!tr) return;
        const stagesTd = tr.querySelector('.pipeline-col-stages');
        if (stagesTd) {
            stagesTd.innerHTML = renderMiniStagesForJob(job);
        }
        const runBtn = tr.querySelector('.btn-success');
        if (runBtn) {
            runBtn.disabled = isPipelineRunDisabled(job);
            runBtn.textContent = getPipelineRunButtonLabel(job);
            const title = getPipelineRunButtonTitle(job) || (job.fromDeployTask ? '重试上线任务 CD 部署' : '');
            if (title) {
                runBtn.title = title;
            } else {
                runBtn.removeAttribute('title');
            }
        }
        const rollbackBtn = tr.querySelector('.btn-rollback');
        if (rollbackBtn) {
            rollbackBtn.disabled = !canRollbackPipelineJob(job);
        }
    });
}

let pipelineListPollTimer = null;
let pipelineListForcePollingJobId = null;
let pipelineListForcePollingJobStarted = false;
let pipelineListForcePollingStartAt = 0;
const PIPELINE_LIST_FORCE_POLLING_MAX_MS = 2 * 60 * 1000;

function startPipelineListPolling() {
    if (pipelineListPollTimer) {
        clearInterval(pipelineListPollTimer);
        pipelineListPollTimer = null;
    }
    const intervalMs = pipelineListForcePollingJobId ? 1000 : 3000;
    const tick = async () => {
        if (currentPipelineJobId) return;
        const container = document.getElementById('pipelineJobsView');
        if (!container) {
            stopPipelineListPolling();
            return;
        }
        const renderToken = pipelineListRenderToken;
        try {
            const data = await fetchPipelineJobsPage(1, 10);
            // 点击服务名称进入详情后，丢弃过期的列表渲染
            if (renderToken !== pipelineListRenderToken || currentPipelineJobId) {
                return;
            }
            if (pipelineListForcePollingJobId) {
                // 超时兜底：避免某些情况下无法再触发 stop
                if (Date.now() - pipelineListForcePollingStartAt > PIPELINE_LIST_FORCE_POLLING_MAX_MS) {
                    pipelineListForcePollingJobId = null;
                    pipelineListForcePollingJobStarted = false;
                    stopPipelineListPolling();
                    return;
                }

                const targetJob = (data?.items || []).find(j => String(j.id) === String(pipelineListForcePollingJobId));
                if (targetJob) {
                    if (targetJob.building) {
                        pipelineListForcePollingJobStarted = true;
                    } else if (pipelineListForcePollingJobStarted) {
                        // 该 Job 已从 building 状态退出，结束强制轮询
                        pipelineListForcePollingJobId = null;
                        pipelineListForcePollingJobStarted = false;
                        stopPipelineListPolling();
                    }
                }
            }
            if (renderToken !== pipelineListRenderToken || currentPipelineJobId) {
                return;
            }
            patchPipelineJobsTable(data, container);
        } catch (e) {
            console.warn('Pipeline list polling failed', e);
        }
    };
    tick();
    pipelineListPollTimer = setInterval(tick, intervalMs);
}

function stopPipelineListPolling() {
    if (pipelineListPollTimer) {
        clearInterval(pipelineListPollTimer);
        pipelineListPollTimer = null;
    }
    // 停止轮询就清掉强制轮询状态，避免切换页面后残留逻辑。
    pipelineListForcePollingJobId = null;
    pipelineListForcePollingJobStarted = false;
    pipelineListForcePollingStartAt = 0;
}

async function showPipelineDetail(jobId) {
    const isNewTask = currentPipelineJobId !== jobId;
    currentPipelineJobId = jobId;
    pipelineListRenderToken += 1;
    stopPipelineListPolling();
    if (isNewTask) {
        pipelineStageViewPage = 1;
    }
    const container = document.getElementById('pipelineJobsView');
    if (!container) return;

    setPipelineSectionMode('detail');
    bindPipelineJobsViewDelegation();

    container.innerHTML = `
        <div id="pipelineStageViewContainer" style="background: #fff; padding: 20px; border-radius: 6px; border: 1px solid #e5e7eb;">
            <div style="padding: 40px; text-align: center; color: #999;">加载阶段视图...</div>
        </div>
    `;

    await loadPipelineStageView(jobId, pipelineStageViewPage);
    startPipelinePolling();
}
window.showPipelineDetail = showPipelineDetail;
window.showPipelineView = showPipelineDetail;

async function fetchPipelineStageView(jobId, page, pageSize) {
    const response = await fetch(`/api/pipeline-run/${jobId}/stage-view?page=${page}&pageSize=${pageSize}`);
    if (!response.ok) {
        throw new Error(await response.text() || response.statusText);
    }
    return response.json();
}

async function loadPipelineStageView(jobId, page, options) {
    const box = document.getElementById('pipelineStageViewContainer');
    if (!box) return;

    const pageSize = getPipelinePageSize();
    pipelineStageViewPage = page || 1;
    const renderToken = ++pipelineStageRenderToken;
    const fromPoll = !!(options && options.fromPoll);

    try {
        const view = await fetchPipelineStageView(jobId, pipelineStageViewPage, pageSize);
        if (renderToken !== pipelineStageRenderToken) {
            return null;
        }
        if (String(currentPipelineJobId) !== String(jobId)) {
            return null;
        }
        // 轮询期间若日志弹窗打开，跳过整页重绘，避免点不到阶段卡片
        if (fromPoll && isPipelineModalOpen()) {
            return view;
        }
        currentPipelineJobId = jobId;
        box.innerHTML = renderPipelineStageViewHtml(view);
        return view;
    } catch (error) {
        if (renderToken !== pipelineStageRenderToken) {
            return null;
        }
        box.innerHTML = `<div style="padding: 40px; text-align: center; color: #dc2626;">加载失败: ${escapeHtml(error.message)}</div>`;
        return null;
    }
}

function renderPipelineStageViewHtml(view) {
    const page = view.currentPage || 1;
    const pageSize = view.pageSize || getPipelinePageSize();
    const total = view.totalBuilds || 0;
    const totalPages = view.totalPages || 1;
    const highlightId = view.highlightJobId;
    const builds = view.buildHistory || [];

    let averageStagesHtml = '';
    if (view.averageStageTimesText && Object.keys(view.averageStageTimesText).length > 0) {
        const maxDuration = Math.max(...Object.values(view.averageStageTimes || {}), 1);
        averageStagesHtml = `
            <div style="margin: 16px 0; padding: 14px; background: #f9fafb; border-radius: 6px; border: 1px solid #e5e7eb;">
                <div style="font-size: 13px; color: #374151; margin-bottom: 10px;">
                    <strong>平均阶段耗时</strong>
                    <span style="color: #6b7280; margin-left: 8px;">（本页完整运行约 ${escapeHtml(view.averageFullRunTimeText || '-') }）</span>
                </div>
                <div style="display: flex; flex-wrap: wrap; gap: 12px;">
                    ${Object.entries(view.averageStageTimesText).map(([name, time]) => {
                        const duration = view.averageStageTimes[name] || 0;
                        const widthPercent = maxDuration > 0 ? (duration / maxDuration * 100) : 0;
                        return `
                            <div style="min-width: 160px; flex: 1;">
                                <div style="display:flex;justify-content:space-between;font-size:12px;color:#6b7280;">
                                    <span>${escapeHtml(name)}</span>
                                    <span>${escapeHtml(time)}</span>
                                </div>
                                <div style="height:6px;background:#e5e7eb;border-radius:3px;margin-top:4px;">
                                    <div style="height:100%;width:${widthPercent}%;background:#667eea;border-radius:3px;"></div>
                                </div>
                            </div>
                        `;
                    }).join('')}
                </div>
            </div>
        `;
    }

    const buildsHtml = builds.length ? builds.map(build => {
        const isHighlight = String(build.jobId) === String(highlightId);
        const stages = build.stages || [];
        const stagesHtml = stages.length
            ? `<div class="pipeline-build-stages" style="--stage-count: ${stages.length}">${stages.map(stage => renderStageCard(build.jobId, stage)).join('')}</div>`
            : '<div class="pipeline-build-stages-empty">暂无阶段信息</div>';

        return `
            <div class="pipeline-build-card${isHighlight ? ' pipeline-build-card--highlight' : ''}" data-job-id="${build.jobId}">
                <div class="pipeline-build-header">
                    <div class="pipeline-build-title" onclick="window.focusPipelineBuild(${build.jobId})">
                        #${escapeHtml(build.jobNumber || build.jobId)}
                        ${isHighlight ? '<span class="pipeline-build-current-tag">当前</span>' : ''}
                    </div>
                    <div class="pipeline-build-meta">${escapeHtml(build.buildTimeText || '-')}</div>
                    <div class="pipeline-build-meta">${escapeHtml(build.totalDurationText || '-')}</div>
                    <div class="pipeline-build-nodes"
                         title="CI 节点: ${escapeHtml(build.buildNodeDisplay || '本机')} / CD 节点: ${escapeHtml(build.deployNodeDisplay || '本机')}">
                        <span class="pipeline-build-node-label">CI 节点:</span> ${escapeHtml(build.buildNodeDisplay || '本机')}
                        <span class="pipeline-build-node-sep">/</span>
                        <span class="pipeline-build-node-label">CD 节点:</span> ${escapeHtml(build.deployNodeDisplay || '本机')}
                    </div>
                    <div class="pipeline-build-creator" title="${escapeHtml(build.creatorName || '-')}">
                        ${escapeHtml(build.creatorName || '-')}
                    </div>
                    <div class="pipeline-build-status">${getStatusIcon(build.status, build.building)}</div>
                </div>
                ${stagesHtml}
            </div>
        `;
    }).join('') : '<div class="pipeline-build-empty">暂无构建历史</div>';

    return `
        <div class="pipeline-view">
            <div class="pipeline-view-summary">
                <div class="pipeline-view-summary-main">
                    ${escapeHtml(view.taskName || view.jobName || '流水线任务')}
                    · ${escapeHtml(view.serviceName || '-')} / ${escapeHtml(view.envName || '-')} / ${escapeHtml(view.branch || '-')}
                    · ${escapeHtml(view.pipelineTemplateName || '-')}
                </div>
                <div class="pipeline-view-summary-sub">
                    CI 节点: ${escapeHtml(view.buildNodeDisplay || '本机')} / CD 节点: ${escapeHtml(view.deployNodeDisplay || '本机')}
                </div>
                <div class="pipeline-view-summary-status">${getStatusIcon(view.currentStatus, false)} <span>${escapeHtml(view.currentStatus || '-')}</span></div>
            </div>

            ${averageStagesHtml}

            <div class="pipeline-build-history">
                <div class="pipeline-build-history-header">
                    <h4>构建历史</h4>
                    ${renderPageSizeSelect(pageSize, 'window.changePipelineStageViewPageSize')}
                </div>
                <div class="pipeline-build-list">
                    ${buildsHtml}
                </div>
                ${renderPaginationBar(page, totalPages, total, 'window.gotoPipelineStageViewPage')}
            </div>
        </div>
    `;
}

function focusPipelineBuild(jobId) {
    currentPipelineJobId = jobId;
    loadPipelineStageView(jobId, pipelineStageViewPage);
}
window.focusPipelineBuild = focusPipelineBuild;

function changePipelineStageViewPageSize(size) {
    setPipelinePageSize(size);
    pipelineStageViewPage = 1;
    if (currentPipelineJobId) {
        loadPipelineStageView(currentPipelineJobId, 1);
    }
}
window.changePipelineStageViewPageSize = changePipelineStageViewPageSize;

function gotoPipelineStageViewPage(page) {
    pipelineStageViewPage = page;
    if (currentPipelineJobId) {
        loadPipelineStageView(currentPipelineJobId, page);
    }
}
window.gotoPipelineStageViewPage = gotoPipelineStageViewPage;

function renderStageCard(jobId, stage) {
    const bg = getStageBg(stage.status);
    return `
        <div class="pipeline-stage-card"
             style="background: ${bg};"
             data-pipeline-action="stage-log"
             data-job-id="${escapeHtml(jobId)}"
             data-stage-id="${escapeHtml(stage.id)}"
             data-stage-name="${escapeHtml(stage.name || '阶段')}">
            <div class="pipeline-stage-card-name">${escapeHtml(stage.name || '阶段')}</div>
            <div class="pipeline-stage-card-duration">${escapeHtml(stage.durationText || '-')}</div>
            <div class="pipeline-stage-card-status">${getStageStatusIcon(stage.status)}</div>
        </div>
    `;
}

async function showPipelineStageLog(jobId, stageId, stageName) {
    if (typeof window.showModal !== 'function') {
        alert('模态框未加载');
        return;
    }

    stopPipelineStageLogPolling();
    window.__pipelineStageLogContext = {
        jobId,
        stageId,
        stageName,
        offset: 0,
        buffer: '',
        finalReloadDone: false
    };
    window.showModal(
        `阶段日志 - ${stageName || '阶段'}`,
        `<div id="pipelineStageLogMeta" style="padding:8px 16px 0;font-size:12px;color:#6b7280;">加载中...</div>
         <div id="pipelineStageLogBox" style="padding:12px 16px 20px;color:#999;">加载中...</div>`,
        null
    );

    await refreshPipelineStageLog(true);
    startPipelineStageLogPolling();
}

async function refreshPipelineStageLog(forceScroll) {
    const ctx = window.__pipelineStageLogContext;
    if (!ctx || !ctx.jobId || !ctx.stageId) {
        return;
    }
    const box = document.getElementById('pipelineStageLogBox');
    const meta = document.getElementById('pipelineStageLogMeta');
    if (!box) {
        stopPipelineStageLogPolling();
        return;
    }

    try {
        let guard = 0;
        let running = false;
        let status = '-';
        while (guard < 32) {
            guard += 1;
            const response = await fetch(
                `/api/pipeline-run/${ctx.jobId}/stage/${encodeURIComponent(ctx.stageId)}/log?offset=${ctx.offset || 0}`
            );
            if (!response.ok) {
                throw new Error(await response.text() || response.statusText);
            }
            const data = await response.json();
            running = !!data.running;
            status = data.status || '-';

            // 步骤刚结束时文件可能被终态完整日志覆盖，强制从头读一次
            if (ctx.wasRunning && !running) {
                ctx.wasRunning = false;
                ctx.offset = 0;
                ctx.buffer = '';
                continue;
            }
            ctx.wasRunning = running;

            // 文件被重写变短：从头再读
            if (data.reset || (typeof data.size === 'number' && data.size < (ctx.offset || 0))) {
                ctx.offset = 0;
                ctx.buffer = '';
                continue;
            }

            const chunk = data.log || '';
            if (chunk) {
                ctx.buffer = (ctx.buffer || '') + chunk;
            }
            ctx.offset = typeof data.nextOffset === 'number' ? data.nextOffset : (ctx.offset || 0);

            if (meta) {
                const sizeText = typeof data.size === 'number' ? ` · ${data.size} bytes` : '';
                meta.innerHTML = running
                    ? `<span style="color:#2563eb;">● 执行中</span> · 状态 ${escapeHtml(status)}${sizeText} · 增量刷新`
                    : `<span>状态 ${escapeHtml(status)}</span>${sizeText}`;
            }

            // 执行中：本轮先展示，等下次轮询再拉新增量
            if (running) {
                break;
            }
            // 已结束：把剩余 chunk 拉完
            if (data.eof) {
                break;
            }
        }

        const pre = box.querySelector('pre');
        const logText = ctx.buffer || '暂无日志';
        const nearBottom = pre
            ? (pre.scrollTop + pre.clientHeight >= pre.scrollHeight - 40)
            : true;

        box.innerHTML = `<pre style="margin:0; white-space:pre-wrap; background:#1e1e1e; color:#d4d4d4; padding:16px; border-radius:4px; max-height:60vh; overflow:auto;">${escapeHtml(logText)}</pre>`;
        const newPre = box.querySelector('pre');
        if (newPre && (forceScroll || running || nearBottom)) {
            newPre.scrollTop = newPre.scrollHeight;
        }

        if (!running) {
            stopPipelineStageLogPolling();
        }
    } catch (error) {
        box.innerHTML = `<div style="color:#dc2626;">加载失败: ${escapeHtml(error.message)}</div>`;
    }
}

function startPipelineStageLogPolling() {
    stopPipelineStageLogPolling();
    window.__pipelineStageLogTimer = setInterval(() => {
        refreshPipelineStageLog(false);
    }, 1000);
}

function stopPipelineStageLogPolling() {
    if (window.__pipelineStageLogTimer) {
        clearInterval(window.__pipelineStageLogTimer);
        window.__pipelineStageLogTimer = null;
    }
}

window.showPipelineStageLog = showPipelineStageLog;
window.stopPipelineStageLogPolling = stopPipelineStageLogPolling;

function startPipelinePolling() {
    stopPipelinePolling();
    // 先立即刷一次，再按 1s 轮询，保证步骤状态接近实时
    const tick = async () => {
        if (!currentPipelineJobId) return;
        try {
            const view = await loadPipelineStageView(currentPipelineJobId, pipelineStageViewPage, { fromPoll: true });
            if (!view) return;
            const hasBuilding = (view.buildHistory || []).some(b => b.building)
                || ['BUILDING', 'DEPLOYING', 'PENDING'].includes(String(view.currentStatus || '').toUpperCase());
            if (!hasBuilding) {
                stopPipelinePolling();
            }
        } catch (e) {
            console.warn('Pipeline polling failed', e);
        }
    };
    tick();
    pipelinePollTimer = setInterval(tick, 1000);
}

function stopPipelinePolling() {
    if (pipelinePollTimer) {
        clearInterval(pipelinePollTimer);
        pipelinePollTimer = null;
    }
}

function getStatusIcon(status, building) {
    if (building) return '<span title="运行中">🔄</span>';
    switch ((status || '').toUpperCase()) {
        case 'SUCCESS': return '<span title="成功">✓</span>';
        case 'FAILED': return '<span title="失败">✗</span>';
        case 'BUILDING':
        case 'DEPLOYING': return '<span title="运行中">🔄</span>';
        default: return '<span title="等待">○</span>';
    }
}

function getStageStatusIcon(status) {
    switch ((status || '').toUpperCase()) {
        case 'SUCCESS':
        case 'SUCCESSFUL': return '✓';
        case 'FAILURE':
        case 'FAILED': return '✗';
        case 'IN_PROGRESS':
        case 'RUNNING': return '🔄';
        default: return '○';
    }
}

function getStageBg(status) {
    switch ((status || '').toUpperCase()) {
        case 'SUCCESS':
        case 'SUCCESSFUL': return '#d1fae5';
        case 'FAILURE':
        case 'FAILED': return '#fee2e2';
        case 'IN_PROGRESS':
        case 'RUNNING': return '#dbeafe';
        default: return '#f9fafb';
    }
}

function escapeHtml(text) {
    if (text == null) return '';
    return String(text)
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;')
        .replace(/'/g, '&#39;');
}

async function showCreatePipelineTaskModal() {
    await openPipelineTaskModal(null);
}
window.showCreatePipelineTaskModal = showCreatePipelineTaskModal;

async function showEditPipelineTaskModal(jobId) {
    await openPipelineTaskModal(jobId);
}
window.showEditPipelineTaskModal = showEditPipelineTaskModal;

async function openPipelineTaskModal(jobId) {
    if (typeof window.showModal !== 'function') {
        alert('模态框未加载，请刷新页面');
        return;
    }

    let editingJob = null;
    if (jobId) {
        try {
            const jobResp = await fetch(`/api/pipeline-run/${jobId}`);
            if (!jobResp.ok) {
                throw new Error(await jobResp.text() || '加载任务失败');
            }
            editingJob = await jobResp.json();
            if (editingJob.editable === false || editingJob.building) {
                alert('任务运行中，无法编辑');
                return;
            }
        } catch (error) {
            alert('加载任务失败: ' + error.message);
            return;
        }
    }

    const services = typeof window.getServicesList === 'function'
        ? await window.getServicesList()
        : [];

    if (!services.length) {
        alert('暂无可用服务，请先在「服务管理」中添加服务');
        return;
    }

    let pipelines = [];
    try {
        const pipelineResp = await fetch('/api/pipeline/list');
        if (pipelineResp.ok) {
            pipelines = await pipelineResp.json();
        }
    } catch (error) {
        console.error('Load pipelines error:', error);
    }

    const enabledPipelines = (pipelines || []).filter(p =>
        (p.status === 1 || p.status == null) && p.pipelineType !== 'cd');
    if (!enabledPipelines.length) {
        alert('暂无可用 CI/CI/CD 流水线模版，请先在「流水线配置 → 流水线管理」中配置');
        return;
    }

    let envs = [];
    try {
        const envResp = await fetch('/api/build/envs');
        if (envResp.ok) {
            envs = await envResp.json();
        }
    } catch (error) {
        console.error('Load envs error:', error);
    }

    if (!envs.length) {
        alert('暂无可用环境，请先在「环境配置」中添加非生产环境');
        return;
    }

    const isEdit = !!editingJob;
    const defaultPipelineId = editingJob?.pipelineTemplateId || enabledPipelines[0].id;
    const defaultGitType = editingJob?.gitType || 'branch';
    const formId = isEdit ? 'editPipelineTaskForm' : 'createPipelineTaskForm';
    const content = `
        <form id="${formId}" style="max-height: 70vh; overflow-y: auto;">
            ${isEdit ? `<input type="hidden" name="jobId" value="${editingJob.id}">` : ''}
            <div class="form-item">
                <label>部署环境 *</label>
                <select name="envId" required>
                    <option value="">请选择环境</option>
                    ${envs.map(e => `
                        <option value="${e.id}" ${editingJob && String(e.id) === String(editingJob.envId) ? 'selected' : ''}>
                            ${escapeHtml(e.name)}
                        </option>
                    `).join('')}
                </select>
            </div>
            <div class="form-item">
                <label>服务 *</label>
                <select name="serviceId" id="pipelineTaskServiceSelect" required>
                    <option value="">请选择服务</option>
                    ${services.map(s => `
                        <option value="${s.id}"
                                ${editingJob && String(s.id) === String(editingJob.serviceId) ? 'selected' : ''}
                                data-service-name="${escapeHtml(s.name)}"
                                data-git-repo="${escapeHtml(s.gitRepo || '')}">
                            ${escapeHtml(s.name)} (${escapeHtml(s.code || s.name)})
                        </option>
                    `).join('')}
                </select>
            </div>
            <div class="form-item">
                <label>Pipeline 模板 *</label>
                <select name="pipelineTemplateId" required>
                    ${enabledPipelines.map(p => `
                        <option value="${p.id}" ${String(p.id) === String(defaultPipelineId) ? 'selected' : ''}>
                            ${escapeHtml(p.name)}${p.description ? ' - ' + escapeHtml(p.description) : ''}
                        </option>
                    `).join('')}
                </select>
            </div>
            <div class="form-item">
                <label>tag/branch *</label>
                <select name="gitType" id="pipelineTaskGitTypeSelect" required onchange="onPipelineGitTypeChange()">
                    ${renderPipelineGitTypeOptions(defaultGitType)}
                </select>
            </div>
            <div class="form-item" id="pipelineTaskRefField">
                <label id="pipelineTaskRefLabel">Git ${getPipelineGitTypeLabel(defaultGitType)} *</label>
                <div style="display:flex;gap:8px;align-items:center;">
                    <select name="branch" id="pipelineTaskBranchSelect" required style="flex:1;">
                        <option value="${isEdit && editingJob?.branch ? escapeHtml(editingJob.branch) : ''}" ${isEdit && editingJob?.branch ? 'selected' : ''}>
                            ${isEdit && editingJob?.branch ? escapeHtml(editingJob.branch) : '请先选择服务并点击加载'}
                        </option>
                    </select>
                    <button type="button" class="btn-secondary" onclick="loadPipelineGitRefs()">加载</button>
                </div>
                <small id="pipelineTaskRefHint" style="color:#666;display:block;margin-top:4px;">选择服务与 Git 类型后，点击「加载」获取${getPipelineGitTypeLabel(defaultGitType)}列表</small>
            </div>
            ${isEdit ? '' : `
            <div class="form-item">
                <label style="display: flex; align-items: center; gap: 8px;">
                    <input type="checkbox" name="autoDeploy" checked> 构建完成后自动部署
                </label>
            </div>`}
        </form>
    `;

    window.showModal(isEdit ? '编辑 Job' : '新建 Job', content,
        isEdit ? submitEditPipelineTask : submitCreatePipelineTask, 'medium');

    setTimeout(() => {
        bindPipelineTaskFormSuggest(formId);
        const serviceSelect = document.getElementById('pipelineTaskServiceSelect');
        const initialServiceId = serviceSelect?.value;
        const initialBranch = editingJob?.branch || '';
        if (initialServiceId) {
            loadServiceBranches(initialServiceId, initialBranch);
        }
    }, 100);
}

async function loadServiceBranches(serviceId, selectedBranch, options = {}) {
    const { showAlertOnError = false, requireService = true } = options;
    const branchSelect = document.getElementById('pipelineTaskBranchSelect');
    if (!branchSelect) return false;

    const gitTypeSelect = document.getElementById('pipelineTaskGitTypeSelect');
    const gitType = gitTypeSelect?.value === 'tag' ? 'tag' : 'branch';
    const refLabel = getPipelineGitTypeLabel(gitType);
    const labelEl = document.getElementById('pipelineTaskRefLabel');
    const hintEl = document.getElementById('pipelineTaskRefHint');
    if (labelEl) labelEl.textContent = `Git ${refLabel} *`;
    if (hintEl) hintEl.textContent = `选择服务与 Git 类型后，点击「加载」获取${refLabel}列表`;

    if (!serviceId) {
        if (requireService) {
            resetPipelineBranchSelect('请先选择服务');
        }
        return false;
    }

    branchSelect.innerHTML = `<option value="">加载${refLabel}...</option>`;
    branchSelect.disabled = true;

    try {
        const response = await fetch(`/api/build/service/${serviceId}/branches?type=${encodeURIComponent(gitType)}`);
        let branches = response.ok ? await response.json() : [];
        if (!Array.isArray(branches)) {
            branches = [];
        }
        const unique = [...new Set(branches.filter(b => b && String(b).trim()))];
        if (!unique.length) {
            branchSelect.innerHTML = `<option value="">${gitType === 'tag' ? '未获取到 Tag' : '未获取到分支'}</option>`;
            branchSelect.disabled = false;
            return false;
        }

        const preferred = selectedBranch
            || unique.find(b => ['main', 'master', 'develop'].includes(b))
            || unique[0];
        branchSelect.innerHTML = unique.map(b => `
            <option value="${escapeHtml(b)}" ${b === preferred ? 'selected' : ''}>${escapeHtml(b)}</option>
        `).join('');
        branchSelect.disabled = false;
        return true;
    } catch (error) {
        console.error('Load git refs error:', error);
        branchSelect.innerHTML = '<option value="">加载失败</option>';
        branchSelect.disabled = false;
        if (showAlertOnError) {
            alert(`加载 Git ${refLabel} 失败: ` + error.message);
        }
        return false;
    }
}
window.loadServiceBranches = loadServiceBranches;

function resetPipelineBranchSelect(message) {
    const branchSelect = document.getElementById('pipelineTaskBranchSelect');
    if (!branchSelect) return;
    branchSelect.innerHTML = `<option value="">${message || '请先选择服务并点击加载'}</option>`;
    branchSelect.disabled = false;
}

async function loadPipelineGitRefs() {
    const serviceSelect = document.getElementById('pipelineTaskServiceSelect');
    const serviceId = serviceSelect?.value;
    if (!serviceId) {
        alert('请先选择服务');
        return;
    }
    const branchSelect = document.getElementById('pipelineTaskBranchSelect');
    const current = branchSelect?.value || '';
    await loadServiceBranches(serviceId, current || null, { showAlertOnError: true, requireService: true });
}
window.loadPipelineGitRefs = loadPipelineGitRefs;

function onPipelineGitTypeChange() {
    resetPipelineBranchSelect();
}
window.onPipelineGitTypeChange = onPipelineGitTypeChange;

function bindPipelineTaskFormSuggest(formId) {
    const form = document.getElementById(formId);
    if (!form) return;
    const serviceSelect = document.getElementById('pipelineTaskServiceSelect');

    if (serviceSelect) {
        serviceSelect.addEventListener('change', function () {
            resetPipelineBranchSelect();
        });
    }
}

async function submitEditPipelineTask() {
    const form = document.getElementById('editPipelineTaskForm');
    if (!form) return;

    const jobId = parseInt(form.querySelector('[name="jobId"]')?.value, 10);
    const formData = new FormData(form);
    const payload = buildPipelineTaskPayload(formData);
    if (!payload || !jobId) return;

    try {
        const response = await fetch(`/api/pipeline-run/${jobId}`, {
            method: 'PUT',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(payload)
        });

        let result = {};
        try {
            result = await response.json();
        } catch (e) {
            result = {};
        }

        if (!response.ok) {
            throw new Error(result.message || result.error || response.statusText || '更新失败');
        }

        if (typeof window.closeModal === 'function') {
            window.closeModal();
        }
        await loadPipelineJobsView();
        alert('任务已更新');
    } catch (error) {
        console.error('Update pipeline task error:', error);
        alert('更新失败: ' + error.message);
    }
}
window.submitEditPipelineTask = submitEditPipelineTask;

let pipelineRollbackContext = null;

async function showPipelineRollbackModal(jobId, rowJobId) {
    if (typeof window.showModal !== 'function') {
        alert('弹窗组件未加载');
        return;
    }
    const rowId = rowJobId != null ? rowJobId : jobId;
    pipelineRollbackContext = { jobId, rowId, prodEnv: false };

    window.showModal('回滚到历史版本', `
        <div id="pipelineRollbackBox" style="padding:8px 0;color:#6b7280;">正在加载可回滚版本...</div>
    `, null);

    try {
        const jobResp = await fetch(`/api/pipeline-run/${jobId}`);
        if (jobResp.ok) {
            const job = await jobResp.json();
            pipelineRollbackContext.prodEnv = !!job.prodEnv;
            pipelineRollbackContext.serviceName = job.serviceName || job.taskName || '';
            pipelineRollbackContext.envName = job.envName || '';
        }

        const response = await fetch(`/api/pipeline-run/${jobId}/rollback-candidates`);
        let candidates = [];
        if (response.ok) {
            candidates = await response.json() || [];
        } else {
            let err = {};
            try { err = await response.json(); } catch (e) { /* ignore */ }
            throw new Error(err.message || err.error || '加载回滚版本失败');
        }

        const box = document.getElementById('pipelineRollbackBox');
        if (!box) return;

        const selectable = candidates.filter(c => !c.current);
        if (!selectable.length) {
            box.innerHTML = `
                <div style="color:#b45309;padding:12px 0;">
                    暂无可回滚版本。需要至少有一条不同于当前线上的历史成功部署记录。
                </div>
            `;
            return;
        }

        const current = candidates.find(c => c.current);
        const options = selectable.map((c, idx) => `
            <label style="display:flex;gap:10px;align-items:flex-start;padding:10px 12px;border:1px solid #e5e7eb;border-radius:8px;margin-bottom:8px;cursor:pointer;background:${idx === 0 ? '#f8fafc' : '#fff'};">
                <input type="radio" name="pipelineRollbackSource" value="${c.jobId}" ${idx === 0 ? 'checked' : ''} style="margin-top:3px;">
                <span style="flex:1;min-width:0;">
                    <div style="font-weight:600;color:#111827;word-break:break-all;">${escapeHtml(c.imageTag || c.imageFullName || '-')}</div>
                    <div style="font-size:12px;color:#6b7280;margin-top:4px;word-break:break-all;">${escapeHtml(c.imageFullName || '-')}</div>
                    <div style="font-size:12px;color:#9ca3af;margin-top:4px;">
                        ${escapeHtml(c.createTimeText || '-')} · ${escapeHtml(c.jobNumber || '')}
                    </div>
                </span>
            </label>
        `).join('');

        box.innerHTML = `
            <div style="margin-bottom:12px;font-size:13px;color:#4b5563;line-height:1.5;">
                将在原任务上为 <b>${escapeHtml(pipelineRollbackContext.serviceName || '当前服务')}</b>
                （环境 <b>${escapeHtml(pipelineRollbackContext.envName || '-')}</b>）
                仅重跑 CD 步骤，重新部署到所选历史成功镜像（不新建 Job）。
            </div>
            ${current ? `
                <div style="margin-bottom:12px;padding:10px 12px;background:#f9fafb;border:1px solid #e5e7eb;border-radius:8px;font-size:12px;color:#6b7280;">
                    当前成功版本：<span style="color:#111827;word-break:break-all;">${escapeHtml(current.imageFullName || current.imageTag || '-')}</span>
                </div>
            ` : ''}
            <div style="margin-bottom:8px;font-size:13px;font-weight:600;color:#374151;">选择回滚目标</div>
            <div style="max-height:280px;overflow:auto;padding-right:4px;">${options}</div>
            ${pipelineRollbackContext.prodEnv ? `
                <label style="display:flex;align-items:center;gap:8px;margin-top:14px;padding:10px 12px;background:#fff7ed;border:1px solid #fdba74;border-radius:8px;color:#9a3412;font-size:13px;">
                    <input type="checkbox" id="pipelineRollbackConfirmProd">
                    <span>我确认这是生产环境回滚，目标版本正确</span>
                </label>
            ` : ''}
            <div style="margin-top:16px;display:flex;justify-content:flex-end;gap:8px;">
                <button type="button" class="btn-secondary" onclick="window.closeModal()">取消</button>
                <button type="button" class="btn-primary" onclick="window.submitPipelineRollback()">确认回滚</button>
            </div>
        `;
        const footer = document.querySelector('#modalContainer .modal-footer');
        if (footer) {
            footer.style.display = 'none';
        }
    } catch (error) {
        const box = document.getElementById('pipelineRollbackBox');
        if (box) {
            box.innerHTML = `<div style="color:#dc2626;">${escapeHtml(error.message || '加载失败')}</div>`;
        } else {
            alert(error.message || '加载失败');
        }
    }
}
window.showPipelineRollbackModal = showPipelineRollbackModal;

async function submitPipelineRollback() {
    if (!pipelineRollbackContext || !pipelineRollbackContext.jobId) {
        alert('回滚上下文丢失，请重新打开');
        return;
    }
    const selected = document.querySelector('input[name="pipelineRollbackSource"]:checked');
    if (!selected) {
        alert('请选择要回滚到的历史版本');
        return;
    }
    if (pipelineRollbackContext.prodEnv) {
        const confirmed = document.getElementById('pipelineRollbackConfirmProd');
        if (!confirmed || !confirmed.checked) {
            alert('生产环境回滚请勾选确认');
            return;
        }
    }

    const sourceJobId = parseInt(selected.value, 10);
    try {
        const response = await fetch(`/api/pipeline-run/${pipelineRollbackContext.jobId}/rollback`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
                sourceJobId,
                confirmed: true
            })
        });
        let result = {};
        try {
            result = await response.json();
        } catch (e) {
            result = {};
        }
        if (!response.ok) {
            throw new Error(result.message || result.error || response.statusText || '回滚失败');
        }
        if (typeof window.closeModal === 'function') {
            window.closeModal();
        }
        await loadPipelineJobsView();
        pipelineListForcePollingJobId = pipelineRollbackContext.rowId;
        pipelineListForcePollingJobStarted = false;
        pipelineListForcePollingStartAt = Date.now();
        startPipelineListPolling();
    } catch (error) {
        console.error('Rollback pipeline job error:', error);
        alert('回滚失败: ' + error.message);
    }
}
window.submitPipelineRollback = submitPipelineRollback;

async function triggerPipelineBuild(jobId, rowJobId) {
    const rowId = rowJobId != null ? rowJobId : jobId;
    const btn = document.querySelector(`tr[data-job-id="${rowId}"] .btn-success`);
    const previousLabel = btn ? btn.textContent : 'Run';

    if (btn) {
        btn.disabled = true;
        btn.textContent = 'Running...';
    }

    try {
        const response = await fetch(`/api/pipeline-run/${jobId}/build`, {
            method: 'POST'
        });

        let result = {};
        try {
            result = await response.json();
        } catch (e) {
            result = {};
        }

        if (!response.ok) {
            throw new Error(result.message || result.error || response.statusText || '构建失败');
        }

        await loadPipelineJobsView();
        pipelineListForcePollingJobId = rowId;
        pipelineListForcePollingJobStarted = false;
        pipelineListForcePollingStartAt = Date.now();
        startPipelineListPolling();
    } catch (error) {
        console.error('Trigger pipeline build error:', error);
        if (btn) {
            btn.disabled = false;
            btn.textContent = previousLabel;
        }
        alert('构建失败: ' + error.message);
    }
}
window.triggerPipelineBuild = triggerPipelineBuild;

async function deletePipelineTask(jobId) {
    if (!confirm('确定要删除这个流水线任务吗？删除后不可恢复。')) return;

    try {
        const response = await fetch(`/api/pipeline-run/${jobId}`, { method: 'DELETE' });
        if (!response.ok) {
            let errText = await response.text();
            try {
                const errJson = JSON.parse(errText);
                errText = errJson.message || errJson.error || errText;
            } catch (e) { /* ignore */ }
            throw new Error(errText || '删除失败');
        }
        if (currentPipelineJobId === jobId) {
            currentPipelineJobId = null;
            stopPipelinePolling();
        }
        await loadPipelineJobsView();
        alert('删除成功');
    } catch (error) {
        alert('删除失败: ' + error.message);
    }
}
window.deletePipelineTask = deletePipelineTask;

function resolvePipelineTaskServiceName() {
    const serviceSelect = document.getElementById('pipelineTaskServiceSelect');
    if (!serviceSelect || serviceSelect.selectedIndex <= 0) return '';
    const option = serviceSelect.options[serviceSelect.selectedIndex];
    return (option.getAttribute('data-service-name') || option.textContent.split('(')[0] || '').trim();
}

function buildPipelineTaskPayload(formData) {
    const serviceId = parseInt(formData.get('serviceId'), 10);
    const envId = parseInt(formData.get('envId'), 10);
    const pipelineTemplateId = parseInt(formData.get('pipelineTemplateId'), 10);
    const branch = (formData.get('branch') || '').trim();
    const gitType = formData.get('gitType') === 'tag' ? 'tag' : 'branch';
    const taskName = resolvePipelineTaskServiceName();

    if (!taskName || !serviceId || !envId || !pipelineTemplateId || !branch) {
        alert('请填写所有必填项，并点击「加载」选择 Git 分支或 Tag');
        return null;
    }

    return {
        taskName,
        serviceId,
        envId,
        pipelineTemplateId,
        branch,
        gitType,
        autoDeploy: formData.get('autoDeploy') === 'on'
    };
}

async function submitCreatePipelineTask() {
    const form = document.getElementById('createPipelineTaskForm');
    if (!form) return;

    const payload = buildPipelineTaskPayload(new FormData(form));
    if (!payload) return;

    try {
        const response = await fetch('/api/build/start', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(payload)
        });

        let result = {};
        try {
            result = await response.json();
        } catch (e) {
            result = {};
        }

        if (!response.ok) {
            throw new Error(result.message || result.error || response.statusText || '创建失败');
        }

        if (typeof window.closeModal === 'function') {
            window.closeModal();
        }

        await loadPipelineJobsView();

        if (result.jobId) {
            await showPipelineDetail(result.jobId);
        } else {
            alert('Job 已创建：' + (result.taskName || result.jobNumber || ''));
        }
    } catch (error) {
        console.error('Create pipeline task error:', error);
        alert('创建失败: ' + error.message);
    }
}
window.submitCreatePipelineTask = submitCreatePipelineTask;
