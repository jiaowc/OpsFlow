// ============================================
// 用户管理模块（用户、角色、权限）
// ============================================

// 显示用户管理标签页
function showUserTab(tabName, element) {
    // 移除所有标签页的active类
    const tabNav = element ? element.closest('.tab-nav') : null;
    if (tabNav) {
        tabNav.querySelectorAll('.tab-btn').forEach(btn => {
            btn.classList.remove('active');
        });
    }
    
    // 隐藏所有标签页内容
    document.querySelectorAll('.user-tab-content').forEach(content => {
        content.classList.remove('active');
    });
    
    // 激活选中的标签页
    if (element) {
        element.classList.add('active');
    }
    
    // 显示对应的内容
    const contentId = tabName + '-tab';
    const content = document.getElementById(contentId);
    if (content) {
        content.classList.add('active');
    }
    
    // 加载对应的数据
    if (tabName === 'user-list') {
        loadUsers();
    } else if (tabName === 'role-management') {
        loadRoles();
    } else if (tabName === 'permission-management') {
        loadPermissions();
    }
}

// 加载用户列表
async function loadUsers() {
    const userList = document.getElementById('userList');
    if (!userList) return;
    
    try {
        const response = await fetch('/api/user/list');
        if (response.ok) {
            const users = await response.json();
            if (users && users.length > 0) {
                renderUserList(users);
                return;
            }
        }
    } catch (error) {
        console.error('Load users error:', error);
    }
    
    userList.innerHTML = '<tr><td colspan="8" style="text-align: center; padding: 40px; color: #999;">暂无用户数据</td></tr>';
}

function formatUserSource(source) {
    const s = (source || 'local').toLowerCase();
    if (s === 'feishu') {
        return '<span style="display:inline-block;padding:2px 8px;border-radius:999px;background:#eef2ff;color:#3730a3;font-size:12px;">飞书</span>';
    }
    if (s === 'ldap') {
        return '<span style="display:inline-block;padding:2px 8px;border-radius:999px;background:#ecfdf5;color:#047857;font-size:12px;">LDAP</span>';
    }
    return '<span style="display:inline-block;padding:2px 8px;border-radius:999px;background:#f3f4f6;color:#4b5563;font-size:12px;">本地</span>';
}

function renderUserSourceOptions(selected) {
    const cur = (selected || 'local').toLowerCase();
    return `
        <option value="local" ${cur === 'local' ? 'selected' : ''}>本地</option>
        <option value="feishu" ${cur === 'feishu' ? 'selected' : ''}>飞书</option>
        <option value="ldap" ${cur === 'ldap' ? 'selected' : ''}>LDAP</option>
    `;
}

// 渲染用户列表
function renderUserList(users) {
    const userList = document.getElementById('userList');
    if (!userList) return;
    
    userList.innerHTML = users.map(user => {
        const roles = user.roles && user.roles.length > 0 
            ? user.roles.map(r => r.name).join(', ') 
            : '-';
        const statusText = user.status === 1 ? '<span style="color: #059669;">启用</span>' : '<span style="color: #dc2626;">禁用</span>';
        
        return `
            <tr>
                <td>${user.username || '-'}</td>
                <td>${user.realName || '-'}</td>
                <td>${formatUserSource(user.source)}</td>
                <td>${user.email || '-'}</td>
                <td>${user.phone || '-'}</td>
                <td>${roles}</td>
                <td>${statusText}</td>
                <td>
                    <button class="btn-edit" onclick="editUser(${user.id})">修改</button>
                    <button class="btn-danger" onclick="deleteUser(${user.id})">删除</button>
                </td>
            </tr>
        `;
    }).join('');
}

// 复选列表：避免 native multi-select 需 Ctrl/Cmd 才能多选的问题
function escapeUserHtml(text) {
    if (text == null) return '';
    return String(text)
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;')
        .replace(/'/g, '&#39;');
}

function renderCheckboxList(items, inputName, selectedIds, labelHtmlFn) {
    const selected = new Set((selectedIds || []).map(id => Number(id)));
    if (!items || items.length === 0) {
        return `<div class="checkbox-list"><div class="checkbox-list-empty">暂无可选项</div></div>`;
    }
    return `
        <div class="checkbox-list">
            ${items.map(item => `
                <label class="checkbox-list-item">
                    <input type="checkbox" name="${inputName}" value="${item.id}" ${selected.has(Number(item.id)) ? 'checked' : ''}>
                    <span>${labelHtmlFn(item)}</span>
                </label>
            `).join('')}
        </div>
    `;
}

function collectCheckedIds(form, inputName) {
    if (!form) return [];
    return Array.from(form.querySelectorAll(`input[name="${inputName}"]:checked`))
        .map(el => parseInt(el.value, 10))
        .filter(id => !Number.isNaN(id));
}

// 显示添加用户模态框
async function showAddUserModal() {
    // 加载角色列表
    let roles = [];
    try {
        const response = await fetch('/api/role/list');
        if (response.ok) {
            roles = await response.json();
        }
    } catch (error) {
        console.error('Load roles error:', error);
    }
    
    const content = `
        <form id="addUserForm">
            <div class="form-item">
                <label>用户名 *</label>
                <input type="text" name="username" placeholder="例如: admin" required>
            </div>
            <div class="form-item">
                <label>密码 *</label>
                <input type="password" name="password" placeholder="请输入密码" required>
            </div>
            <div class="form-item">
                <label>真实姓名</label>
                <input type="text" name="realName" placeholder="例如: 张三">
            </div>
            <div class="form-item">
                <label>邮箱</label>
                <input type="email" name="email" placeholder="例如: user@example.com">
            </div>
            <div class="form-item">
                <label>手机号</label>
                <input type="text" name="phone" placeholder="例如: 13800138000">
            </div>
            <div class="form-item">
                <label>飞书用户 ID</label>
                <input type="text" name="feishuUserId" placeholder="可选，用于审批卡片推送">
                <small style="color:#6b7280;">也可填写手机号/邮箱，系统会尝试自动匹配飞书账号</small>
            </div>
            <div class="form-item">
                <label>来源</label>
                <select name="source">${renderUserSourceOptions('local')}</select>
                <small style="color:#6b7280;">手动添加一般为「本地」；飞书/LDAP 同步用户请选对应来源</small>
            </div>
            <div class="form-item">
                <label>角色</label>
                ${renderCheckboxList(roles, 'roleIds', [], r => escapeUserHtml(r.name || r.code || String(r.id)))}
            </div>
            <div class="form-item">
                <label style="display: flex; align-items: center; gap: 8px;">
                    <input type="checkbox" name="status" checked> 启用
                </label>
            </div>
        </form>
    `;
    
    showModal('添加用户', content, async () => {
        const form = document.getElementById('addUserForm');
        const formData = new FormData(form);
        const selectedRoleIds = collectCheckedIds(form, 'roleIds');
        
        const data = {
            username: formData.get('username'),
            password: formData.get('password'),
            realName: formData.get('realName') || null,
            email: formData.get('email') || null,
            phone: formData.get('phone') || null,
            feishuUserId: formData.get('feishuUserId') || null,
            source: formData.get('source') || 'local',
            status: formData.get('status') === 'on' ? 1 : 0,
            roleIds: selectedRoleIds
        };
        
        try {
            const response = await fetch('/api/user/create', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(data)
            });
            
            if (response.ok) {
                alert('创建成功');
                loadUsers();
                closeModal();
            } else {
                const errorMessage = await handleApiError(response);
                alert('创建失败: ' + errorMessage);
            }
        } catch (error) {
            alert('创建失败: ' + error.message);
        }
    });
}

// 编辑用户
async function editUser(id) {
    try {
        const response = await fetch(`/api/user/${id}`);
        if (!response.ok) {
            alert('获取用户信息失败');
            return;
        }
        const user = await response.json();
        
        // 加载角色列表
        let roles = [];
        try {
            const roleResponse = await fetch('/api/role/list');
            if (roleResponse.ok) {
                roles = await roleResponse.json();
            }
        } catch (error) {
            console.error('Load roles error:', error);
        }
        
        const content = `
            <form id="editUserForm">
                <div class="form-item">
                    <label>用户名 *</label>
                    <input type="text" name="username" value="${user.username || ''}" required>
                </div>
                <div class="form-item">
                    <label>密码（留空则不修改）</label>
                    <input type="password" name="password" placeholder="留空则不修改密码">
                </div>
                <div class="form-item">
                    <label>真实姓名</label>
                    <input type="text" name="realName" value="${user.realName || ''}">
                </div>
                <div class="form-item">
                    <label>邮箱</label>
                    <input type="email" name="email" value="${user.email || ''}">
                </div>
                <div class="form-item">
                    <label>手机号</label>
                    <input type="text" name="phone" value="${user.phone || ''}">
                </div>
                <div class="form-item">
                    <label>飞书用户 ID</label>
                    <input type="text" name="feishuUserId" value="${user.feishuUserId || ''}" placeholder="可选，用于审批卡片推送">
                    <small style="color:#6b7280;">也可依赖手机号/邮箱自动匹配飞书账号</small>
                </div>
                <div class="form-item">
                    <label>来源</label>
                    <select name="source">${renderUserSourceOptions(user.source || 'local')}</select>
                </div>
                <div class="form-item">
                    <label>角色</label>
                    ${renderCheckboxList(roles, 'roleIds', user.roleIds || [], r => escapeUserHtml(r.name || r.code || String(r.id)))}
                </div>
                <div class="form-item">
                    <label style="display: flex; align-items: center; gap: 8px;">
                        <input type="checkbox" name="status" ${user.status === 1 ? 'checked' : ''}> 启用
                    </label>
                </div>
            </form>
        `;
        
        showModal('编辑用户', content, async () => {
            const form = document.getElementById('editUserForm');
            const formData = new FormData(form);
            const selectedRoleIds = collectCheckedIds(form, 'roleIds');
            
            const data = {
                username: formData.get('username'),
                realName: formData.get('realName') || null,
                email: formData.get('email') || null,
                phone: formData.get('phone') || null,
                feishuUserId: formData.get('feishuUserId') || '',
                source: formData.get('source') || 'local',
                status: formData.get('status') === 'on' ? 1 : 0,
                roleIds: selectedRoleIds
            };
            
            const password = formData.get('password');
            if (password && password.trim()) {
                data.password = password;
            }
            
            try {
                const response = await fetch(`/api/user/${id}`, {
                    method: 'PUT',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify(data)
                });
                
                if (response.ok) {
                    alert('更新成功');
                    loadUsers();
                    closeModal();
                } else {
                    const errorMessage = await handleApiError(response);
                    alert('更新失败: ' + errorMessage);
                }
            } catch (error) {
                alert('更新失败: ' + error.message);
            }
        });
    } catch (error) {
        alert('获取用户信息失败: ' + error.message);
    }
}

// 删除用户
async function deleteUser(id) {
    if (!confirm('确定要删除该用户吗？')) {
        return;
    }
    
    try {
        const response = await fetch(`/api/user/${id}`, { method: 'DELETE' });
        if (response.ok) {
            alert('删除成功');
            loadUsers();
        } else {
            alert('删除失败');
        }
    } catch (error) {
        alert('删除失败');
    }
}

// 加载角色列表
async function loadRoles() {
    const roleList = document.getElementById('roleList');
    if (!roleList) return;
    
    try {
        const response = await fetch('/api/role/list');
        if (response.ok) {
            const roles = await response.json();
            if (roles && roles.length > 0) {
                renderRoleList(roles);
                return;
            }
        }
    } catch (error) {
        console.error('Load roles error:', error);
    }
    
    roleList.innerHTML = '<tr><td colspan="6" style="text-align: center; padding: 40px; color: #999;">暂无角色数据</td></tr>';
}

// 渲染角色列表
function renderRoleList(roles) {
    const roleList = document.getElementById('roleList');
    if (!roleList) return;
    
    roleList.innerHTML = roles.map(role => {
        const permissionCount = role.permissions ? role.permissions.length : 0;
        const statusText = role.status === 1 ? '<span style="color: #059669;">启用</span>' : '<span style="color: #dc2626;">禁用</span>';
        
        return `
            <tr>
                <td>${role.name || '-'}</td>
                <td>${role.code || '-'}</td>
                <td>${role.description || '-'}</td>
                <td>${permissionCount}</td>
                <td>${statusText}</td>
                <td>
                    <button class="btn-edit" onclick="editRole(${role.id})">修改</button>
                    <button class="btn-danger" onclick="deleteRole(${role.id})">删除</button>
                </td>
            </tr>
        `;
    }).join('');
}

// 显示添加角色模态框
async function showAddRoleModal() {
    // 加载权限列表
    let permissions = [];
    try {
        const response = await fetch('/api/permission/list');
        if (response.ok) {
            permissions = await response.json();
        }
    } catch (error) {
        console.error('Load permissions error:', error);
    }
    
    const content = `
        <form id="addRoleForm">
            <div class="form-item">
                <label>角色名称 *</label>
                <input type="text" name="name" placeholder="例如: 管理员" required>
            </div>
            <div class="form-item">
                <label>角色代码 *</label>
                <input type="text" name="code" placeholder="例如: ADMIN" required>
            </div>
            <div class="form-item">
                <label>描述</label>
                <textarea name="description" rows="2" placeholder="角色描述"></textarea>
            </div>
            <div class="form-item">
                <label>权限</label>
                ${renderCheckboxList(permissions, 'permissionIds', [], p =>
                    `${escapeUserHtml(p.name || '')} <span class="perm-code">(${escapeUserHtml(p.code || '')})</span>`)}
            </div>
            <div class="form-item">
                <label style="display: flex; align-items: center; gap: 8px;">
                    <input type="checkbox" name="status" checked> 启用
                </label>
            </div>
        </form>
    `;
    
    showModal('添加角色', content, async () => {
        const form = document.getElementById('addRoleForm');
        const formData = new FormData(form);
        const selectedPermissionIds = collectCheckedIds(form, 'permissionIds');
        
        const data = {
            name: formData.get('name'),
            code: formData.get('code'),
            description: formData.get('description') || null,
            status: formData.get('status') === 'on' ? 1 : 0,
            permissionIds: selectedPermissionIds
        };
        
        try {
            const response = await fetch('/api/role/create', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(data)
            });
            
            if (response.ok) {
                alert('创建成功');
                loadRoles();
                closeModal();
            } else {
                const errorMessage = await handleApiError(response);
                alert('创建失败: ' + errorMessage);
            }
        } catch (error) {
            alert('创建失败: ' + error.message);
        }
    });
}

// 编辑角色
async function editRole(id) {
    try {
        const response = await fetch(`/api/role/${id}`);
        if (!response.ok) {
            alert('获取角色信息失败');
            return;
        }
        const role = await response.json();
        
        // 加载权限列表
        let permissions = [];
        try {
            const permissionResponse = await fetch('/api/permission/list');
            if (permissionResponse.ok) {
                permissions = await permissionResponse.json();
            }
        } catch (error) {
            console.error('Load permissions error:', error);
        }
        
        const content = `
            <form id="editRoleForm">
                <div class="form-item">
                    <label>角色名称 *</label>
                    <input type="text" name="name" value="${role.name || ''}" required>
                </div>
                <div class="form-item">
                    <label>角色代码 *</label>
                    <input type="text" name="code" value="${role.code || ''}" required>
                </div>
                <div class="form-item">
                    <label>描述</label>
                    <textarea name="description" rows="2">${role.description || ''}</textarea>
                </div>
                <div class="form-item">
                    <label>权限</label>
                    ${renderCheckboxList(permissions, 'permissionIds', role.permissionIds || [], p =>
                        `${escapeUserHtml(p.name || '')} <span class="perm-code">(${escapeUserHtml(p.code || '')})</span>`)}
                </div>
                <div class="form-item">
                    <label style="display: flex; align-items: center; gap: 8px;">
                        <input type="checkbox" name="status" ${role.status === 1 ? 'checked' : ''}> 启用
                    </label>
                </div>
            </form>
        `;
        
        showModal('编辑角色', content, async () => {
            const form = document.getElementById('editRoleForm');
            const formData = new FormData(form);
            const selectedPermissionIds = collectCheckedIds(form, 'permissionIds');
            
            const data = {
                name: formData.get('name'),
                code: formData.get('code'),
                description: formData.get('description') || null,
                status: formData.get('status') === 'on' ? 1 : 0,
                permissionIds: selectedPermissionIds
            };
            
            try {
                const response = await fetch(`/api/role/${id}`, {
                    method: 'PUT',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify(data)
                });
                
                if (response.ok) {
                    alert('更新成功');
                    loadRoles();
                    closeModal();
                } else {
                    const errorMessage = await handleApiError(response);
                    alert('更新失败: ' + errorMessage);
                }
            } catch (error) {
                alert('更新失败: ' + error.message);
            }
        });
    } catch (error) {
        alert('获取角色信息失败: ' + error.message);
    }
}

// 删除角色
async function deleteRole(id) {
    if (!confirm('确定要删除该角色吗？删除后关联的用户将失去该角色。')) {
        return;
    }
    
    try {
        const response = await fetch(`/api/role/${id}`, { method: 'DELETE' });
        if (response.ok) {
            alert('删除成功');
            loadRoles();
        } else {
            const errorMessage = await handleApiError(response);
            alert('删除失败: ' + errorMessage);
        }
    } catch (error) {
        alert('删除失败');
    }
}

// 加载权限列表
async function loadPermissions() {
    const permissionList = document.getElementById('permissionList');
    if (!permissionList) return;
    
    try {
        const response = await fetch('/api/permission/list');
        if (response.ok) {
            const permissions = await response.json();
            if (permissions && permissions.length > 0) {
                renderPermissionList(permissions);
                return;
            }
        }
    } catch (error) {
        console.error('Load permissions error:', error);
    }
    
    permissionList.innerHTML = '<tr><td colspan="7" style="text-align: center; padding: 40px; color: #999;">暂无权限数据</td></tr>';
}

// 渲染权限列表
function renderPermissionList(permissions) {
    const permissionList = document.getElementById('permissionList');
    if (!permissionList) return;
    
    permissionList.innerHTML = permissions.map(permission => {
        const statusText = permission.status === 1 ? '<span style="color: #059669;">启用</span>' : '<span style="color: #dc2626;">禁用</span>';
        const methodText = permission.method || '-';
        
        return `
            <tr>
                <td>${permission.name || '-'}</td>
                <td>${permission.code || '-'}</td>
                <td>${permission.resource || '-'}</td>
                <td>${methodText}</td>
                <td>${permission.description || '-'}</td>
                <td>${statusText}</td>
                <td>
                    <button class="btn-edit" onclick="editPermission(${permission.id})">修改</button>
                    <button class="btn-danger" onclick="deletePermission(${permission.id})">删除</button>
                </td>
            </tr>
        `;
    }).join('');
}

// 显示添加权限模态框
async function showAddPermissionModal() {
    // 加载权限列表（用于选择父权限）
    let permissions = [];
    try {
        const response = await fetch('/api/permission/list');
        if (response.ok) {
            permissions = await response.json();
        }
    } catch (error) {
        console.error('Load permissions error:', error);
    }
    
    const content = `
        <form id="addPermissionForm">
            <div class="form-item">
                <label>权限名称 *</label>
                <input type="text" name="name" placeholder="例如: 用户管理" required>
            </div>
            <div class="form-item">
                <label>权限代码 *</label>
                <input type="text" name="code" placeholder="例如: user:manage" required>
            </div>
            <div class="form-item">
                <label>资源路径</label>
                <input type="text" name="resource" placeholder="例如: /api/user/**">
            </div>
            <div class="form-item">
                <label>HTTP方法</label>
                <select name="method">
                    <option value="">全部</option>
                    <option value="GET">GET</option>
                    <option value="POST">POST</option>
                    <option value="PUT">PUT</option>
                    <option value="DELETE">DELETE</option>
                </select>
            </div>
            <div class="form-item">
                <label>父权限</label>
                <select name="parentId">
                    <option value="0">无（顶级权限）</option>
                    ${permissions.map(p => `<option value="${p.id}">${p.name}</option>`).join('')}
                </select>
            </div>
            <div class="form-item">
                <label>描述</label>
                <textarea name="description" rows="2" placeholder="权限描述"></textarea>
            </div>
            <div class="form-item">
                <label style="display: flex; align-items: center; gap: 8px;">
                    <input type="checkbox" name="status" checked> 启用
                </label>
            </div>
        </form>
    `;
    
    showModal('添加权限', content, async () => {
        const form = document.getElementById('addPermissionForm');
        const formData = new FormData(form);
        
        const parentIdStr = formData.get('parentId');
        const parentId = parentIdStr && parentIdStr !== '0' ? parseInt(parentIdStr) : 0;
        
        const data = {
            name: formData.get('name'),
            code: formData.get('code'),
            resource: formData.get('resource') || null,
            method: formData.get('method') || null,
            parentId: parentId,
            description: formData.get('description') || null,
            status: formData.get('status') === 'on' ? 1 : 0
        };
        
        try {
            const response = await fetch('/api/permission/create', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(data)
            });
            
            if (response.ok) {
                alert('创建成功');
                loadPermissions();
                closeModal();
            } else {
                const errorMessage = await handleApiError(response);
                alert('创建失败: ' + errorMessage);
            }
        } catch (error) {
            alert('创建失败: ' + error.message);
        }
    });
}

// 编辑权限
async function editPermission(id) {
    try {
        const response = await fetch(`/api/permission/${id}`);
        if (!response.ok) {
            alert('获取权限信息失败');
            return;
        }
        const permission = await response.json();
        
        // 加载权限列表（用于选择父权限）
        let permissions = [];
        try {
            const permissionResponse = await fetch('/api/permission/list');
            if (permissionResponse.ok) {
                permissions = await permissionResponse.json();
            }
        } catch (error) {
            console.error('Load permissions error:', error);
        }
        
        const parentId = permission.parentId || 0;
        
        const content = `
            <form id="editPermissionForm">
                <div class="form-item">
                    <label>权限名称 *</label>
                    <input type="text" name="name" value="${permission.name || ''}" required>
                </div>
                <div class="form-item">
                    <label>权限代码 *</label>
                    <input type="text" name="code" value="${permission.code || ''}" required>
                </div>
                <div class="form-item">
                    <label>资源路径</label>
                    <input type="text" name="resource" value="${permission.resource || ''}">
                </div>
                <div class="form-item">
                    <label>HTTP方法</label>
                    <select name="method">
                        <option value="">全部</option>
                        <option value="GET" ${permission.method === 'GET' ? 'selected' : ''}>GET</option>
                        <option value="POST" ${permission.method === 'POST' ? 'selected' : ''}>POST</option>
                        <option value="PUT" ${permission.method === 'PUT' ? 'selected' : ''}>PUT</option>
                        <option value="DELETE" ${permission.method === 'DELETE' ? 'selected' : ''}>DELETE</option>
                    </select>
                </div>
                <div class="form-item">
                    <label>父权限</label>
                    <select name="parentId">
                        <option value="0">无（顶级权限）</option>
                        ${permissions.filter(p => p.id !== permission.id).map(p => `<option value="${p.id}" ${parentId === p.id ? 'selected' : ''}>${p.name}</option>`).join('')}
                    </select>
                </div>
                <div class="form-item">
                    <label>描述</label>
                    <textarea name="description" rows="2">${permission.description || ''}</textarea>
                </div>
                <div class="form-item">
                    <label style="display: flex; align-items: center; gap: 8px;">
                        <input type="checkbox" name="status" ${permission.status === 1 ? 'checked' : ''}> 启用
                    </label>
                </div>
            </form>
        `;
        
        showModal('编辑权限', content, async () => {
            const form = document.getElementById('editPermissionForm');
            const formData = new FormData(form);
            
            const parentIdStr = formData.get('parentId');
            const newParentId = parentIdStr && parentIdStr !== '0' ? parseInt(parentIdStr) : 0;
            
            const data = {
                name: formData.get('name'),
                code: formData.get('code'),
                resource: formData.get('resource') || null,
                method: formData.get('method') || null,
                parentId: newParentId,
                description: formData.get('description') || null,
                status: formData.get('status') === 'on' ? 1 : 0
            };
            
            try {
                const response = await fetch(`/api/permission/${id}`, {
                    method: 'PUT',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify(data)
                });
                
                if (response.ok) {
                    alert('更新成功');
                    loadPermissions();
                    closeModal();
                } else {
                    const errorMessage = await handleApiError(response);
                    alert('更新失败: ' + errorMessage);
                }
            } catch (error) {
                alert('更新失败: ' + error.message);
            }
        });
    } catch (error) {
        alert('获取权限信息失败: ' + error.message);
    }
}

// 删除权限
async function deletePermission(id) {
    if (!confirm('确定要删除该权限吗？')) {
        return;
    }
    
    try {
        const response = await fetch(`/api/permission/${id}`, { method: 'DELETE' });
        if (response.ok) {
            alert('删除成功');
            loadPermissions();
        } else {
            const errorMessage = await handleApiError(response);
            alert('删除失败: ' + errorMessage);
        }
    } catch (error) {
        alert('删除失败');
    }
}

window.renderCheckboxList = renderCheckboxList;
window.collectCheckedIds = collectCheckedIds;
window.escapeUserHtml = escapeUserHtml;


