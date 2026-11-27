// ============================================
// 服务管理模块
// ============================================

const SERVICE_TYPE_OPTIONS = [
    // label 用 getter：随当前语言变化，不在加载时固定
    { value: 'backend', get label() { return t('services.type.backend'); } },
    { value: 'frontend', get label() { return t('services.type.frontend'); } },
    { value: 'lib', label: 'lib' }
];

let serviceGitComponents = [];
let cachedServiceList = [];

function getServiceTypeLabel(type) {
    const item = SERVICE_TYPE_OPTIONS.find(o => o.value === type);
    return item ? item.label : (type || '-');
}

function renderServiceTypeOptions(selected) {
    return SERVICE_TYPE_OPTIONS.map(opt => {
        const sel = selected === opt.value ? ' selected' : '';
        return `<option value="${escapeHtml(opt.value)}"${sel}>${escapeHtml(opt.label)}</option>`;
    }).join('');
}

function getComponentTypeLabel(type) {
    return { gitlab: 'GitLab', github: 'GitHub' }[type] || type || '';
}

async function loadServiceGitComponents() {
    try {
        const response = await fetch('/api/component/options');
        if (response.ok) {
            const components = await response.json();
            serviceGitComponents = (components || []).filter(c =>
                c.status === 1 && (c.type === 'gitlab' || c.type === 'github')
            );
        }
    } catch (error) {
        console.error('Load git components error:', error);
        serviceGitComponents = [];
    }
    return serviceGitComponents;
}

function renderServiceComponentOptions(selectedId) {
    const options = [('<option value="">' + t('services.form.selectComponent') + '</option>')];
    serviceGitComponents.forEach(component => {
        const sel = String(component.id) === String(selectedId) ? ' selected' : '';
        options.push(
            `<option value="${component.id}" data-url="${escapeHtml(component.url || '')}"${sel}>`
            + `${escapeHtml(component.name)} (${getComponentTypeLabel(component.type)})`
            + `</option>`
        );
    });
    return options.join('');
}

function isAbsoluteRepoPath(path) {
    const lower = (path || '').toLowerCase();
    return lower.startsWith('http://') || lower.startsWith('https://') || (path || '').startsWith('git@');
}

function extractRepoHost(value) {
    if (!value) return '';
    value = value.trim();
    if (value.startsWith('git@')) {
        const colon = value.indexOf(':');
        if (colon > 4) return value.substring(4, colon).toLowerCase();
        return '';
    }
    try {
        const url = new URL(value);
        return (url.hostname || '').toLowerCase().replace(/^www\./, '');
    } catch (e) {
        return '';
    }
}

function trimTrailingSlash(url) {
    let result = (url || '').trim();
    while (result.endsWith('/')) {
        result = result.slice(0, -1);
    }
    return result;
}

function resolveServiceGitRepo(componentUrl, repoPath) {
    if (!componentUrl) {
        return { success: false, message: t('services.form.selectComponent') };
    }
    if (!repoPath) {
        return { success: false, message: t('services.msg.repoRequired') };
    }

    const baseUrl = trimTrailingSlash(componentUrl);
    const path = repoPath.trim();

    if (isAbsoluteRepoPath(path)) {
        const repoHost = extractRepoHost(path);
        const componentHost = extractRepoHost(baseUrl);
        if (!repoHost || !componentHost) {
            return { success: false, message: t('services.msg.hostParseFailed') };
        }
        if (repoHost !== componentHost) {
            return {
                success: false,
                message: t('services.msg.hostMismatch', { repoHost: repoHost, componentHost: componentHost })
            };
        }
        return { success: true, gitRepo: path };
    }

    const relative = path.startsWith('/') ? path.slice(1) : path;
    return { success: true, gitRepo: `${baseUrl}/${relative}` };
}

function getSelectedServiceComponentUrl() {
    const select = document.getElementById('serviceComponentSelect');
    if (!select || !select.value) return '';
    const option = select.options[select.selectedIndex];
    return option ? (option.getAttribute('data-url') || '') : '';
}

function buildServiceFormFields(service) {
    service = service || {};
    const repoPathValue = service.gitRepoPath || service.gitRepo || '';
    return `
        <div class="form-item">
            <label>${t('services.form.project')}${REQUIRED_MARK}</label>
            <select name="projectId" id="serviceProjectSelect" required>
                <option value="">${t('common.loading')}</option>
            </select>
            <small style="color:#6b7280;">${t('services.form.projectHint')}</small>
        </div>
        <div class="form-item">
            <label>${t('services.form.name')}${REQUIRED_MARK}</label>
            <input type="text" name="name" value="${escapeHtml(service.name || '')}" required>
        </div>
        <div class="form-item">
            <label>${t('services.form.type')}${REQUIRED_MARK}</label>
            <select name="serviceType" required>
                ${renderServiceTypeOptions(service.serviceType || 'backend')}
            </select>
        </div>
        <div class="form-item">
            <label>${t('services.form.port')}${REQUIRED_MARK}</label>
            <input type="number" name="servicePort" id="servicePortInput"
                   value="${service.servicePort != null ? escapeHtml(service.servicePort) : ''}"
                   min="1" max="65535" placeholder="${t('services.form.portPlaceholder')}" required>
            <small style="color:#6b7280;">${t('services.form.portHint')}</small>
        </div>
        <div class="form-item">
            <label>${t('services.form.component')}${REQUIRED_MARK}</label>
            <select name="componentId" id="serviceComponentSelect" required>
                <option value="">${t('common.loading')}</option>
            </select>
            <small style="color:#6b7280;">${t('services.form.componentHint')}</small>
        </div>
        <div class="form-item">
            <label>${t('services.form.repo')}${REQUIRED_MARK}</label>
            <input type="text" name="gitRepoPath" id="serviceRepoPathInput"
                   value="${escapeHtml(repoPathValue)}"
                   placeholder="${t('services.form.repoPlaceholder')}"
                   required>
            <small style="color:#6b7280;">${t('services.form.repoHint')}</small>
        </div>
        <div class="form-item">
            <label>${t('services.form.owner')}</label>
            <select name="ownerUserId" id="serviceOwnerSelect"
                    data-original="${service.ownerUserId != null ? escapeHtml(service.ownerUserId) : ''}">
                <option value="">${t('common.loading')}</option>
            </select>
            <small style="color:#6b7280;">${t('services.form.ownerHint')}</small>
        </div>
    `;
}

async function initServiceGitForm(service) {
    await Promise.all([loadServiceGitComponents(), initServiceProjectField(service)]);
    const componentSelect = document.getElementById('serviceComponentSelect');
    if (componentSelect) {
        componentSelect.innerHTML = renderServiceComponentOptions(service?.componentId || '');
    }
}

async function initServiceProjectField(service) {
    const projectSelect = document.getElementById('serviceProjectSelect');
    if (!projectSelect) return;
    const projects = typeof loadProjectOptions === 'function' ? await loadProjectOptions() : [];
    const selected = service?.projectId != null
        ? String(service.projectId)
        : getServiceProjectFilterId();
    const writable = projects.filter(p => p.myRole && p.myRole !== 'VIEWER');
    if (service?.projectId != null && !writable.some(p => String(p.id) === selected)) {
        writable.unshift({ id: service.projectId, name: service.projectName || (t('services.form.projectFallback') + '#' + service.projectId) });
    }
    projectSelect.innerHTML = ('<option value="">' + t('services.form.selectProject') + '</option>') + writable.map(p =>
        `<option value="${p.id}"${String(p.id) === selected ? ' selected' : ''}>${escapeHtml(p.name)}</option>`
    ).join('');
    projectSelect.addEventListener('change', () => loadServiceOwnerOptions(projectSelect.value, service));
    await loadServiceOwnerOptions(projectSelect.value, service);
}

async function loadServiceOwnerOptions(projectId, service) {
    const ownerSelect = document.getElementById('serviceOwnerSelect');
    if (!ownerSelect) return;
    const originalId = ownerSelect.dataset.original || '';
    const currentId = ownerSelect.dataset.loaded === '1' ? ownerSelect.value : originalId;
    if (!projectId) {
        ownerSelect.innerHTML = ('<option value="">' + t('services.form.selectProjectFirst') + '</option>');
        ownerSelect.dataset.loaded = '';
        return;
    }
    let members = null;
    try {
        const resp = await fetch(`/api/project/${projectId}/members`);
        if (resp.ok) members = await resp.json();
    } catch (e) {
        members = null;
    }
    if (!Array.isArray(members)) {
        ownerSelect.innerHTML = `<option value="${escapeHtml(originalId)}">${escapeHtml(service?.ownerName || t('services.form.membersFailed'))}</option>`;
        ownerSelect.dataset.loaded = '';
        return;
    }
    const options = members.map(m => ({ id: String(m.userId), name: m.name }));
    if (currentId && !options.some(o => o.id === currentId) && String(service?.ownerUserId || '') === currentId) {
        options.unshift({ id: currentId, name: (service.ownerName || t('services.form.userFallback') + '#' + currentId) + t('services.form.notMember') });
    }
    ownerSelect.innerHTML = ('<option value="">' + t('common.notSet') + '</option>') + options.map(o =>
        `<option value="${o.id}"${o.id === currentId ? ' selected' : ''}>${escapeHtml(o.name)}</option>`
    ).join('');
    ownerSelect.dataset.loaded = '1';
}

function validateServicePort(port) {
    const value = parseInt(port, 10);
    if (isNaN(value) || value < 1 || value > 65535) {
        return { valid: false, message: t('services.msg.portInvalid') };
    }
    return { valid: true, value };
}

function collectServiceFormData(form, includeStatus) {
    const formData = new FormData(form);
    const componentId = formData.get('componentId');
    const gitRepoPath = (formData.get('gitRepoPath') || '').trim();
    const portCheck = validateServicePort(formData.get('servicePort'));

    if (!portCheck.valid) {
        notify(portCheck.message);
        return null;
    }

    if (!componentId) {
        notify(t('services.form.selectComponent'));
        return null;
    }
    if (!gitRepoPath) {
        notify(t('services.msg.repoRequired'));
        return null;
    }

    const componentUrl = getSelectedServiceComponentUrl();
    const resolved = resolveServiceGitRepo(componentUrl, gitRepoPath);
    if (!resolved.success) {
        notify(resolved.message);
        return null;
    }

    const data = {
        name: (formData.get('name') || '').trim(),
        serviceType: formData.get('serviceType') || 'backend',
        servicePort: portCheck.value,
        componentId: parseInt(componentId, 10),
        gitRepoPath,
        gitRepo: resolved.gitRepo
    };
    const projectId = formData.get('projectId');
    if (!projectId) {
        notify(t('services.msg.projectRequired'));
        return null;
    }
    data.projectId = parseInt(projectId, 10);
    // 后端以请求中的负责人为准；成员列表未加载成功时回传原值，避免误清空
    const ownerSelect = form.querySelector('#serviceOwnerSelect');
    const ownerUserId = ownerSelect?.dataset.loaded === '1' ? formData.get('ownerUserId') : (ownerSelect?.dataset.original || '');
    data.ownerUserId = ownerUserId ? parseInt(ownerUserId, 10) : null;
    if (includeStatus) {
        data.status = parseInt(formData.get('status'), 10);
    }
    if (!data.name) {
        notify(t('services.msg.nameRequired'));
        return null;
    }
    return data;
}

function getServiceProjectFilterId() {
    return ((document.getElementById('serviceProjectFilter') || {}).value || '').trim();
}

function onServiceProjectFilterChange() {
    loadServices();
}
window.onServiceProjectFilterChange = onServiceProjectFilterChange;

async function loadServices() {
    const serviceList = document.getElementById('serviceList');
    if (!serviceList) return;

    try {
        const filterSelect = document.getElementById('serviceProjectFilter');
        if (filterSelect && filterSelect.options.length <= 1 && typeof refreshServiceProjectFilter === 'function') {
            await refreshServiceProjectFilter();
        }
        const projectId = getServiceProjectFilterId();
        const response = await fetch('/api/service/list' + (projectId ? `?projectId=${encodeURIComponent(projectId)}` : ''));
        if (response.ok) {
            cachedServiceList = (await response.json()) || [];
            applyServiceFilters();
            return;
        }
        let message = t('common.loadFailed');
        try {
            message = (await response.json()).message || message;
        } catch (e) {
            // 保留默认提示
        }
        cachedServiceList = [];
        serviceList.innerHTML = `<tr><td colspan="9" style="text-align: center; padding: 40px; color: #dc2626;">${escapeHtml(message)}</td></tr>`;
    } catch (error) {
        console.error('Load services error:', error);
        cachedServiceList = [];
        serviceList.innerHTML = ('<tr><td colspan="9" style="text-align: center; padding: 40px; color: #dc2626;">' + t('common.loadFailed') + '</td></tr>');
    }
}
window.loadServices = loadServices;

function applyServiceFilters() {
    const keyword = ((document.getElementById('serviceSearchInput') || {}).value || '').trim().toLowerCase();
    const source = cachedServiceList || [];
    if (!keyword) {
        paintServiceTable(source);
        return;
    }
    const filtered = source.filter(service => {
        const haystack = [
            service.projectName,
            service.name,
            getServiceTypeLabel(service.serviceType),
            service.serviceType,
            service.componentName,
            service.servicePort,
            service.gitRepo,
            service.ownerName,
            service.status === 1 ? t('common.enabled') : t('common.disabled')
        ].map(v => String(v == null ? '' : v).toLowerCase()).join(' ');
        return haystack.indexOf(keyword) >= 0;
    });
    paintServiceTable(filtered, true);
}
window.applyServiceFilters = applyServiceFilters;

function renderServiceList(services) {
    cachedServiceList = services || [];
    applyServiceFilters();
}

/** 列表里只显示「组/仓库名」（去掉协议、主机和 .git），完整地址放在单元格的 title 里 */
function shortGitRepo(url) {
    const raw = String(url || '').trim();
    if (!raw) return '';
    // git@host:group/repo.git 或 http(s)://host/group/repo.git
    const m = raw.match(/^[^@\s]+@[^:]+:(.+)$/) || raw.match(/^[a-z][a-z0-9+.-]*:\/\/[^/]+\/(.+)$/i);
    const path = (m ? m[1] : raw).replace(/\/+$/, '').replace(/\.git$/i, '');
    return path || raw;
}

function paintServiceTable(services, fromFilter) {
    const serviceList = document.getElementById('serviceList');
    if (!serviceList) return;

    if (!services || services.length === 0) {
        const emptyText = fromFilter ? t('services.emptyFiltered') : t('services.empty');
        serviceList.innerHTML = `<tr><td colspan="9" style="text-align: center; padding: 40px; color: var(--text-4);">${emptyText}</td></tr>`;
        return;
    }

    serviceList.innerHTML = services.map(service => `
        <tr data-service-id="${service.id}">
            <td>${escapeHtml(service.projectName || '-')}</td>
            <td><span class="cell-ellipsis" title="${escapeHtml(service.name || '')}">${escapeHtml(service.name || '-')}</span></td>
            <td>${escapeHtml(getServiceTypeLabel(service.serviceType))}</td>
            <td>${escapeHtml(service.componentName || '-')}</td>
            <td>${service.servicePort != null ? escapeHtml(service.servicePort) : '-'}</td>
            <td title="${escapeHtml(service.gitRepo || '')}">${escapeHtml(shortGitRepo(service.gitRepo) || '-')}</td>
            <td>${escapeHtml(service.ownerName || '-')}</td>
            <td>${service.status === 1 ? ('<span class="status-tag is-on">' + t('common.enabled') + '</span>') : ('<span class="status-tag is-off">' + t('common.disabled') + '</span>')}</td>
            <td>
                ${hasPermission('service:manage') ? `<div class="table-actions">
                    <button class="btn-edit service-edit-btn" data-service-id="${service.id}">${t('common.modify')}</button>
                    <button class="btn-danger service-delete-btn" data-service-id="${service.id}">${t('common.delete')}</button>
                </div>` : '<span style="color:#9ca3af;">-</span>'}
            </td>
        </tr>
    `).join('');
}

function showCreateServiceModal() {
    const content = `
        <form id="createServiceForm">
            ${buildServiceFormFields()}
        </form>
    `;

    showModal(t('services.form.createTitle'), content, async () => {
        const form = document.getElementById('createServiceForm');
        const data = collectServiceFormData(form, false);
        if (!data) return;

        try {
            const response = await fetch('/api/service/create', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(data)
            });

            if (response.ok) {
                notify(t('common.createOk'));
                loadServices();
                loadServicesForSelect();
                closeModal();
            } else {
                const errText = await response.text();
                notify((t('common.createFailed') + ': ') + errText);
            }
        } catch (error) {
            notify((t('common.createFailed') + ': ') + error.message);
        }
    });

    setTimeout(() => initServiceGitForm(), 50);
}
window.showCreateServiceModal = showCreateServiceModal;

async function editService(id) {
    try {
        const response = await fetch(`/api/service/${id}`);
        if (!response.ok) {
            throw new Error(t('services.msg.detailFailed'));
        }

        const service = await response.json();
        if (!service) {
            throw new Error(t('services.msg.notFound'));
        }

        const content = `
            <form id="editServiceForm">
                ${buildServiceFormFields(service)}
                <div class="form-item">
                    <label>${t('common.status')}</label>
                    <select name="status">
                        <option value="1" ${service.status === 1 ? 'selected' : ''}>${t('common.enabled')}</option>
                        <option value="0" ${service.status === 0 ? 'selected' : ''}>${t('common.disabled')}</option>
                    </select>
                </div>
            </form>
        `;

        showModal(t('services.form.editTitle'), content, async () => {
            const form = document.getElementById('editServiceForm');
            const data = collectServiceFormData(form, true);
            if (!data) return;

            try {
                const updateResp = await fetch(`/api/service/${id}`, {
                    method: 'PUT',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify(data)
                });

                if (!updateResp.ok) {
                    const errorText = await updateResp.text();
                    throw new Error(errorText || t('common.updateFailed'));
                }

                notify(t('common.updateOk'));
                await loadServices();
                if (typeof loadServicesForSelect === 'function') {
                    await loadServicesForSelect();
                }
                closeModal();
            } catch (error) {
                notify((t('common.updateFailed') + ': ') + error.message);
            }
        });

        setTimeout(() => initServiceGitForm(service), 50);
    } catch (error) {
        notify((t('services.msg.loadFailed') + ': ') + error.message);
    }
}
window.editService = editService;

async function deleteService(id) {
    if (!await confirmDialog(t('services.msg.deleteConfirm'))) return;
    try {
        const response = await fetch(`/api/service/${id}`, { method: 'DELETE' });
        if (response.ok) {
            notify(t('common.deleteOk'));
            loadServices();
            loadServicesForSelect();
        }
    } catch (error) {
        notify(t('common.deleteFailed'));
    }
}
window.deleteService = deleteService;

function bindServiceActions() {
    const serviceSection = document.getElementById('services-section');
    if (!serviceSection || serviceSection.dataset.bound === 'true') {
        return;
    }

    serviceSection.addEventListener('click', function (event) {
        const editBtn = event.target.closest('.service-edit-btn');
        if (editBtn) {
            event.preventDefault();
            event.stopPropagation();
            const id = parseInt(editBtn.getAttribute('data-service-id'), 10);
            if (!isNaN(id)) {
                window.editService(id);
            }
            return;
        }

        const deleteBtn = event.target.closest('.service-delete-btn');
        if (deleteBtn) {
            event.preventDefault();
            event.stopPropagation();
            const id = parseInt(deleteBtn.getAttribute('data-service-id'), 10);
            if (!isNaN(id)) {
                window.deleteService(id);
            }
        }
    });
    serviceSection.dataset.bound = 'true';
}
window.bindServiceActions = bindServiceActions;

if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', bindServiceActions);
} else {
    bindServiceActions();
}
