// ============================================
// 集群管理模块
// ============================================

async function loadClusters() {
    const clusterList = document.getElementById('clusterList');
    if (!clusterList) return;

    clusterList.innerHTML = '<tr><td colspan="7" style="text-align:center;padding:40px;color:#999;">正在加载集群信息...</td></tr>';

    try {
        const [clusterResp, envResp] = await Promise.all([
            fetch('/api/cluster/list'),
            fetch('/api/env/list')
        ]);
        if (!clusterResp.ok || !envResp.ok) {
            throw new Error('获取集群信息失败');
        }

        const clusters = await clusterResp.json();
        const envs = await envResp.json();
        renderClusterList(buildClusterRows(Array.isArray(clusters) ? clusters : [], Array.isArray(envs) ? envs : []));
    } catch (error) {
        console.error('Load clusters error:', error);
        clusterList.innerHTML = `<tr><td colspan="7" style="text-align:center;padding:40px;color:#dc2626;">加载失败: ${escapeClusterHtml(error.message)}</td></tr>`;
    }
}
window.loadClusters = loadClusters;

function buildClusterRows(clusters, envs) {
    return clusters.map(cluster => {
        const relatedEnvs = envs.filter(env => String(env.clusterId || '') === String(cluster.id));
        return {
            id: cluster.id,
            clusterName: cluster.name,
            server: cluster.server,
            envNames: relatedEnvs.map(env => env.name).filter(Boolean),
            envCount: relatedEnvs.length,
            status: cluster.status === 1 ? 'ACTIVE' : 'IDLE',
            remark: cluster.description || (relatedEnvs.length ? '已被环境引用' : '暂无关联环境')
        };
    }).sort((a, b) => String(a.clusterName || '').localeCompare(String(b.clusterName || '')));
}

function renderClusterList(clusters) {
    const clusterList = document.getElementById('clusterList');
    if (!clusterList) return;

    if (!clusters.length) {
        clusterList.innerHTML = '<tr><td colspan="7" style="text-align:center;padding:40px;color:#999;">暂无集群数据，请先添加集群</td></tr>';
        return;
    }

    clusterList.innerHTML = clusters.map(cluster => `
        <tr data-cluster-id="${cluster.id}">
            <td style="font-weight:600;color:#111827;">${escapeClusterHtml(cluster.clusterName)}</td>
            <td>${cluster.server ? escapeClusterHtml(cluster.server) : '<span style="color:#9ca3af;">-</span>'}</td>
            <td>${cluster.envNames.length ? cluster.envNames.map(name => `<span class="cluster-env-tag">${escapeClusterHtml(name)}</span>`).join(' ') : '<span style="color:#9ca3af;">-</span>'}</td>
            <td>${cluster.envCount}</td>
            <td>${renderClusterStatus(cluster.status)}</td>
            <td>${escapeClusterHtml(cluster.remark)}</td>
            <td>
                <button class="btn-edit cluster-edit-btn" data-cluster-id="${cluster.id}">修改</button>
                <button class="btn-danger cluster-delete-btn" data-cluster-id="${cluster.id}">删除</button>
            </td>
        </tr>
    `).join('');
}

function renderClusterStatus(status) {
    if (status === 'ACTIVE') {
        return '<span class="cluster-status cluster-status-active">使用中</span>';
    }
    return '<span class="cluster-status cluster-status-idle">空闲</span>';
}

function escapeClusterHtml(value) {
    return String(value == null ? '' : value)
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;')
        .replace(/'/g, '&#39;');
}

function showCreateClusterModal() {
    const content = `
        <form id="createClusterForm">
            <div class="form-item">
                <label>集群名称 *</label>
                <input type="text" name="name" placeholder="例如: cluster-dev" required>
            </div>
            <div class="form-item">
                <label>集群地址</label>
                <input type="text" name="server" placeholder="例如: https://k8s-dev.example.com">
            </div>
            <div class="form-item">
                <label>说明</label>
                <textarea name="description" rows="3" placeholder="集群用途说明"></textarea>
            </div>
        </form>
    `;

    showModal('添加集群', content, async () => {
        const form = document.getElementById('createClusterForm');
        const data = Object.fromEntries(new FormData(form).entries());
        data.status = 1;

        try {
            const response = await fetch('/api/cluster/create', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(data)
            });
            if (!response.ok) {
                const text = await response.text();
                throw new Error(text || '创建失败');
            }
            alert('创建成功');
            await loadClusters();
            if (typeof loadClustersForSelect === 'function') {
                await loadClustersForSelect();
            }
            closeModal();
        } catch (error) {
            alert('创建失败: ' + error.message);
        }
    });
}
window.showCreateClusterModal = showCreateClusterModal;

async function editCluster(id) {
    try {
        const response = await fetch(`/api/cluster/${id}`);
        if (!response.ok) {
            throw new Error('获取集群详情失败');
        }
        const cluster = await response.json();
        if (!cluster) {
            throw new Error('集群不存在');
        }

        const content = `
            <form id="editClusterForm">
                <div class="form-item">
                    <label>集群名称 *</label>
                    <input type="text" name="name" value="${escapeClusterHtml(cluster.name || '')}" required>
                </div>
                <div class="form-item">
                    <label>集群地址</label>
                    <input type="text" name="server" value="${escapeClusterHtml(cluster.server || '')}">
                </div>
                <div class="form-item">
                    <label>状态</label>
                    <select name="status">
                        <option value="1" ${cluster.status === 1 ? 'selected' : ''}>启用</option>
                        <option value="0" ${cluster.status === 0 ? 'selected' : ''}>禁用</option>
                    </select>
                </div>
                <div class="form-item">
                    <label>说明</label>
                    <textarea name="description" rows="3">${escapeClusterHtml(cluster.description || '')}</textarea>
                </div>
            </form>
        `;

        showModal('编辑集群', content, async () => {
            const form = document.getElementById('editClusterForm');
            const data = Object.fromEntries(new FormData(form).entries());
            data.status = parseInt(data.status, 10);

            try {
                const updateResp = await fetch(`/api/cluster/${id}`, {
                    method: 'PUT',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify(data)
                });
                if (!updateResp.ok) {
                    const text = await updateResp.text();
                    throw new Error(text || '修改失败');
                }
                alert('修改成功');
                await loadClusters();
                if (typeof loadClustersForSelect === 'function') {
                    await loadClustersForSelect();
                }
                if (typeof loadEnvironments === 'function') {
                    await loadEnvironments();
                }
                closeModal();
            } catch (error) {
                alert('修改失败: ' + error.message);
            }
        });
    } catch (error) {
        alert('加载集群失败: ' + error.message);
    }
}
window.editCluster = editCluster;

async function deleteCluster(id) {
    if (!confirm('确定要删除这个集群吗？')) return;
    try {
        const response = await fetch(`/api/cluster/${id}`, { method: 'DELETE' });
        if (!response.ok) {
            const text = await response.text();
            throw new Error(text || '删除失败');
        }
        alert('删除成功');
        await loadClusters();
        if (typeof loadClustersForSelect === 'function') {
            await loadClustersForSelect();
        }
    } catch (error) {
        alert('删除失败: ' + error.message);
    }
}
window.deleteCluster = deleteCluster;

async function loadClustersForSelect(selectedId) {
    try {
        const response = await fetch('/api/cluster/list');
        if (!response.ok) return;
        const clusters = await response.json();
        const select = document.getElementById('envClusterId');
        if (!select) return;
        select.innerHTML = '<option value="">请选择集群</option>' + (clusters || []).map(cluster => `
            <option value="${cluster.id}" ${String(selectedId || '') === String(cluster.id) ? 'selected' : ''}>${escapeClusterHtml(cluster.name || '')}</option>
        `).join('');
    } catch (error) {
        console.error('Load clusters for select error:', error);
    }
}
window.loadClustersForSelect = loadClustersForSelect;

function bindClusterActions() {
    const clusterSection = document.getElementById('clusters-section');
    if (!clusterSection || clusterSection.dataset.bound === 'true') {
        return;
    }

    clusterSection.addEventListener('click', function (event) {
        const editBtn = event.target.closest('.cluster-edit-btn');
        if (editBtn) {
            const id = parseInt(editBtn.getAttribute('data-cluster-id'), 10);
            if (!isNaN(id)) {
                window.editCluster(id);
            }
            return;
        }

        const deleteBtn = event.target.closest('.cluster-delete-btn');
        if (deleteBtn) {
            const id = parseInt(deleteBtn.getAttribute('data-cluster-id'), 10);
            if (!isNaN(id)) {
                window.deleteCluster(id);
            }
        }
    });

    clusterSection.dataset.bound = 'true';
}
window.bindClusterActions = bindClusterActions;

if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', bindClusterActions);
} else {
    bindClusterActions();
}
