// ============================================
// 环境配置模块
// ============================================

function formatEnvType(envType, envName) {
    const normalized = String(envType || '').trim().toLowerCase();
    if (normalized === 'prod' || (!normalized && String(envName || '').trim().toLowerCase() === 'prod')) {
        return ('<span style="color:#dc2626;font-weight:600;">' + t('environments.type.prod') + '</span>');
    }
    return ('<span style="color:#2563eb;font-weight:600;">' + t('environments.type.nonprod') + '</span>');
}

// 加载环境列表（表格）
async function loadEnvironments() {
    const envList = document.getElementById('envList');
    if (!envList) return;
    
    try {
        const response = await fetch('/api/env/list');
        if (response.ok) {
            const envs = await response.json();
            if (envs && envs.length > 0) {
                renderEnvList(envs);
                return;
            }
        }
    } catch (error) {
        console.error('Load environments error:', error);
    }
    
    renderEnvList(mockEnvs);
}
window.loadEnvironments = loadEnvironments;

function renderEnvList(envs) {
    const envList = document.getElementById('envList');
    if (!envList) return;
    const emptyCell = '<span style="color:#9ca3af;">-</span>';
    
    if (envs.length === 0) {
        envList.innerHTML = ('<tr><td colspan="6" style="text-align: center; padding: 40px; color: var(--text-4);">' + t('environments.empty') + '</td></tr>');
        return;
    }
    
    envList.innerHTML = envs.map(env => `
        <tr data-env-id="${env.id}">
            <td><strong>${escapeHtml(env.name || '-')}</strong></td>
            <td>
                ${env.k8sCluster ? `<div style="font-weight:600;color:#111827;">${escapeHtml(env.k8sCluster)}</div>` : emptyCell}
                ${env.clusterServer ? `<div style="margin-top:4px;font-size:12px;color:#9ca3af;" title="${escapeHtml(env.clusterServer)}">${escapeHtml(env.clusterServer)}</div>` : ''}
            </td>
            <td>${env.k8sNamespace ? escapeHtml(env.k8sNamespace) : emptyCell}</td>
            <td style="white-space:nowrap;">${formatEnvType(env.envType, env.name)}</td>
            <td>${env.status === 1 ? ('<span class="status-tag is-on">' + t('common.enabled') + '</span>') : ('<span class="status-tag is-off">' + t('common.disabled') + '</span>')}</td>
            <td>
                ${hasPermission('env:manage') ? `<div class="table-actions">
                    <button class="btn-edit env-edit-btn" data-env-id="${env.id}">${t('common.modify')}</button>
                    <button class="btn-danger env-delete-btn" data-env-id="${env.id}">${t('common.delete')}</button>
                </div>` : '<span style="color:#9ca3af;">-</span>'}
            </td>
        </tr>
    `).join('');
}

// 显示添加环境模态框
async function showCreateEnvModal() {
    const content = `
        <form id="createEnvForm">
            <div class="form-item">
                <label>${t('environments.form.name')}${REQUIRED_MARK}</label>
                <input type="text" name="name" placeholder="${t('environments.form.namePlaceholder')}" required>
            </div>
            <div class="form-item">
                <label>${t('environments.form.cluster')}</label>
                <select name="clusterId" id="envClusterId">
                    <option value="">${t('environments.form.selectCluster')}</option>
                </select>
            </div>
            <div class="form-item">
                <label>${t('environments.form.namespace')}</label>
                <input type="text" name="k8sNamespace">
            </div>
            <div class="form-item">
                <label>${t('environments.form.type')}${REQUIRED_MARK}</label>
                <select name="envType" required>
                    <option value="nonprod" selected>${t('environments.type.nonprod')}</option>
                    <option value="prod">${t('environments.type.prod')}</option>
                </select>
            </div>
        </form>
    `;
    
    showModal(t('environments.form.createTitle'), content, async () => {
        const form = document.getElementById('createEnvForm');
        const formData = new FormData(form);
        const data = Object.fromEntries(formData.entries());
        data.clusterId = data.clusterId ? parseInt(data.clusterId, 10) : null;
        
        try {
            const response = await fetch('/api/env/create', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(data)
            });
            
            if (response.ok) {
                notify(t('common.createOk'));
                loadEnvironments();
                loadEnvs();
                closeModal();
            } else {
                notify(t('common.createFailed'));
            }
        } catch (error) {
            notify((t('common.createFailed') + ': ') + error.message);
        }
    });
    if (typeof loadClustersForSelect === 'function') {
        await loadClustersForSelect();
    }
}
window.showCreateEnvModal = showCreateEnvModal;

// 编辑环境
async function editEnv(id) {
    try {
        const response = await fetch(`/api/env/${id}`);
        if (!response.ok) {
            throw new Error(t('environments.msg.detailFailed'));
        }

        const env = await response.json();
        if (!env) {
            throw new Error(t('environments.msg.notFound'));
        }

        const content = `
            <form id="editEnvForm">
                <div class="form-item">
                    <label>${t('environments.form.name')}${REQUIRED_MARK}</label>
                    <input type="text" name="name" value="${escapeHtml(env.name || '')}" required>
                </div>
                <div class="form-item">
                    <label>${t('environments.form.cluster')}</label>
                    <select name="clusterId" id="envClusterId">
                        <option value="">${t('environments.form.selectCluster')}</option>
                    </select>
                </div>
                <div class="form-item">
                    <label>${t('environments.form.namespace')}</label>
                    <input type="text" name="k8sNamespace" value="${escapeHtml(env.k8sNamespace || '')}">
                </div>
                <div class="form-item">
                    <label>${t('environments.form.type')}${REQUIRED_MARK}</label>
                    <select name="envType" required>
                        <option value="nonprod" ${String(env.envType || '').toLowerCase() !== 'prod' ? 'selected' : ''}>${t('environments.type.nonprod')}</option>
                        <option value="prod" ${String(env.envType || '').toLowerCase() === 'prod' ? 'selected' : ''}>${t('environments.type.prod')}</option>
                    </select>
                </div>
                <div class="form-item">
                    <label>${t('common.status')}</label>
                    <select name="status">
                        <option value="1" ${env.status === 1 ? 'selected' : ''}>${t('common.enabled')}</option>
                        <option value="0" ${env.status === 0 ? 'selected' : ''}>${t('common.disabled')}</option>
                    </select>
                </div>
            </form>
        `;

        showModal(t('environments.form.editTitle'), content, async () => {
            const form = document.getElementById('editEnvForm');
            const formData = new FormData(form);
            const data = Object.fromEntries(formData.entries());
            data.status = parseInt(data.status, 10);
            data.clusterId = data.clusterId ? parseInt(data.clusterId, 10) : null;

            try {
                const updateResp = await fetch(`/api/env/${id}`, {
                    method: 'PUT',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify(data)
                });

                if (!updateResp.ok) {
                    const errorText = await updateResp.text();
                    throw new Error(errorText || t('common.updateFailed'));
                }

                notify(t('common.updateOk'));
                await loadEnvironments();
                if (typeof loadEnvs === 'function') {
                    await loadEnvs();
                }
                closeModal();
            } catch (error) {
                notify((t('common.updateFailed') + ': ') + error.message);
            }
        });
        if (typeof loadClustersForSelect === 'function') {
            await loadClustersForSelect(env.clusterId);
        }
    } catch (error) {
        notify((t('environments.msg.loadFailed') + ': ') + error.message);
    }
}
window.editEnv = editEnv;

// 删除环境
async function deleteEnv(id) {
    if (!await confirmDialog(t('environments.msg.deleteConfirm'))) return;
    try {
        const response = await fetch(`/api/env/${id}`, { method: 'DELETE' });
        if (response.ok) {
            notify(t('common.deleteOk'));
            loadEnvironments();
            loadEnvs();
        }
    } catch (error) {
        notify(t('common.deleteFailed'));
    }
}
window.deleteEnv = deleteEnv;

function bindEnvActions() {
    const envSection = document.getElementById('environments-section');
    if (!envSection || envSection.dataset.bound === 'true') {
        return;
    }

    envSection.addEventListener('click', function (event) {
        const editBtn = event.target.closest('.env-edit-btn');
        if (editBtn) {
            event.preventDefault();
            event.stopPropagation();
            const id = parseInt(editBtn.getAttribute('data-env-id'), 10);
            if (!isNaN(id)) {
                window.editEnv(id);
            }
            return;
        }

        const deleteBtn = event.target.closest('.env-delete-btn');
        if (deleteBtn) {
            event.preventDefault();
            event.stopPropagation();
            const id = parseInt(deleteBtn.getAttribute('data-env-id'), 10);
            if (!isNaN(id)) {
                window.deleteEnv(id);
            }
        }
    });

    envSection.dataset.bound = 'true';
}
window.bindEnvActions = bindEnvActions;

if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', bindEnvActions);
} else {
    bindEnvActions();
}


