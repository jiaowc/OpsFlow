// ============================================
// 服务管理模块
// ============================================

const SERVICE_TYPE_OPTIONS = [
    { value: 'backend', label: '后端' },
    { value: 'frontend', label: '前端' },
    { value: 'lib', label: 'lib' }
];

let serviceGitComponents = [];
let cachedServiceList = [];

function escServiceHtml(text) {
    if (text == null) return '';
    return String(text).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
}

function getServiceTypeLabel(type) {
    const item = SERVICE_TYPE_OPTIONS.find(o => o.value === type);
    return item ? item.label : (type || '-');
}

function renderServiceTypeOptions(selected) {
    return SERVICE_TYPE_OPTIONS.map(opt => {
        const sel = selected === opt.value ? ' selected' : '';
        return `<option value="${opt.value}"${sel}>${opt.label}</option>`;
    }).join('');
}

function getComponentTypeLabel(type) {
    return { gitlab: 'GitLab', github: 'GitHub' }[type] || type || '';
}

async function loadServiceGitComponents() {
    try {
        const response = await fetch('/api/component/list');
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
    const options = ['<option value="">请选择组件</option>'];
    serviceGitComponents.forEach(component => {
        const sel = String(component.id) === String(selectedId) ? ' selected' : '';
        options.push(
            `<option value="${component.id}" data-url="${escServiceHtml(component.url || '')}"${sel}>`
            + `${escServiceHtml(component.name)} (${getComponentTypeLabel(component.type)})`
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
        return { success: false, message: '请选择组件' };
    }
    if (!repoPath) {
        return { success: false, message: '请填写仓库路径' };
    }

    const baseUrl = trimTrailingSlash(componentUrl);
    const path = repoPath.trim();

    if (isAbsoluteRepoPath(path)) {
        const repoHost = extractRepoHost(path);
        const componentHost = extractRepoHost(baseUrl);
        if (!repoHost || !componentHost) {
            return { success: false, message: '无法解析仓库或组件地址中的域名' };
        }
        if (repoHost !== componentHost) {
            return {
                success: false,
                message: `仓库路径域名 (${repoHost}) 与组件地址域名 (${componentHost}) 不一致，请检查`
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
            <label>服务名称 *</label>
            <input type="text" name="name" value="${escServiceHtml(service.name || '')}" required>
        </div>
        <div class="form-item">
            <label>服务类型 *</label>
            <select name="serviceType" required>
                ${renderServiceTypeOptions(service.serviceType || 'backend')}
            </select>
        </div>
        <div class="form-item">
            <label>对外提供服务端口 *</label>
            <input type="number" name="servicePort" id="servicePortInput"
                   value="${service.servicePort != null ? escServiceHtml(service.servicePort) : ''}"
                   min="1" max="65535" placeholder="例如：8080" required>
            <small style="color:#6b7280;">服务对外暴露的 HTTP/TCP 端口</small>
        </div>
        <div class="form-item">
            <label>组件 *</label>
            <select name="componentId" id="serviceComponentSelect" required>
                <option value="">加载中...</option>
            </select>
            <small style="color:#6b7280;">从组件管理中选择 GitLab 或 GitHub 组件</small>
        </div>
        <div class="form-item">
            <label>仓库路径 *</label>
            <input type="text" name="gitRepoPath" id="serviceRepoPathInput"
                   value="${escServiceHtml(repoPathValue)}"
                   placeholder="例如：group/project 或 https://github.com/org/repo.git"
                   required>
            <small style="color:#6b7280;">可填相对路径（与组件地址拼接），或完整地址（域名需与组件一致）</small>
        </div>
    `;
}

async function initServiceGitForm(service) {
    await loadServiceGitComponents();
    const componentSelect = document.getElementById('serviceComponentSelect');
    if (componentSelect) {
        componentSelect.innerHTML = renderServiceComponentOptions(service?.componentId || '');
    }
}

function validateServicePort(port) {
    const value = parseInt(port, 10);
    if (isNaN(value) || value < 1 || value > 65535) {
        return { valid: false, message: '对外提供服务端口须为 1-65535 之间的整数' };
    }
    return { valid: true, value };
}

function collectServiceFormData(form, includeStatus) {
    const formData = new FormData(form);
    const componentId = formData.get('componentId');
    const gitRepoPath = (formData.get('gitRepoPath') || '').trim();
    const portCheck = validateServicePort(formData.get('servicePort'));

    if (!portCheck.valid) {
        alert(portCheck.message);
        return null;
    }

    if (!componentId) {
        alert('请选择组件');
        return null;
    }
    if (!gitRepoPath) {
        alert('请填写仓库路径');
        return null;
    }

    const componentUrl = getSelectedServiceComponentUrl();
    const resolved = resolveServiceGitRepo(componentUrl, gitRepoPath);
    if (!resolved.success) {
        alert(resolved.message);
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
    if (includeStatus) {
        data.status = parseInt(formData.get('status'), 10);
    }
    if (!data.name) {
        alert('服务名称不能为空');
        return null;
    }
    return data;
}

async function loadServices() {
    const serviceList = document.getElementById('serviceList');
    if (!serviceList) return;

    try {
        const response = await fetch('/api/service/list');
        if (response.ok) {
            const services = await response.json();
            if (services && services.length > 0) {
                renderServiceList(services);
                return;
            }
        }
    } catch (error) {
        console.error('Load services error:', error);
    }

    renderServiceList(mockServices);
}
window.loadServices = loadServices;

function renderServiceList(services) {
    const serviceList = document.getElementById('serviceList');
    if (!serviceList) return;

    cachedServiceList = services || [];

    if (services.length === 0) {
        serviceList.innerHTML = '<tr><td colspan="7" style="text-align: center; padding: 40px; color: #999;">暂无服务</td></tr>';
        return;
    }

    serviceList.innerHTML = services.map(service => `
        <tr data-service-id="${service.id}">
            <td>${escServiceHtml(service.name || '-')}</td>
            <td>${escServiceHtml(getServiceTypeLabel(service.serviceType))}</td>
            <td>${escServiceHtml(service.componentName || '-')}</td>
            <td>${service.servicePort != null ? escServiceHtml(service.servicePort) : '-'}</td>
            <td>${escServiceHtml(service.gitRepo || '-')}</td>
            <td>${service.status === 1 ? '<span style="color: #059669;">启用</span>' : '<span style="color: #dc2626;">禁用</span>'}</td>
            <td>
                <div class="table-actions">
                    <button class="btn-edit service-edit-btn" data-service-id="${service.id}">修改</button>
                    <button class="btn-danger service-delete-btn" data-service-id="${service.id}">删除</button>
                </div>
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

    showModal('添加服务', content, async () => {
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
                alert('创建成功');
                loadServices();
                loadServicesForSelect();
                closeModal();
            } else {
                const errText = await response.text();
                alert('创建失败: ' + errText);
            }
        } catch (error) {
            alert('创建失败: ' + error.message);
        }
    });

    setTimeout(() => initServiceGitForm(), 50);
}
window.showCreateServiceModal = showCreateServiceModal;

async function editService(id) {
    try {
        const response = await fetch(`/api/service/${id}`);
        if (!response.ok) {
            throw new Error('获取服务详情失败');
        }

        const service = await response.json();
        if (!service) {
            throw new Error('服务不存在');
        }

        const content = `
            <form id="editServiceForm">
                ${buildServiceFormFields(service)}
                <div class="form-item">
                    <label>状态</label>
                    <select name="status">
                        <option value="1" ${service.status === 1 ? 'selected' : ''}>启用</option>
                        <option value="0" ${service.status === 0 ? 'selected' : ''}>禁用</option>
                    </select>
                </div>
            </form>
        `;

        showModal('编辑服务', content, async () => {
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
                    throw new Error(errorText || '修改失败');
                }

                alert('修改成功');
                await loadServices();
                if (typeof loadServicesForSelect === 'function') {
                    await loadServicesForSelect();
                }
                closeModal();
            } catch (error) {
                alert('修改失败: ' + error.message);
            }
        });

        setTimeout(() => initServiceGitForm(service), 50);
    } catch (error) {
        alert('加载服务失败: ' + error.message);
    }
}
window.editService = editService;

async function deleteService(id) {
    if (!confirm('确定要删除这个服务吗？')) return;
    try {
        const response = await fetch(`/api/service/${id}`, { method: 'DELETE' });
        if (response.ok) {
            alert('删除成功');
            loadServices();
            loadServicesForSelect();
        }
    } catch (error) {
        alert('删除失败');
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
