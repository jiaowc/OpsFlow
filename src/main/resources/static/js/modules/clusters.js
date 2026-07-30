// ============================================
// 集群管理模块
// ============================================

async function loadClusters() {
    const clusterList = document.getElementById('clusterList');
    if (!clusterList) return;

    clusterList.innerHTML = '<tr><td colspan="8" style="text-align:center;padding:40px;color:#999;">正在加载集群信息...</td></tr>';

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
        clusterList.innerHTML = `<tr><td colspan="8" style="text-align:center;padding:40px;color:#dc2626;">加载失败: ${escapeClusterHtml(error.message)}</td></tr>`;
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
            credentialName: cluster.credentialName,
            credentialId: cluster.credentialId,
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
        clusterList.innerHTML = '<tr><td colspan="8" style="text-align:center;padding:40px;color:#999;">暂无集群数据，请先添加集群</td></tr>';
        return;
    }

    clusterList.innerHTML = clusters.map(cluster => `
        <tr data-cluster-id="${cluster.id}">
            <td style="font-weight:600;color:#111827;">${escapeClusterHtml(cluster.clusterName)}</td>
            <td>${cluster.server ? escapeClusterHtml(cluster.server) : '<span style="color:#9ca3af;">-</span>'}</td>
            <td>${cluster.credentialName
                ? escapeClusterHtml(cluster.credentialName)
                : '<span style="color:#dc2626;">未关联</span>'}</td>
            <td>${cluster.envNames.length ? cluster.envNames.map(name => `<span class="cluster-env-tag">${escapeClusterHtml(name)}</span>`).join(' ') : '<span style="color:#9ca3af;">-</span>'}</td>
            <td>${cluster.envCount}</td>
            <td>${renderClusterStatus(cluster.status)}</td>
            <td>${escapeClusterHtml(cluster.remark)}</td>
            <td>
                <div class="table-actions">
                    <button class="${getClusterTestButtonClass(cluster.id)} cluster-test-btn" data-cluster-id="${cluster.id}" data-cluster-name="${escapeClusterHtml(cluster.clusterName)}" title="${clusterTestPassCache[String(cluster.id)] ? '最近检测通过' : '点击检测集群连通性'}">检测</button>
                    <button class="btn-edit cluster-edit-btn" data-cluster-id="${cluster.id}">修改</button>
                    <button class="btn-danger cluster-delete-btn" data-cluster-id="${cluster.id}">删除</button>
                </div>
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

async function loadKubeconfigCredentialOptions(selectedId) {
    let credentials = [];
    if (typeof window.fetchCredentialsList === 'function') {
        credentials = await window.fetchCredentialsList();
    } else {
        try {
            const response = await fetch('/api/credential/list');
            if (response.ok) {
                credentials = await response.json();
            }
        } catch (e) {
            console.warn('Load credentials for cluster failed', e);
        }
    }
    const kubeOnes = (credentials || []).filter(c =>
        c.credentialType === 'kubeconfig' && (c.status === 1 || c.status == null));
    let html = '<option value="">不关联</option>';
    if (!kubeOnes.length) {
        html += '<option value="" disabled>暂无启用的 K8s 钥匙，请先在「钥匙串管理」中添加类型为「K8s 集群」的钥匙</option>';
    }
    kubeOnes.forEach(c => {
        const selected = selectedId != null && String(selectedId) === String(c.id) ? ' selected' : '';
        html += `<option value="${c.id}"${selected}>${escapeClusterHtml(c.name)}</option>`;
    });
    return html;
}

function collectClusterFormData(form) {
    const data = Object.fromEntries(new FormData(form).entries());
    const credentialRaw = data.credentialId;
    if (credentialRaw) {
        data.credentialId = parseInt(credentialRaw, 10);
    } else {
        data.credentialId = null;
    }
    if (data.status != null && data.status !== '') {
        data.status = parseInt(data.status, 10);
    }
    return data;
}

async function showCreateClusterModal() {
    const credentialOptions = await loadKubeconfigCredentialOptions(null);
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
                <label>关联 Kubeconfig 钥匙串</label>
                <select name="credentialId">${credentialOptions}</select>
                <small style="color:#6b7280;">请选择钥匙串中类型为「K8s 集群」的凭证；部署时自动使用该配置</small>
            </div>
            <div class="form-item">
                <label>说明</label>
                <textarea name="description" rows="3" placeholder="集群用途说明"></textarea>
            </div>
        </form>
    `;

    showModal('添加集群', content, async () => {
        const form = document.getElementById('createClusterForm');
        const data = collectClusterFormData(form);
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

        const credentialOptions = await loadKubeconfigCredentialOptions(cluster.credentialId);
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
                    <label>关联 Kubeconfig 钥匙串</label>
                    <select name="credentialId">${credentialOptions}</select>
                    <small style="color:#6b7280;">请选择钥匙串中类型为「K8s 集群」的凭证；部署时自动使用该配置</small>
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
            const data = collectClusterFormData(form);

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

async function buildProxyNodeOptions() {
    let nodes = [];
    if (typeof window.refreshGlobalNodes === 'function') {
        try {
            nodes = await window.refreshGlobalNodes() || [];
        } catch (e) {
            console.warn('refreshGlobalNodes failed', e);
        }
    }
    if ((!nodes || !nodes.length) && typeof mockNodes !== 'undefined') {
        nodes = mockNodes || [];
    }
    if (!nodes || !nodes.length) {
        try {
            const response = await fetch('/api/node/list');
            if (response.ok) {
                nodes = await response.json() || [];
            }
        } catch (e) {
            console.warn('Load nodes for cluster check failed', e);
        }
    }
    let html = '<option value="">本机（默认）</option>';
    (nodes || []).forEach(n => {
        const host = n.host ? ` (${n.host})` : '';
        const typeLabel = n.nodeType === 'deploy' ? '部署' : (n.nodeType === 'build' ? '构建' : (n.nodeType || ''));
        const typeText = typeLabel ? ` [${typeLabel}]` : '';
        html += `<option value="${n.id}">${escapeClusterHtml(n.name || ('node-' + n.id))}${escapeClusterHtml(host)}${escapeClusterHtml(typeText)}</option>`;
    });
    return html;
}

async function showTestClusterModal(clusterId, clusterName) {
    if (typeof showModal !== 'function') {
        alert('模态框未加载');
        return;
    }
    const nodeOptions = await buildProxyNodeOptions();
    /** 本次弹窗内的临时检测结果；仅点「确认」后写入列表按钮状态，点叉/遮罩关闭则丢弃 */
    let pendingPassed = null;

    const content = `
        <div>
            <p style="margin:0 0 12px;color:#4b5563;font-size:13px;">
                检测集群 <strong>${escapeClusterHtml(clusterName || '')}</strong> 的连通性。将使用该集群关联的 Kubeconfig，通过所选代理节点执行 kubectl。
            </p>
            <div class="form-item">
                <label>检测代理节点</label>
                <select id="clusterTestProxyNode">${nodeOptions}</select>
                <small style="color:#6b7280;">不选择则在 OpsFlow 本机执行检测</small>
            </div>
            <div style="margin: 12px 0 16px;">
                <button type="button" class="btn-primary" id="clusterTestStartBtn">开始检测</button>
            </div>
            <div id="clusterTestResultBox" style="min-height:40px;color:#6b7280;font-size:13px;">选择代理节点后点击「开始检测」；确认后才会保存检测状态</div>
        </div>
    `;

    showModal('集群连通检测', content, () => {
        if (pendingPassed !== null) {
            updateClusterTestButtonState(clusterId, pendingPassed);
        }
    });

    setTimeout(() => {
        const footer = document.querySelector('#modalContainer .modal-footer');
        if (footer) {
            footer.innerHTML = '<button class="btn-primary" onclick="confirmModal()">确认</button>';
        }

        const startBtn = document.getElementById('clusterTestStartBtn');
        if (!startBtn) return;
        startBtn.onclick = async () => {
            const select = document.getElementById('clusterTestProxyNode');
            const box = document.getElementById('clusterTestResultBox');
            if (!box) return;
            const nodeIdRaw = select && select.value ? select.value : '';
            const body = nodeIdRaw ? { nodeId: parseInt(nodeIdRaw, 10) } : {};
            startBtn.disabled = true;
            startBtn.textContent = '检测中...';
            box.innerHTML = '<div style="padding:12px;color:#6b7280;">正在检测，请稍候...</div>';
            try {
                const response = await fetch(`/api/cluster/${clusterId}/test`, {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify(body)
                });
                if (!response.ok) {
                    let errText = await response.text();
                    try {
                        const errJson = JSON.parse(errText);
                        errText = errJson.message || errJson.error || errText;
                    } catch (e) { /* ignore */ }
                    box.innerHTML = `<div style="color:#dc2626;padding:8px;">检测失败: ${escapeClusterHtml(errText)}</div>`;
                    pendingPassed = false;
                    return;
                }
                const result = await response.json();
                box.innerHTML = renderClusterCheckResult(result);
                pendingPassed = result.passed === true;
            } catch (error) {
                box.innerHTML = `<div style="color:#dc2626;padding:8px;">检测失败: ${escapeClusterHtml(error.message)}</div>`;
                pendingPassed = false;
            } finally {
                startBtn.disabled = false;
                startBtn.textContent = '开始检测';
            }
        };
    }, 50);
}
window.showTestClusterModal = showTestClusterModal;

/** 记录各集群最近一次检测是否成功，用于列表刷新后保留按钮颜色 */
const clusterTestPassCache = {};

function updateClusterTestButtonState(clusterId, passed) {
    clusterTestPassCache[String(clusterId)] = !!passed;
    const btn = document.querySelector(`.cluster-test-btn[data-cluster-id="${clusterId}"]`);
    if (!btn) return;
    btn.classList.remove('btn-secondary', 'btn-success', 'btn-danger');
    if (passed) {
        btn.classList.add('btn-success');
        btn.title = '最近检测通过';
    } else {
        btn.classList.add('btn-secondary');
        btn.title = '最近检测未通过或未检测';
    }
}

function getClusterTestButtonClass(clusterId) {
    return clusterTestPassCache[String(clusterId)] ? 'btn-success' : 'btn-secondary';
}

function renderClusterCheckResult(result) {
    const passed = result.passed === true;
    const summaryColor = passed ? '#059669' : '#dc2626';
    const items = result.items || [];
    const rows = items.map(item => {
        const ok = item.passed === true;
        const statusHtml = ok
            ? '<span style="color:#059669;font-weight:500;">通过</span>'
            : '<span style="color:#dc2626;font-weight:500;">未通过</span>';
        return `
            <tr>
                <td>${escapeClusterHtml(item.name || item.key || '-')}</td>
                <td>${item.required ? '必检' : '可选'}</td>
                <td>${statusHtml}</td>
                <td style="font-size:12px;color:#374151;white-space:pre-wrap;max-width:280px;">${escapeClusterHtml(item.detail || '-')}</td>
                <td style="font-size:12px;color:#6b7280;">${escapeClusterHtml(item.message || '-')}</td>
            </tr>
        `;
    }).join('');

    return `
        <div style="margin-bottom:12px;padding:10px 12px;border-radius:6px;background:${passed ? '#ecfdf5' : '#fef2f2'};color:${summaryColor};">
            <strong>${escapeClusterHtml(result.summary || (passed ? '检测通过' : '检测未通过'))}</strong>
            <div style="margin-top:4px;font-size:12px;opacity:0.85;">代理节点：${escapeClusterHtml(result.proxyNodeName || '本机')}</div>
        </div>
        <div class="table-container">
            <table class="data-table">
                <thead>
                    <tr>
                        <th>检测项</th>
                        <th>类型</th>
                        <th>结果</th>
                        <th>输出</th>
                        <th>说明</th>
                    </tr>
                </thead>
                <tbody>${rows || '<tr><td colspan="5" style="text-align:center;color:#999;">无检测项</td></tr>'}</tbody>
            </table>
        </div>
    `;
}

async function loadClustersForSelect(selectedId) {
    const select = document.getElementById('envClusterId')
        || document.getElementById('envClusterSelect')
        || document.querySelector('select[name="clusterId"]');
    if (!select) return;
    try {
        const response = await fetch('/api/cluster/list');
        if (!response.ok) return;
        const clusters = await response.json();
        const enabled = (clusters || []).filter(c => c.status === 1 || c.status == null);
        let html = '<option value="">请选择集群</option>';
        enabled.forEach(c => {
            const selected = selectedId != null && String(selectedId) === String(c.id) ? ' selected' : '';
            const server = c.server ? ` (${c.server})` : '';
            html += `<option value="${c.id}"${selected}>${escapeClusterHtml(c.name)}${escapeClusterHtml(server)}</option>`;
        });
        select.innerHTML = html;
    } catch (e) {
        console.warn('loadClustersForSelect failed', e);
    }
}
window.loadClustersForSelect = loadClustersForSelect;

document.addEventListener('click', (e) => {
    const testBtn = e.target.closest('.cluster-test-btn');
    if (testBtn) {
        e.preventDefault();
        showTestClusterModal(
            testBtn.getAttribute('data-cluster-id'),
            testBtn.getAttribute('data-cluster-name')
        );
        return;
    }
    const editBtn = e.target.closest('.cluster-edit-btn');
    if (editBtn) {
        e.preventDefault();
        editCluster(editBtn.getAttribute('data-cluster-id'));
        return;
    }
    const deleteBtn = e.target.closest('.cluster-delete-btn');
    if (deleteBtn) {
        e.preventDefault();
        deleteCluster(deleteBtn.getAttribute('data-cluster-id'));
    }
});
