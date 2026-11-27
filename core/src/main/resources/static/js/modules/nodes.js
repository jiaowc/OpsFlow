// ============================================
// 节点管理模块
// ============================================

// 节点状态在库里存的是中文「在线 / 离线」：比较和提交用原值，显示时再翻译
const NODE_STATUS_ONLINE = '在线'; // i18n-ignore
const NODE_STATUS_OFFLINE = '离线'; // i18n-ignore

function nodeStatusText(status) {
    if (status === NODE_STATUS_ONLINE) return t('nodes.status.online');
    if (status === NODE_STATUS_OFFLINE) return t('nodes.status.offline');
    return status || '';
}

const DEFAULT_NODE_WORK_DIR = '~/opsflow';
const LEGACY_NODE_WORK_DIR = '/tmp/opsflow-workspace';

function normalizeNodeWorkDir(workDir) {
    if (!workDir || workDir === LEGACY_NODE_WORK_DIR) {
        return DEFAULT_NODE_WORK_DIR;
    }
    return workDir;
}

// 加载节点列表
async function loadNodes() {
    const nodeList = document.getElementById('nodeList');
    if (!nodeList) return;
    
    try {
        const response = await fetch('/api/node/list');
        if (response.ok) {
            const nodes = await response.json();
            if (nodes && nodes.length > 0) {
                renderNodeList(nodes);
                return;
            }
        }
    } catch (error) {
        console.error('Load nodes error:', error);
    }
    
    renderNodeList(mockNodes);
}

function renderNodeList(nodes) {
    const nodeList = document.getElementById('nodeList');
    if (!nodeList) return;
    
    if (nodes.length === 0) {
        nodeList.innerHTML = ('<tr><td colspan="8" style="text-align: center; padding: 40px; color: var(--text-4);">' + t('nodes.empty') + '</td></tr>');
        return;
    }
    
    nodeList.innerHTML = nodes.map(node => {
        const nodeTypeText = node.nodeType === 'build' ? t('nodes.type.build') : node.nodeType === 'deploy' ? t('nodes.type.deploy') : node.nodeType;
        const sshAddress = node.host ? `${node.host}${node.port && node.port !== 22 ? ':' + node.port : ''}` : '-';
        const authTypeText = node.authType === 'password' ? t('nodes.auth.password') : node.authType === 'private_key' ? t('nodes.auth.privateKey') : '-';
        const workDir = normalizeNodeWorkDir(node.workDir);
        return `
            <tr>
                <td>${escapeHtml(node.name)}</td>
                <td>${escapeHtml(nodeTypeText)}</td>
                <td>${escapeHtml(node.label || '-')}</td>
                <td title="${escapeHtml(node.hostKeyFingerprint ? t('nodes.hostKey.fingerprint', { fingerprint: node.hostKeyFingerprint }) : t('nodes.hostKey.none'))}">${escapeHtml(sshAddress)}${node.hostKeyFingerprint ? ' <span style="color:#059669;font-size:12px;">🔒</span>' : ''}</td>
                <td style="font-size:12px;" title="${escapeHtml(workDir)}">${escapeHtml(workDir)}</td>
                <td>${authTypeText}</td>
                <td><span style="color: ${node.status === NODE_STATUS_ONLINE ? '#059669' : '#dc2626'};">${escapeHtml(nodeStatusText(node.status === NODE_STATUS_ONLINE ? NODE_STATUS_ONLINE : NODE_STATUS_OFFLINE))}</span></td>
                <td>${/* html-safe: 函数内已转义 */ renderNodeRowActionsHtml(node)}</td>
            </tr>
        `;
    }).join('');
}

/**
 * 节点行的操作：检测环境、修改直接显示，重置主机密钥和删除收进「更多」菜单，操作列始终一行。
 * 删除和重置仍走各自的确认弹窗。
 */
function renderNodeRowActionsHtml(node) {
    if (!hasPermission('node:manage')) return '<span style="color:#9ca3af;">-</span>';
    const id = Number(node.id);
    const canCheck = node.nodeType === 'build' || node.nodeType === 'deploy';
    const checkHtml = canCheck
        ? `<button class="btn-secondary" onclick="checkNodeEnvironment(${id}, ${escapeHtml(JSON.stringify(node.name || ''))}, ${escapeHtml(JSON.stringify(node.nodeType || ''))})">${escapeHtml(t('nodes.check.button'))}</button>`
        : '';
    return `<div class="table-actions">
        ${/* html-safe: 上面已转义 */ checkHtml}
        <button class="btn-edit" onclick="editNode(${id})">${escapeHtml(t('common.modify'))}</button>
        ${/* html-safe: 写死的函数名和数字参数 */ rowMoreButtonHtml(`toggleNodeRowMenu(this, ${id}, ${node.hostKeyFingerprint ? 1 : 0})`, true)}
    </div>`;
}

function toggleNodeRowMenu(trigger, id, hasHostKey) {
    const items = [];
    if (hasHostKey) {
        items.push({ label: t('nodes.hostKey.reset'), fn: 'resetNodeHostKey', args: [id] });
    }
    items.push({ label: t('common.delete'), fn: 'deleteNode', args: [id], tone: 'danger', separated: items.length > 0 });
    toggleRowActionMenu(trigger, items);
}
window.toggleNodeRowMenu = toggleNodeRowMenu;

// 显示添加节点模态框
function showCreateNodeModal() {
    const content = `
        <form id="createNodeForm">
            <div class="form-item">
                <label>${t('nodes.form.name')}${REQUIRED_MARK}</label>
                <input type="text" name="name" required>
            </div>
            <div class="form-item">
                <label>${t('nodes.form.type')}${REQUIRED_MARK}</label>
                <select name="nodeType" required>
                    <option value="">${t('common.select')}</option>
                    <option value="build">${t('nodes.type.build')}</option>
                    <option value="deploy">${t('nodes.type.deploy')}</option>
                </select>
            </div>
            <div class="form-item">
                <label>${t('nodes.form.labels')}</label>
                <input type="text" name="label">
            </div>
            <div class="form-item">
                <label>${t('common.description')}</label>
                <textarea name="description" rows="2"></textarea>
            </div>
            <div class="form-item">
                <label>${t('nodes.form.host')}${REQUIRED_MARK}</label>
                <input type="text" name="host" placeholder="${t('nodes.form.hostPlaceholder')}" required>
            </div>
            <div class="form-item">
                <label>${t('nodes.form.workDir')}</label>
                <input type="text" name="workDir" value="${DEFAULT_NODE_WORK_DIR}" placeholder="${DEFAULT_NODE_WORK_DIR}">
                <small style="color:var(--text-3);display:block;margin-top:4px;">${t('nodes.form.workDirHint')}</small>
            </div>
            <div class="form-item">
                <label>${t('nodes.form.port')}</label>
                <input type="number" name="port" value="22" min="1" max="65535">
            </div>
            <div class="form-item">
                <label>${t('nodes.form.authType')}${REQUIRED_MARK}</label>
                <select name="authType" id="nodeAuthType" onchange="toggleNodeAuthFields()" required>
                    <option value="">${t('common.select')}</option>
                    <option value="password">${t('nodes.auth.password')}</option>
                    <option value="private_key">${t('nodes.auth.privateKey')}</option>
                </select>
            </div>
            <div class="form-item">
                <label>${t('nodes.form.username')}${REQUIRED_MARK}</label>
                <input type="text" name="username" required>
            </div>
            <div id="nodePasswordFields" class="form-item" style="display: none;">
                <label>${t('nodes.form.password')}${REQUIRED_MARK}</label>
                <input type="password" name="password" placeholder="${t('nodes.msg.passwordRequired')}"><!-- required-mark-dynamic：按认证方式切换 required，见 toggleNodeAuthFields -->
            </div>
            <div id="nodePrivateKeyFields" style="display: none;">
                <div class="form-item">
                    <label>${t('nodes.form.privateKey')}${REQUIRED_MARK}</label>
                    <textarea name="privateKey" rows="8" placeholder="${t('nodes.form.privateKeyPlaceholder')}"></textarea><!-- required-mark-dynamic：按认证方式切换 required，见 toggleNodeAuthFields -->
                    <div style="margin-top: 4px; font-size: 12px; color: var(--text-3);">
                        ${t('nodes.form.privateKeyHint')}
                    </div>
                </div>
                <div class="form-item">
                    <label>${t('nodes.form.passphrase')}</label>
                    <input type="password" name="privateKeyPassphrase" placeholder="${t('nodes.form.passphrasePlaceholder')}">
                </div>
            </div>
        </form>
    `;
    
    showModal(t('nodes.form.createTitle'), content, async () => {
        const form = document.getElementById('createNodeForm');
        const formData = new FormData(form);
        const data = Object.fromEntries(formData.entries());
        
        // 转换端口为整数
        if (data.port) {
            data.port = parseInt(data.port) || 22;
        } else {
            data.port = 22;
        }
        if (!data.workDir || !data.workDir.trim()) {
            data.workDir = DEFAULT_NODE_WORK_DIR;
        } else {
            data.workDir = normalizeNodeWorkDir(data.workDir);
        }
        
        // 根据认证方式清理不需要的字段
        if (data.authType === 'password') {
            delete data.privateKey;
            delete data.privateKeyPassphrase;
            if (!data.password) {
                notify(t('nodes.msg.passwordRequired'));
                return;
            }
        } else if (data.authType === 'private_key') {
            delete data.password;
            if (!data.privateKey || !data.privateKey.trim()) {
                notify(t('nodes.msg.privateKeyRequired'));
                return;
            }
        }
        
        data.status = NODE_STATUS_ONLINE;
        
        try {
            const response = await fetch('/api/node/create', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(data)
            });
            
            if (response.ok) {
                notify(t('common.createOk'));
                loadNodes();
                loadNodesForSelect();
                // 刷新全局节点列表，以便Pipeline模态框中的节点列表也更新
                await refreshGlobalNodes();
                closeModal();
            } else {
                const errorText = await response.text();
                notify((t('common.createFailed') + ': ') + errorText);
            }
        } catch (error) {
            notify((t('common.createFailed') + ': ') + error.message);
        }
    });
}

// 切换节点认证方式字段显示
function toggleNodeAuthFields() {
    const authType = document.getElementById('nodeAuthType').value;
    const passwordFields = document.getElementById('nodePasswordFields');
    const privateKeyFields = document.getElementById('nodePrivateKeyFields');
    const passwordInput = document.querySelector('[name="password"]');
    const privateKeyInput = document.querySelector('[name="privateKey"]');
    
    if (authType === 'password') {
        passwordFields.style.display = 'block';
        privateKeyFields.style.display = 'none';
        passwordInput.required = true;
        privateKeyInput.required = false;
    } else if (authType === 'private_key') {
        passwordFields.style.display = 'none';
        privateKeyFields.style.display = 'block';
        passwordInput.required = false;
        privateKeyInput.required = true;
    } else {
        passwordFields.style.display = 'none';
        privateKeyFields.style.display = 'none';
        passwordInput.required = false;
        privateKeyInput.required = false;
    }
}

// 编辑节点
async function editNode(id) {
    try {
        const response = await fetch(`/api/node/${id}`);
        if (!response.ok) {
            notify(t('nodes.msg.detailFailed'));
            return;
        }
        const node = await response.json();
        
        const content = `
            <form id="editNodeForm">
                <div class="form-item">
                    <label>${t('nodes.form.name')}${REQUIRED_MARK}</label>
                    <input type="text" name="name" value="${escapeHtml(node.name || '')}" required>
                </div>
                <div class="form-item">
                    <label>${t('nodes.form.type')}${REQUIRED_MARK}</label>
                    <select name="nodeType" required>
                        <option value="">${t('common.select')}</option>
                        <option value="build" ${node.nodeType === 'build' ? 'selected' : ''}>${t('nodes.type.build')}</option>
                        <option value="deploy" ${node.nodeType === 'deploy' ? 'selected' : ''}>${t('nodes.type.deploy')}</option>
                    </select>
                </div>
                <div class="form-item">
                    <label>${t('nodes.form.labels')}</label>
                    <input type="text" name="label" value="${escapeHtml(node.label || '')}">
                </div>
                <div class="form-item">
                    <label>${t('common.description')}</label>
                    <textarea name="description" rows="2">${escapeHtml(node.description || '')}</textarea>
                </div>
                <div class="form-item">
                    <label>${t('nodes.form.host')}${REQUIRED_MARK}</label>
                    <input type="text" name="host" value="${escapeHtml(node.host || '')}" placeholder="${t('nodes.form.hostPlaceholder')}" required>
                </div>
                <div class="form-item">
                    <label>${t('nodes.form.workDir')}</label>
                    <input type="text" name="workDir" value="${escapeHtml(normalizeNodeWorkDir(node.workDir))}" placeholder="${escapeHtml(DEFAULT_NODE_WORK_DIR)}">
                    <small style="color:var(--text-3);display:block;margin-top:4px;">${t('nodes.form.workDirHint')}</small>
                </div>
                <div class="form-item">
                    <label>${t('nodes.form.port')}</label>
                    <input type="number" name="port" value="${node.port || 22}" min="1" max="65535">
                </div>
                <div class="form-item">
                    <label>${t('nodes.form.authType')}${REQUIRED_MARK}</label>
                    <select name="authType" id="editNodeAuthType" onchange="toggleEditNodeAuthFields()" required>
                        <option value="">${t('common.select')}</option>
                        <option value="password" ${node.authType === 'password' ? 'selected' : ''}>${t('nodes.auth.password')}</option>
                        <option value="private_key" ${node.authType === 'private_key' ? 'selected' : ''}>${t('nodes.auth.privateKey')}</option>
                    </select>
                </div>
                <div class="form-item">
                    <label>${t('nodes.form.username')}${REQUIRED_MARK}</label>
                    <input type="text" name="username" value="${escapeHtml(node.username || '')}" required>
                </div>
                <div id="editNodePasswordFields" class="form-item" style="display: ${node.authType === 'password' ? 'block' : 'none'};">
                    <label>${t('nodes.form.password')}</label>
                    <input type="password" name="password" placeholder="${t('nodes.form.passwordKeep')}">
                </div>
                <div id="editNodePrivateKeyFields" style="display: ${node.authType === 'private_key' ? 'block' : 'none'};">
                    <div class="form-item">
                        <label>${t('nodes.form.privateKey')}</label>
                        <textarea name="privateKey" rows="8" placeholder="${node.hasPrivateKey ? t('nodes.form.privateKeySaved') : t('nodes.form.privateKeyPaste')}"></textarea>
                        <div style="margin-top: 4px; font-size: 12px; color: var(--text-3);">
                            ${t('nodes.form.privateKeyKeepHint')}
                        </div>
                    </div>
                    <div class="form-item">
                        <label>${t('nodes.form.passphrase')}</label>
                        <input type="password" name="privateKeyPassphrase" placeholder="${t('nodes.form.passphraseKeep')}">
                    </div>
                </div>
            </form>
        `;
        
        showModal(t('nodes.form.editTitle'), content, async () => {
            const form = document.getElementById('editNodeForm');
            const formData = new FormData(form);
            const data = Object.fromEntries(formData.entries());
            
            // 转换端口为整数
            if (data.port) {
                data.port = parseInt(data.port) || 22;
            } else {
                data.port = 22;
            }
            if (!data.workDir || !data.workDir.trim()) {
                data.workDir = DEFAULT_NODE_WORK_DIR;
            } else {
                data.workDir = normalizeNodeWorkDir(data.workDir);
            }
            
            // 根据认证方式清理不需要的字段
            if (data.authType === 'password') {
                delete data.privateKey;
                delete data.privateKeyPassphrase;
                // 如果密码为空，不发送密码字段
                if (!data.password || !data.password.trim()) {
                    delete data.password;
                }
            } else if (data.authType === 'private_key') {
                delete data.password;
                // 如果私钥为空，不发送私钥字段
                if (!data.privateKey || !data.privateKey.trim()) {
                    delete data.privateKey;
                }
            }
            
            try {
                const response = await fetch(`/api/node/${id}`, {
                    method: 'PUT',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify(data)
                });
                
                if (response.ok) {
                    notify(t('nodes.msg.updateOk'));
                    loadNodes();
                    loadNodesForSelect();
                    // 刷新全局节点列表，以便Pipeline模态框中的节点列表也更新
                    await refreshGlobalNodes();
                    closeModal();
                } else {
                    const errorText = await response.text();
                    notify((t('nodes.msg.updateFailed') + ': ') + errorText);
                }
            } catch (error) {
                notify((t('nodes.msg.updateFailed') + ': ') + error.message);
            }
        });
    } catch (error) {
        notify((t('nodes.msg.detailFailed') + ': ') + error.message);
    }
}

// 切换编辑节点认证方式字段显示
function toggleEditNodeAuthFields() {
    const authType = document.getElementById('editNodeAuthType').value;
    const passwordFields = document.getElementById('editNodePasswordFields');
    const privateKeyFields = document.getElementById('editNodePrivateKeyFields');
    
    if (authType === 'password') {
        passwordFields.style.display = 'block';
        privateKeyFields.style.display = 'none';
    } else if (authType === 'private_key') {
        passwordFields.style.display = 'none';
        privateKeyFields.style.display = 'block';
    } else {
        passwordFields.style.display = 'none';
        privateKeyFields.style.display = 'none';
    }
}

// 重置 SSH 主机指纹（节点重装系统 / 更换主机后使用）
async function resetNodeHostKey(id) {
    if (!await confirmDialog(t('nodes.hostKey.resetConfirm'))) return;
    try {
        const response = await fetch(`/api/node/${id}/reset-host-key`, { method: 'POST' });
        if (!response.ok) {
            const text = await response.text();
            let msg = text;
            try { msg = JSON.parse(text).message || text; } catch (e) { /* 非 JSON */ }
            notify((t('nodes.hostKey.resetFailed') + ': ') + msg);
            return;
        }
        notify(t('nodes.hostKey.resetOk'));
        loadNodes();
    } catch (e) {
        notify((t('nodes.hostKey.resetFailed') + ': ') + e.message);
    }
}

// 节点环境检测
async function checkNodeEnvironment(id, nodeName, nodeType) {
    const isDeploy = nodeType === 'deploy';
    const modalTitle = t(isDeploy ? 'nodes.check.deployTitle' : 'nodes.check.buildTitle');

    const loadingContent = `
        <div id="nodeEnvCheckResult" style="padding: 24px; text-align: center; color: var(--text-3);">
            ${t(isDeploy ? 'nodes.check.runningDeploy' : 'nodes.check.runningBuild', { name: '<strong>' + escapeHtml(nodeName || '') + '</strong>' })}
        </div>
    `;
    showModal(modalTitle, loadingContent, null, 'medium');

    try {
        const response = await fetch(`/api/node/${id}/check-env`, { method: 'POST' });
        const box = document.getElementById('nodeEnvCheckResult');
        if (!box) return;

        if (!response.ok) {
            let errText = await response.text();
            try {
                const errJson = JSON.parse(errText);
                errText = errJson.message || errJson.error || errText;
            } catch (e) { /* ignore */ }
            box.innerHTML = `<div style="color:#dc2626;padding:12px;">${t('nodes.check.failed')}: ${escapeHtml(errText)}</div>`;
            return;
        }

        const result = await response.json();
        box.innerHTML = renderNodeEnvCheckResult(result);
        // 后端已按 SSH 连通性回写在线/离线，刷新列表
        if (typeof loadNodes === 'function') {
            loadNodes();
        }
        if (typeof loadNodesForSelect === 'function') {
            loadNodesForSelect();
        }
        if (typeof refreshGlobalNodes === 'function') {
            refreshGlobalNodes();
        }
    } catch (error) {
        const box = document.getElementById('nodeEnvCheckResult');
        if (box) {
            box.innerHTML = `<div style="color:#dc2626;padding:12px;">${t('nodes.check.failed')}: ${escapeHtml(error.message)}</div>`;
        }
    }
}

function renderNodeEnvCheckResult(result) {
    const passed = result.passed === true;
    const summaryColor = passed ? '#059669' : '#dc2626';
    const items = result.items || [];
    const nodeStatus = result.status || '';
    const statusOnline = nodeStatus === NODE_STATUS_ONLINE;

    const rows = items.map(item => {
        const ok = item.passed === true;
        const statusHtml = ok
            ? ('<span style="color:#059669;font-weight:500;">' + t('nodes.check.pass') + '</span>')
            : ('<span style="color:#dc2626;font-weight:500;">' + t('nodes.check.notPass') + '</span>');
        const requiredText = item.required ? t('nodes.check.required') : t('nodes.check.optional');
        return `
            <tr>
                <td>${escapeHtml(item.name || item.key || '-')}</td>
                <td>${requiredText}</td>
                <td>${statusHtml}</td>
                <td style="font-size:12px;color:#374151;">${escapeHtml(item.version || '-')}</td>
                <td style="font-size:12px;color:#6b7280;">${escapeHtml(item.message || '-')}</td>
            </tr>
        `;
    }).join('');

    const isDeploy = result.nodeType === 'deploy';
    const hint = isDeploy
        ? t('nodes.check.deployHint')
        : t('nodes.check.buildHint');

    const statusLine = nodeStatus
        ? `<div style="margin-top:8px;font-size:13px;">${t('nodes.check.statusUpdated')}<strong style="color:${statusOnline ? '#059669' : '#dc2626'};">${escapeHtml(nodeStatusText(nodeStatus))}</strong></div>`
        : '';

    return `
        <div style="margin-bottom: 16px; padding: 12px; border-radius: 6px; background: ${passed ? '#ecfdf5' : '#fef2f2'}; color: ${summaryColor};">
            <strong>${escapeHtml(result.summary || (passed ? t('nodes.check.passed') : t('nodes.check.notPassed')))}</strong>
            ${statusLine}
        </div>
        <div class="table-container">
            <table class="data-table">
                <thead>
                    <tr>
                        <th>${t('nodes.check.col.item')}</th>
                        <th>${t('common.type')}</th>
                        <th>${t('nodes.check.col.result')}</th>
                        <th class="col-wrap">${t('nodes.check.col.output')}</th>
                        <th class="col-wrap">${t('nodes.check.col.note')}</th>
                    </tr>
                </thead>
                <tbody>${rows}</tbody>
            </table>
        </div>
        <div style="margin-top: 12px; font-size: 12px; color: #6b7280;">
            ${hint}
        </div>
    `;
}

// 删除节点
async function deleteNode(id) {
    if (!await confirmDialog(t('nodes.msg.deleteConfirm'))) return;
    try {
        const response = await fetch(`/api/node/${id}`, { method: 'DELETE' });
        if (response.ok) {
            notify(t('common.deleteOk'));
            loadNodes();
            loadNodesForSelect();
            // 刷新全局节点列表，以便Pipeline模态框中的节点列表也更新
            await refreshGlobalNodes();
        }
    } catch (error) {
        notify(t('common.deleteFailed'));
    }
}


