// ============================================
// 节点管理模块
// ============================================

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
        nodeList.innerHTML = '<tr><td colspan="7" style="text-align: center; padding: 40px; color: #999;">暂无节点</td></tr>';
        return;
    }
    
    nodeList.innerHTML = nodes.map(node => {
        const nodeTypeText = node.nodeType === 'build' ? '构建节点' : node.nodeType === 'deploy' ? '部署节点' : node.nodeType;
        const sshAddress = node.host ? `${node.host}${node.port && node.port !== 22 ? ':' + node.port : ''}` : '-';
        const authTypeText = node.authType === 'password' ? '账户密码' : node.authType === 'private_key' ? '私钥' : '-';
        return `
            <tr>
                <td>${node.name}</td>
                <td>${nodeTypeText}</td>
                <td>${node.label || '-'}</td>
                <td>${sshAddress}</td>
                <td>${authTypeText}</td>
                <td>${node.status === '在线' ? '<span style="color: #059669;">在线</span>' : '<span style="color: #dc2626;">离线</span>'}</td>
                <td>
                    <button class="btn-edit" onclick="editNode(${node.id})">修改</button>
                    <button class="btn-danger" onclick="deleteNode(${node.id})">删除</button>
                </td>
            </tr>
        `;
    }).join('');
}

// 显示添加节点模态框
function showCreateNodeModal() {
    const content = `
        <form id="createNodeForm">
            <div class="form-item">
                <label>节点名称 *</label>
                <input type="text" name="name" required>
            </div>
            <div class="form-item">
                <label>节点类型 *</label>
                <select name="nodeType" required>
                    <option value="">请选择</option>
                    <option value="build">构建节点</option>
                    <option value="deploy">部署节点</option>
                </select>
            </div>
            <div class="form-item">
                <label>标签</label>
                <input type="text" name="label">
            </div>
            <div class="form-item">
                <label>描述</label>
                <textarea name="description" rows="2"></textarea>
            </div>
            <div class="form-item">
                <label>SSH主机地址 *</label>
                <input type="text" name="host" placeholder="例如: 192.168.1.100 或 node.example.com" required>
            </div>
            <div class="form-item">
                <label>SSH端口</label>
                <input type="number" name="port" value="22" min="1" max="65535">
            </div>
            <div class="form-item">
                <label>认证方式 *</label>
                <select name="authType" id="nodeAuthType" onchange="toggleNodeAuthFields()" required>
                    <option value="">请选择</option>
                    <option value="password">账户密码</option>
                    <option value="private_key">私钥</option>
                </select>
            </div>
            <div class="form-item">
                <label>SSH用户名 *</label>
                <input type="text" name="username" required>
            </div>
            <div id="nodePasswordFields" class="form-item" style="display: none;">
                <label>SSH密码 *</label>
                <input type="password" name="password" placeholder="请输入SSH密码">
            </div>
            <div id="nodePrivateKeyFields" style="display: none;">
                <div class="form-item">
                    <label>SSH私钥 *</label>
                    <textarea name="privateKey" rows="8" placeholder="请输入SSH私钥内容（支持RSA、DSA、ECDSA、Ed25519格式）"></textarea>
                    <div style="margin-top: 4px; font-size: 12px; color: #666;">
                        提示：请粘贴完整的私钥内容，包括 -----BEGIN ... PRIVATE KEY----- 和 -----END ... PRIVATE KEY-----
                    </div>
                </div>
                <div class="form-item">
                    <label>私钥密码（可选）</label>
                    <input type="password" name="privateKeyPassphrase" placeholder="如果私钥有密码保护，请输入密码">
                </div>
            </div>
        </form>
    `;
    
    showModal('添加节点', content, async () => {
        const form = document.getElementById('createNodeForm');
        const formData = new FormData(form);
        const data = Object.fromEntries(formData.entries());
        
        // 转换端口为整数
        if (data.port) {
            data.port = parseInt(data.port) || 22;
        } else {
            data.port = 22;
        }
        
        // 根据认证方式清理不需要的字段
        if (data.authType === 'password') {
            delete data.privateKey;
            delete data.privateKeyPassphrase;
            if (!data.password) {
                alert('请输入SSH密码');
                return;
            }
        } else if (data.authType === 'private_key') {
            delete data.password;
            if (!data.privateKey || !data.privateKey.trim()) {
                alert('请输入SSH私钥');
                return;
            }
        }
        
        data.status = '在线';
        
        try {
            const response = await fetch('/api/node/create', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(data)
            });
            
            if (response.ok) {
                alert('创建成功');
                loadNodes();
                loadNodesForSelect();
                // 刷新全局节点列表，以便Pipeline模态框中的节点列表也更新
                await refreshGlobalNodes();
                closeModal();
            } else {
                const errorText = await response.text();
                alert('创建失败: ' + errorText);
            }
        } catch (error) {
            alert('创建失败: ' + error.message);
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
            alert('获取节点信息失败');
            return;
        }
        const node = await response.json();
        
        const content = `
            <form id="editNodeForm">
                <div class="form-item">
                    <label>节点名称 *</label>
                    <input type="text" name="name" value="${node.name || ''}" required>
                </div>
                <div class="form-item">
                    <label>节点类型 *</label>
                    <select name="nodeType" required>
                        <option value="">请选择</option>
                        <option value="build" ${node.nodeType === 'build' ? 'selected' : ''}>构建节点</option>
                        <option value="deploy" ${node.nodeType === 'deploy' ? 'selected' : ''}>部署节点</option>
                    </select>
                </div>
                <div class="form-item">
                    <label>标签</label>
                    <input type="text" name="label" value="${node.label || ''}">
                </div>
                <div class="form-item">
                    <label>描述</label>
                    <textarea name="description" rows="2">${node.description || ''}</textarea>
                </div>
                <div class="form-item">
                    <label>SSH主机地址 *</label>
                    <input type="text" name="host" value="${node.host || ''}" placeholder="例如: 192.168.1.100 或 node.example.com" required>
                </div>
                <div class="form-item">
                    <label>SSH端口</label>
                    <input type="number" name="port" value="${node.port || 22}" min="1" max="65535">
                </div>
                <div class="form-item">
                    <label>认证方式 *</label>
                    <select name="authType" id="editNodeAuthType" onchange="toggleEditNodeAuthFields()" required>
                        <option value="">请选择</option>
                        <option value="password" ${node.authType === 'password' ? 'selected' : ''}>账户密码</option>
                        <option value="private_key" ${node.authType === 'private_key' ? 'selected' : ''}>私钥</option>
                    </select>
                </div>
                <div class="form-item">
                    <label>SSH用户名 *</label>
                    <input type="text" name="username" value="${node.username || ''}" required>
                </div>
                <div id="editNodePasswordFields" class="form-item" style="display: ${node.authType === 'password' ? 'block' : 'none'};">
                    <label>SSH密码</label>
                    <input type="password" name="password" placeholder="留空则不修改密码">
                </div>
                <div id="editNodePrivateKeyFields" style="display: ${node.authType === 'private_key' ? 'block' : 'none'};">
                    <div class="form-item">
                        <label>SSH私钥</label>
                        <textarea name="privateKey" rows="8" placeholder="留空则不修改私钥">${node.privateKey || ''}</textarea>
                        <div style="margin-top: 4px; font-size: 12px; color: #666;">
                            提示：留空则不修改私钥，如需修改请粘贴完整的私钥内容
                        </div>
                    </div>
                    <div class="form-item">
                        <label>私钥密码（可选）</label>
                        <input type="password" name="privateKeyPassphrase" value="${node.privateKeyPassphrase || ''}" placeholder="如果私钥有密码保护，请输入密码">
                    </div>
                </div>
            </form>
        `;
        
        showModal('编辑节点', content, async () => {
            const form = document.getElementById('editNodeForm');
            const formData = new FormData(form);
            const data = Object.fromEntries(formData.entries());
            
            // 转换端口为整数
            if (data.port) {
                data.port = parseInt(data.port) || 22;
            } else {
                data.port = 22;
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
                    alert('更新成功');
                    loadNodes();
                    loadNodesForSelect();
                    // 刷新全局节点列表，以便Pipeline模态框中的节点列表也更新
                    await refreshGlobalNodes();
                    closeModal();
                } else {
                    const errorText = await response.text();
                    alert('更新失败: ' + errorText);
                }
            } catch (error) {
                alert('更新失败: ' + error.message);
            }
        });
    } catch (error) {
        alert('获取节点信息失败: ' + error.message);
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

// 删除节点
async function deleteNode(id) {
    if (!confirm('确定要删除这个节点吗？')) return;
    try {
        const response = await fetch(`/api/node/${id}`, { method: 'DELETE' });
        if (response.ok) {
            alert('删除成功');
            loadNodes();
            loadNodesForSelect();
            // 刷新全局节点列表，以便Pipeline模态框中的节点列表也更新
            await refreshGlobalNodes();
        }
    } catch (error) {
        alert('删除失败');
    }
}


