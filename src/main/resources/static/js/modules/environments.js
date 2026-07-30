// ============================================
// 环境配置模块
// ============================================

function formatEnvType(envType, envName) {
    const normalized = String(envType || '').trim().toLowerCase();
    if (normalized === 'prod' || (!normalized && String(envName || '').trim().toLowerCase() === 'prod')) {
        return '<span style="color:#dc2626;font-weight:600;">生产环境</span>';
    }
    return '<span style="color:#2563eb;font-weight:600;">非生产环境</span>';
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
        envList.innerHTML = '<tr><td colspan="6" style="text-align: center; padding: 40px; color: #999;">暂无环境</td></tr>';
        return;
    }
    
    envList.innerHTML = envs.map(env => `
        <tr data-env-id="${env.id}">
            <td><strong>${escapeHtml(env.name || '-')}</strong></td>
            <td>
                ${env.k8sCluster ? `<div style="font-weight:600;color:#111827;">${escapeHtml(env.k8sCluster)}</div>` : emptyCell}
                ${env.clusterServer ? `<div style="margin-top:4px;font-size:12px;color:#9ca3af;">${escapeHtml(env.clusterServer)}</div>` : ''}
            </td>
            <td>${env.k8sNamespace ? escapeHtml(env.k8sNamespace) : emptyCell}</td>
            <td style="white-space:nowrap;">${formatEnvType(env.envType, env.name)}</td>
            <td>${env.status === 1 ? '<span style="color: #059669;">启用</span>' : '<span style="color: #dc2626;">禁用</span>'}</td>
            <td>
                <div class="table-actions">
                    <button class="btn-edit env-edit-btn" data-env-id="${env.id}">修改</button>
                    <button class="btn-danger env-delete-btn" data-env-id="${env.id}">删除</button>
                </div>
            </td>
        </tr>
    `).join('');
}

// 显示添加环境模态框
async function showCreateEnvModal() {
    const content = `
        <form id="createEnvForm">
            <div class="form-item">
                <label>环境名称 *</label>
                <input type="text" name="name" placeholder="例如: dev, uat, prod" required>
            </div>
            <div class="form-item">
                <label>关联集群</label>
                <select name="clusterId" id="envClusterId">
                    <option value="">请选择集群</option>
                </select>
            </div>
            <div class="form-item">
                <label>K8s命名空间</label>
                <input type="text" name="k8sNamespace">
            </div>
            <div class="form-item">
                <label>环境类型 *</label>
                <select name="envType" required>
                    <option value="nonprod" selected>非生产环境</option>
                    <option value="prod">生产环境</option>
                </select>
            </div>
        </form>
    `;
    
    showModal('添加环境', content, async () => {
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
                alert('创建成功');
                loadEnvironments();
                loadEnvs();
                closeModal();
            } else {
                alert('创建失败');
            }
        } catch (error) {
            alert('创建失败: ' + error.message);
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
            throw new Error('获取环境详情失败');
        }

        const env = await response.json();
        if (!env) {
            throw new Error('环境不存在');
        }

        const content = `
            <form id="editEnvForm">
                <div class="form-item">
                    <label>环境名称 *</label>
                    <input type="text" name="name" value="${escapeHtml(env.name || '')}" required>
                </div>
                <div class="form-item">
                    <label>关联集群</label>
                    <select name="clusterId" id="envClusterId">
                        <option value="">请选择集群</option>
                    </select>
                </div>
                <div class="form-item">
                    <label>K8s命名空间</label>
                    <input type="text" name="k8sNamespace" value="${escapeHtml(env.k8sNamespace || '')}">
                </div>
                <div class="form-item">
                    <label>环境类型 *</label>
                    <select name="envType" required>
                        <option value="nonprod" ${String(env.envType || '').toLowerCase() !== 'prod' ? 'selected' : ''}>非生产环境</option>
                        <option value="prod" ${String(env.envType || '').toLowerCase() === 'prod' ? 'selected' : ''}>生产环境</option>
                    </select>
                </div>
                <div class="form-item">
                    <label>状态</label>
                    <select name="status">
                        <option value="1" ${env.status === 1 ? 'selected' : ''}>启用</option>
                        <option value="0" ${env.status === 0 ? 'selected' : ''}>禁用</option>
                    </select>
                </div>
            </form>
        `;

        showModal('编辑环境', content, async () => {
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
                    throw new Error(errorText || '修改失败');
                }

                alert('修改成功');
                await loadEnvironments();
                if (typeof loadEnvs === 'function') {
                    await loadEnvs();
                }
                closeModal();
            } catch (error) {
                alert('修改失败: ' + error.message);
            }
        });
        if (typeof loadClustersForSelect === 'function') {
            await loadClustersForSelect(env.clusterId);
        }
    } catch (error) {
        alert('加载环境失败: ' + error.message);
    }
}
window.editEnv = editEnv;

// 删除环境
async function deleteEnv(id) {
    if (!confirm('确定要删除这个环境吗？')) return;
    try {
        const response = await fetch(`/api/env/${id}`, { method: 'DELETE' });
        if (response.ok) {
            alert('删除成功');
            loadEnvironments();
            loadEnvs();
        }
    } catch (error) {
        alert('删除失败');
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


