// ============================================
// 平台原生 Pipeline 视图模块
// ============================================

let currentPipelineJobId = null;
let pipelinePollTimer = null;
let pipelineStageViewPage = 1;

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

function renderPipelineGitRefCell(job) {
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
    const response = await fetch(`/api/pipeline-run/jobs?page=${page}&pageSize=${pageSize}`);
    if (!response.ok) {
        throw new Error(await response.text() || response.statusText);
    }
    return response.json();
}

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

    const isDetail = mode === 'detail';
    if (title) title.textContent = isDetail ? '阶段视图' : '流水线视图';
    if (createBtn) createBtn.style.display = isDetail ? 'none' : '';
    if (headerRefreshBtn) headerRefreshBtn.style.display = isDetail ? 'none' : '';
    if (detailRefreshBtn) detailRefreshBtn.style.display = isDetail ? '' : 'none';
    if (backToListBtn) backToListBtn.style.display = isDetail ? '' : 'none';
}

async function loadPipelineJobsView() {
    currentPipelineJobId = null;
    stopPipelinePolling();
    stopPipelineListPolling();
    setPipelineSectionMode('list');
    await renderPipelineListPage();
}
window.loadPipelineJobsView = loadPipelineJobsView;

async function renderPipelineListPage() {
    const container = document.getElementById('pipelineJobsView');
    if (!container) return;

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

function renderMiniStagesForJob(job) {
    const stages = job.latestStages || [];
    const buildId = job.latestBuildId || job.id;
    if (!stages.length) {
        return `<span style="font-size:12px;color:#9ca3af;">暂无构建记录</span>`;
    }
    const stageNameEsc = (name) => (name || '阶段').replace(/'/g, "\\'");
    return `
        <div style="display:flex;flex-wrap:nowrap;gap:4px;align-items:center;white-space:nowrap;overflow:hidden;">
            <span style="font-size:11px;color:#6b7280;white-space:nowrap;margin-right:2px;"
                  title="构建 ${escapeHtml(job.jobNumber || buildId)} · ${escapeHtml(job.lastDurationText || '-')}">
                ${getStatusIcon(job.status, job.building)}
            </span>
            ${stages.map(stage => `
                <div title="${escapeHtml(stage.name || '')} · ${escapeHtml(stage.durationText || '-')}"
                     onclick="event.stopPropagation();window.showPipelineStageLog(${buildId}, '${stage.id}', '${stageNameEsc(stage.name)}')"
                     style="flex:0 0 auto;min-width:44px;max-width:64px;padding:3px 4px;border-radius:4px;font-size:10px;text-align:center;
                            background:${getStageBg(stage.status)};border:1px solid #e5e7eb;cursor:pointer;">
                    <div style="font-weight:600;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;">
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

    if (!jobs.length) {
        container.innerHTML = `
            <div style="padding: 40px; text-align: center; color: #999;">
                <p>暂无 Job</p>
                <p style="font-size: 13px; margin-top: 8px;">点击右上角按钮创建第一个 Job</p>
            </div>
        `;
        return;
    }

    container.innerHTML = `
        <div class="pipeline-jobs-table">
            <div style="font-size: 13px; color: #6b7280; margin-bottom: 10px;">任务列表（点击服务名称进入阶段视图）</div>
            <table class="pipeline-table">
                <thead>
                    <tr>
                        <th style="width:84px;">部署环境</th>
                        <th style="width:180px;">服务名称</th>
                        <th style="width:260px;">仓库地址</th>
                        <th style="width:130px;">tag/branch</th>
                        <th>最近构建</th>
                        <th style="width:96px;">构建用户</th>
                        <th style="width: 140px;">操作</th>
                    </tr>
                </thead>
                <tbody>
                    ${jobs.map(job => `
                        <tr data-job-id="${job.id}">
                            <td class="pipeline-col-env">${escapeHtml(job.envName || '-')}</td>
                            <td class="pipeline-col-service">
                                <a href="javascript:void(0)" onclick="window.showPipelineDetail(${job.id})"
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
                                    <button class="btn-success" onclick="window.triggerPipelineBuild(${job.id})" ${job.building ? 'disabled' : ''}>Run</button>
                                    <button class="btn-edit" onclick="window.showEditPipelineTaskModal(${job.id})" ${job.editable === false ? 'disabled' : ''}>编辑</button>
                                    <button class="btn-danger" onclick="window.deletePipelineTask(${job.id})" ${job.editable === false ? 'disabled' : ''}>删除</button>
                                </div>
                            </td>
                        </tr>
                    `).join('')}
                </tbody>
            </table>
        </div>
    `;

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

let pipelineListPollTimer = null;
let pipelineListForcePollingJobId = null;
let pipelineListForcePollingJobStarted = false;
let pipelineListForcePollingStartAt = 0;
const PIPELINE_LIST_FORCE_POLLING_MAX_MS = 2 * 60 * 1000;

function startPipelineListPolling() {
    stopPipelineListPolling();
    const intervalMs = pipelineListForcePollingJobId ? 1000 : 3000;
    const tick = async () => {
        if (currentPipelineJobId) return;
        const container = document.getElementById('pipelineJobsView');
        if (!container) {
            stopPipelineListPolling();
            return;
        }
        try {
            const data = await fetchPipelineJobsPage(1, 10);
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
            renderPipelineJobsTable(data, container);
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
    stopPipelineListPolling();
    if (isNewTask) {
        pipelineStageViewPage = 1;
    }
    const container = document.getElementById('pipelineJobsView');
    if (!container) return;

    setPipelineSectionMode('detail');

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

async function loadPipelineStageView(jobId, page) {
    const box = document.getElementById('pipelineStageViewContainer');
    if (!box) return;

    const pageSize = getPipelinePageSize();
    pipelineStageViewPage = page || 1;

    try {
        const view = await fetchPipelineStageView(jobId, pipelineStageViewPage, pageSize);
        currentPipelineJobId = jobId;
        box.innerHTML = renderPipelineStageViewHtml(view);
        return view;
    } catch (error) {
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
            ? `<div style="display: flex; flex-wrap: wrap; gap: 10px;">${stages.map(stage => renderStageCard(build.jobId, stage)).join('')}</div>`
            : '<div style="padding: 12px; color: #999; font-size: 13px;">暂无阶段信息</div>';

        return `
            <div data-job-id="${build.jobId}"
                 style="margin-bottom: 14px; padding: 14px; border: 1px solid ${isHighlight ? '#93c5fd' : '#e5e7eb'};
                        border-radius: 6px; background: ${isHighlight ? '#f8fbff' : '#fff'};
                        box-shadow: 0 1px 2px rgba(0,0,0,0.04);">
                <div style="display: flex; align-items: center; flex-wrap: wrap; gap: 12px; margin-bottom: 12px;
                           padding-bottom: 10px; border-bottom: 1px solid #f3f4f6;">
                    <div style="font-weight: 600; color: #2563eb; font-size: 14px; cursor: pointer;"
                         onclick="window.focusPipelineBuild(${build.jobId})">
                        #${escapeHtml(build.jobNumber || build.jobId)}
                        ${isHighlight ? '<span style="font-size:11px;color:#2563eb;margin-left:6px;">当前</span>' : ''}
                    </div>
                    <div style="color: #6b7280; font-size: 13px;">${escapeHtml(build.buildTimeText || '-')}</div>
                    <div style="color: #6b7280; font-size: 13px;">${escapeHtml(build.totalDurationText || '-')}</div>
                    <div style="margin-left:20px; flex:1; min-width:320px; color:#9ca3af; font-size:12px; white-space:nowrap; overflow:hidden; text-overflow:ellipsis;"
                         title="CI 节点: ${escapeHtml(build.buildNodeDisplay || '本机')} / CD 节点: ${escapeHtml(build.deployNodeDisplay || '本机')}">
                        CI 节点: ${escapeHtml(build.buildNodeDisplay || '本机')} / CD 节点: ${escapeHtml(build.deployNodeDisplay || '本机')}
                    </div>
                    <div>${getStatusIcon(build.status, build.building)}</div>
                </div>
                ${stagesHtml}
            </div>
        `;
    }).join('') : '<div style="padding: 40px; text-align: center; color: #999;">暂无构建历史</div>';

    return `
        <div class="pipeline-view">
            <div style="margin-bottom: 16px;">
                <div style="color: #6b7280; font-size: 14px;">
                    ${escapeHtml(view.taskName || view.jobName || '流水线任务')}
                    · ${escapeHtml(view.serviceName || '-')} / ${escapeHtml(view.envName || '-')} / ${escapeHtml(view.branch || '-')}
                    · ${escapeHtml(view.pipelineTemplateName || '-')}
                </div>
                <div style="margin-top: 4px; color: #9ca3af; font-size: 12px;">
                    CI 节点: ${escapeHtml(view.buildNodeDisplay || '本机')} / CD 节点: ${escapeHtml(view.deployNodeDisplay || '本机')}
                </div>
                <div style="margin-top: 6px;">${getStatusIcon(view.currentStatus, false)} <span style="font-size:13px;color:#6b7280;">${escapeHtml(view.currentStatus || '-')}</span></div>
            </div>

            ${averageStagesHtml}

            <div style="margin-top: 20px;">
                <div style="display:flex;justify-content:space-between;align-items:center;margin-bottom:12px;flex-wrap:wrap;gap:8px;">
                    <h4 style="margin:0;">构建历史</h4>
                    ${renderPageSizeSelect(pageSize, 'window.changePipelineStageViewPageSize')}
                </div>
                <div style="background: #f9fafb; padding: 16px; border-radius: 6px; border: 1px solid #e5e7eb;">
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
    const stageName = (stage.name || '阶段').replace(/'/g, "\\'");
    return `
        <div onclick="window.showPipelineStageLog(${jobId}, '${stage.id}', '${stageName}')"
             style="min-width: 140px; padding: 12px 14px; border-radius: 8px; background: ${bg};
                    border: 1px solid #e5e7eb; cursor: pointer;">
            <div style="font-weight: 600; font-size: 13px;">${escapeHtml(stage.name || '阶段')}</div>
            <div style="font-size: 12px; color: #6b7280; margin-top: 4px;">${escapeHtml(stage.durationText || '-')}</div>
            <div style="margin-top: 6px;">${getStageStatusIcon(stage.status)}</div>
        </div>
    `;
}

async function showPipelineStageLog(jobId, stageId, stageName) {
    if (typeof window.showModal !== 'function') {
        alert('模态框未加载');
        return;
    }

    window.showModal(`阶段日志 - ${stageName}`, '<div id="pipelineStageLogBox" style="padding:20px;color:#999;">加载中...</div>', null, 'large');

    try {
        const response = await fetch(`/api/pipeline-run/${jobId}/stage/${stageId}/log`);
        if (!response.ok) {
            throw new Error(await response.text() || response.statusText);
        }
        const data = await response.json();
        const box = document.getElementById('pipelineStageLogBox');
        if (box) {
            box.innerHTML = `<pre style="margin:0; white-space:pre-wrap; background:#1e1e1e; color:#d4d4d4; padding:16px; border-radius:4px; max-height:60vh; overflow:auto;">${escapeHtml(data.log || '暂无日志')}</pre>`;
        }
    } catch (error) {
        const box = document.getElementById('pipelineStageLogBox');
        if (box) {
            box.innerHTML = `<div style="color:#dc2626;">加载失败: ${escapeHtml(error.message)}</div>`;
        }
    }
}
window.showPipelineStageLog = showPipelineStageLog;

function startPipelinePolling() {
    stopPipelinePolling();
    // 先立即刷一次，再按 1s 轮询，保证步骤状态接近实时
    const tick = async () => {
        if (!currentPipelineJobId) return;
        try {
            const view = await loadPipelineStageView(currentPipelineJobId, pipelineStageViewPage);
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

    const enabledPipelines = (pipelines || []).filter(p => p.status === 1 || p.status == null);
    if (!enabledPipelines.length) {
        alert('暂无可用 Pipeline 模版，请先在「系统设置 → 流水线管理」中配置');
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

async function triggerPipelineBuild(jobId) {
    // 立刻禁用按钮，给用户即时反馈
    const btn = document.querySelector(`tr[data-job-id="${jobId}"] .btn-success`);
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

        // 保持在当前列表页，并立即刷新“最近构建”步骤
        await loadPipelineJobsView();
        pipelineListForcePollingJobId = jobId;
        pipelineListForcePollingJobStarted = false;
        pipelineListForcePollingStartAt = Date.now();
        startPipelineListPolling();
    } catch (error) {
        console.error('Trigger pipeline build error:', error);
        if (btn) {
            btn.disabled = false;
            btn.textContent = 'Run';
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
