// ============================================
// 集群管理模块
// ============================================

async function loadClusters() {
    const clusterList = document.getElementById('clusterList');
    if (!clusterList) return;

    clusterList.innerHTML = ('<tr><td colspan="8" style="text-align:center;padding:40px;color:var(--text-4);">' + t('clusters.loading') + '</td></tr>');

    try {
        const [clusterResp, envResp] = await Promise.all([
            fetch('/api/cluster/list'),
            fetch('/api/env/list')
        ]);
        if (!clusterResp.ok || !envResp.ok) {
            throw new Error(t('clusters.msg.listFailed'));
        }

        const clusters = await clusterResp.json();
        const envs = await envResp.json();
        renderClusterList(buildClusterRows(Array.isArray(clusters) ? clusters : [], Array.isArray(envs) ? envs : []));
    } catch (error) {
        console.error('Load clusters error:', error);
        clusterList.innerHTML = `<tr><td colspan="8" style="text-align:center;padding:40px;color:#dc2626;">${t('common.loadFailed')}: ${escapeHtml(error.message)}</td></tr>`;
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
            remark: cluster.description || (relatedEnvs.length ? t('clusters.remark.inUse') : t('clusters.remark.noEnv'))
        };
    }).sort((a, b) => String(a.clusterName || '').localeCompare(String(b.clusterName || '')));
}

function renderClusterList(clusters) {
    const clusterList = document.getElementById('clusterList');
    if (!clusterList) return;

    if (!clusters.length) {
        clusterList.innerHTML = ('<tr><td colspan="8" style="text-align:center;padding:40px;color:var(--text-4);">' + t('clusters.empty') + '</td></tr>');
        return;
    }

    clusterList.innerHTML = clusters.map(cluster => `
        <tr data-cluster-id="${cluster.id}">
            <td style="font-weight:600;color:#111827;">${escapeHtml(cluster.clusterName)}</td>
            <td title="${escapeHtml(cluster.server || '')}">${cluster.server ? escapeHtml(cluster.server) : '<span style="color:#9ca3af;">-</span>'}</td>
            <td>${cluster.credentialName
                ? escapeHtml(cluster.credentialName)
                : ('<span style="color:#dc2626;">' + t('clusters.unlinked') + '</span>')}</td>
            <td>${cluster.envNames.length ? cluster.envNames.map(name => `<span class="cluster-env-tag">${escapeHtml(name)}</span>`).join(' ') : '<span style="color:#9ca3af;">-</span>'}</td>
            <td>${cluster.envCount}</td>
            <td>${renderClusterStatus(cluster.status)}</td>
            <td>${escapeHtml(cluster.remark)}</td>
            <td>
                <div class="table-actions">
                    ${hasPermission('cluster:manage') ? `<button class="${getClusterTestButtonClass(cluster.id)} cluster-test-btn" data-cluster-id="${cluster.id}" data-cluster-name="${escapeHtml(cluster.clusterName)}" title="${clusterTestPassCache[String(cluster.id)] ? t('clusters.test.recentPass') : t('clusters.test.clickHint')}">${t('clusters.test.button')}</button>
                    <button class="btn-edit cluster-edit-btn" data-cluster-id="${cluster.id}">${t('common.modify')}</button>
                    <button class="btn-danger cluster-delete-btn" data-cluster-id="${cluster.id}">${t('common.delete')}</button>` : ''}
                </div>
            </td>
        </tr>
    `).join('');
}

function renderClusterStatus(status) {
    if (status === 'ACTIVE') {
        return ('<span class="cluster-status cluster-status-active">' + t('clusters.status.active') + '</span>');
    }
    return ('<span class="cluster-status cluster-status-idle">' + t('clusters.status.idle') + '</span>');
}

async function loadKubeconfigCredentialOptions(selectedId) {
    let credentials = [];
    try {
        const response = await fetch('/api/credential/options?type=kubeconfig');
        if (response.ok) {
            credentials = await response.json();
        }
    } catch (e) {
        console.warn('Load credentials for cluster failed', e);
    }
    const kubeOnes = (credentials || []).filter(c =>
        c.credentialType === 'kubeconfig' && (c.status === 1 || c.status == null));
    let html = ('<option value="">' + t('clusters.form.noLink') + '</option>');
    if (!kubeOnes.length) {
        html += ('<option value="" disabled>' + t('clusters.form.noKubeconfig') + '</option>');
    }
    kubeOnes.forEach(c => {
        const selected = selectedId != null && String(selectedId) === String(c.id) ? ' selected' : '';
        html += `<option value="${c.id}"${selected}>${escapeHtml(c.name)}</option>`;
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
                <label>${t('clusters.form.name')}${REQUIRED_MARK}</label>
                <input type="text" name="name" placeholder="${t('clusters.form.namePlaceholder')}" required>
            </div>
            <div class="form-item">
                <label>${t('clusters.form.server')}</label>
                <input type="text" name="server" placeholder="${t('clusters.form.serverPlaceholder')}">
            </div>
            <div class="form-item">
                <label>${t('clusters.form.kubeconfig')}</label>
                <select name="credentialId">${/* html-safe: renderKubeconfigCredentialOptions 内已转义 */ credentialOptions}</select>
                <small style="color:#6b7280;">${t('clusters.form.kubeconfigHint')}</small>
            </div>
            <div class="form-item">
                <label>${t('clusters.form.description')}</label>
                <textarea name="description" rows="3" placeholder="${t('clusters.form.descriptionPlaceholder')}"></textarea>
            </div>
        </form>
    `;

    showModal(t('clusters.form.createTitle'), content, async () => {
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
                throw new Error(text || t('common.createFailed'));
            }
            notify(t('common.createOk'));
            await loadClusters();
            if (typeof loadClustersForSelect === 'function') {
                await loadClustersForSelect();
            }
            closeModal();
        } catch (error) {
            notify((t('common.createFailed') + ': ') + error.message);
        }
    });
}
window.showCreateClusterModal = showCreateClusterModal;

async function editCluster(id) {
    try {
        const response = await fetch(`/api/cluster/${id}`);
        if (!response.ok) {
            throw new Error(t('clusters.msg.detailFailed'));
        }
        const cluster = await response.json();
        if (!cluster) {
            throw new Error(t('clusters.msg.notFound'));
        }

        const credentialOptions = await loadKubeconfigCredentialOptions(cluster.credentialId);
        const content = `
            <form id="editClusterForm">
                <div class="form-item">
                    <label>${t('clusters.form.name')}${REQUIRED_MARK}</label>
                    <input type="text" name="name" value="${escapeHtml(cluster.name || '')}" required>
                </div>
                <div class="form-item">
                    <label>${t('clusters.form.server')}</label>
                    <input type="text" name="server" value="${escapeHtml(cluster.server || '')}">
                </div>
                <div class="form-item">
                    <label>${t('clusters.form.kubeconfig')}</label>
                    <select name="credentialId">${/* html-safe: renderKubeconfigCredentialOptions 内已转义 */ credentialOptions}</select>
                    <small style="color:#6b7280;">${t('clusters.form.kubeconfigHint')}</small>
                </div>
                <div class="form-item">
                    <label>${t('common.status')}</label>
                    <select name="status">
                        <option value="1" ${cluster.status === 1 ? 'selected' : ''}>${t('common.enabled')}</option>
                        <option value="0" ${cluster.status === 0 ? 'selected' : ''}>${t('common.disabled')}</option>
                    </select>
                </div>
                <div class="form-item">
                    <label>${t('clusters.form.description')}</label>
                    <textarea name="description" rows="3">${escapeHtml(cluster.description || '')}</textarea>
                </div>
            </form>
        `;

        showModal(t('clusters.form.editTitle'), content, async () => {
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
                    throw new Error(text || t('common.updateFailed'));
                }
                notify(t('common.updateOk'));
                await loadClusters();
                if (typeof loadClustersForSelect === 'function') {
                    await loadClustersForSelect();
                }
                if (typeof loadEnvironments === 'function') {
                    await loadEnvironments();
                }
                closeModal();
            } catch (error) {
                notify((t('common.updateFailed') + ': ') + error.message);
            }
        });
    } catch (error) {
        notify((t('clusters.msg.loadFailed') + ': ') + error.message);
    }
}
window.editCluster = editCluster;

async function deleteCluster(id) {
    if (!await confirmDialog(t('clusters.msg.deleteConfirm'))) return;
    try {
        const response = await fetch(`/api/cluster/${id}`, { method: 'DELETE' });
        if (!response.ok) {
            const text = await response.text();
            throw new Error(text || t('common.deleteFailed'));
        }
        notify(t('common.deleteOk'));
        await loadClusters();
        if (typeof loadClustersForSelect === 'function') {
            await loadClustersForSelect();
        }
    } catch (error) {
        notify((t('common.deleteFailed') + ': ') + error.message);
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
    let html = ('<option value="">' + t('clusters.test.localDefault') + '</option>');
    (nodes || []).forEach(n => {
        const host = n.host ? ` (${n.host})` : '';
        const typeLabel = n.nodeType === 'deploy' ? t('clusters.nodeType.deploy') : (n.nodeType === 'build' ? t('clusters.nodeType.build') : (n.nodeType || ''));
        const typeText = typeLabel ? ` [${typeLabel}]` : '';
        html += `<option value="${n.id}">${escapeHtml(n.name || ('node-' + n.id))}${escapeHtml(host)}${escapeHtml(typeText)}</option>`;
    });
    return html;
}

async function showTestClusterModal(clusterId, clusterName) {
    if (typeof showModal !== 'function') {
        notify(t('clusters.msg.modalNotLoaded'));
        return;
    }
    const nodeOptions = await buildProxyNodeOptions();
    /** 本次弹窗内的临时检测结果；仅点「确认」后写入列表按钮状态，点叉/遮罩关闭则丢弃 */
    let pendingPassed = null;

    const content = `
        <div>
            <p style="margin:0 0 12px;color:#4b5563;font-size:13px;">
                ${t('clusters.test.intro', { name: '<strong>' + escapeHtml(clusterName || '') + '</strong>' })}
            </p>
            <div class="form-item">
                <label>${t('clusters.test.proxyNode')}</label>
                <select id="clusterTestProxyNode">${/* html-safe: 选项在生成时已转义 */ nodeOptions}</select>
                <small style="color:#6b7280;">${t('clusters.test.proxyHint')}</small>
            </div>
            <div style="margin: 12px 0 16px;">
                <button type="button" class="btn-primary" id="clusterTestStartBtn">${t('clusters.test.start')}</button>
            </div>
            <div id="clusterTestResultBox" style="min-height:40px;color:#6b7280;font-size:13px;">${t('clusters.test.initial')}</div>
        </div>
    `;

    showModal(t('clusters.test.title'), content, () => {
        if (pendingPassed !== null) {
            updateClusterTestButtonState(clusterId, pendingPassed);
        }
    });

    setTimeout(() => {
        const footer = document.querySelector('#modalContainer .modal-footer');
        if (footer) {
            footer.innerHTML = ('<button class="btn-primary" onclick="confirmModal()">' + t('common.confirm') + '</button>');
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
            startBtn.textContent = t('clusters.test.running');
            box.innerHTML = ('<div style="padding:12px;color:#6b7280;">' + t('clusters.test.wait') + '</div>');
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
                    box.innerHTML = `<div style="color:#dc2626;padding:8px;">${t('clusters.test.failed')}: ${escapeHtml(errText)}</div>`;
                    pendingPassed = false;
                    return;
                }
                const result = await response.json();
                box.innerHTML = renderClusterCheckResult(result);
                pendingPassed = result.passed === true;
            } catch (error) {
                box.innerHTML = `<div style="color:#dc2626;padding:8px;">${t('clusters.test.failed')}: ${escapeHtml(error.message)}</div>`;
                pendingPassed = false;
            } finally {
                startBtn.disabled = false;
                startBtn.textContent = t('clusters.test.start');
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
        btn.title = t('clusters.test.recentPass');
    } else {
        btn.classList.add('btn-secondary');
        btn.title = t('clusters.test.recentFail');
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
            ? ('<span style="color:#059669;font-weight:500;">' + t('clusters.test.pass') + '</span>')
            : ('<span style="color:#dc2626;font-weight:500;">' + t('clusters.test.notPass') + '</span>');
        return `
            <tr>
                <td>${escapeHtml(item.name || item.key || '-')}</td>
                <td>${item.required ? t('clusters.test.required') : t('clusters.test.optional')}</td>
                <td>${statusHtml}</td>
                <td style="font-size:12px;color:#374151;white-space:pre-wrap;max-width:280px;">${escapeHtml(item.detail || '-')}</td>
                <td style="font-size:12px;color:#6b7280;">${escapeHtml(item.message || '-')}</td>
            </tr>
        `;
    }).join('');

    return `
        <div style="margin-bottom:12px;padding:10px 12px;border-radius:6px;background:${passed ? '#ecfdf5' : '#fef2f2'};color:${summaryColor};">
            <strong>${escapeHtml(result.summary || (passed ? t('clusters.test.passed') : t('clusters.test.notPassed')))}</strong>
            <div style="margin-top:4px;font-size:12px;opacity:0.85;">${t('clusters.test.proxyLabel')}: ${escapeHtml(result.proxyNodeName || t('clusters.test.localhost'))}</div>
        </div>
        <div class="table-container">
            <table class="data-table">
                <thead>
                    <tr>
                        <th>${t('clusters.test.col.item')}</th>
                        <th>${t('common.type')}</th>
                        <th>${t('clusters.test.col.result')}</th>
                        <th class="col-wrap">${t('clusters.test.col.output')}</th>
                        <th class="col-wrap">${t('clusters.form.description')}</th>
                    </tr>
                </thead>
                <tbody>${rows || ('<tr><td colspan="5" style="text-align:center;color:var(--text-4);">' + t('clusters.test.noItems') + '</td></tr>')}</tbody>
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
        let html = ('<option value="">' + t('clusters.form.selectCluster') + '</option>');
        enabled.forEach(c => {
            const selected = selectedId != null && String(selectedId) === String(c.id) ? ' selected' : '';
            const server = c.server ? ` (${c.server})` : '';
            html += `<option value="${c.id}"${selected}>${escapeHtml(c.name)}${escapeHtml(server)}</option>`;
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
