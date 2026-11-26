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

// 进入用户管理页：打开第一个当前用户可见的页签（没有页签时直接加载用户列表）
function loadUsersPage() {
    const firstTabBtn = Array.from(document.querySelectorAll('#users-section .tab-btn'))
        .find(btn => btn.style.display !== 'none');
    if (firstTabBtn) {
        showUserTab('user-list', firstTabBtn);
    } else {
        loadUsers();
    }
}
window.loadUsersPage = loadUsersPage;

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
    
    applyUserTwoFactorColumn();
    userList.innerHTML = ('<tr><td colspan="' + (userTwoFactorColumnVisible() ? 9 : 8) + '" style="text-align: center; padding: 40px; color: var(--text-4);">' + t('users.empty') + '</td></tr>');
}

// 两步验证列：策略为关闭时整列隐藏
function userTwoFactorPolicy() {
    return (currentUser && currentUser.twoFactor && currentUser.twoFactor.policy) || 'off';
}

function userTwoFactorColumnVisible() {
    return userTwoFactorPolicy() !== 'off';
}

function applyUserTwoFactorColumn() {
    document.querySelectorAll('#users-section th.user-two-factor-col').forEach(th => {
        th.style.display = userTwoFactorColumnVisible() ? '' : 'none';
    });
}

/** 只有本地和 LDAP 账号能用两步验证，单点登录来源显示「—」 */
function renderUserTwoFactorCell(user) {
    if (!userTwoFactorColumnVisible()) return '';
    const source = (user.source || 'local').toLowerCase();
    if (source !== 'local' && source !== 'ldap') {
        return '<td class="user-two-factor-col" style="color:var(--text-4);">—</td>';
    }
    return user.twoFactorEnabled
        ? `<td class="user-two-factor-col"><span class="tf-badge is-on">${escapeHtml(t('users.twoFactor.on'))}</span></td>`
        : `<td class="user-two-factor-col"><span class="tf-badge is-off">${escapeHtml(t('users.twoFactor.off'))}</span></td>`;
}

async function resetUserTwoFactor(userId, username) {
    const key = userTwoFactorPolicy() === 'required' ? 'users.twoFactor.resetConfirmForced' : 'users.twoFactor.resetConfirm';
    const ok = await confirmDialog(t(key, { username: username }), {
        title: t('users.twoFactor.reset'),
        confirmText: t('users.twoFactor.resetConfirmButton'),
        danger: true
    });
    if (!ok) return;
    try {
        const resp = await fetch('/api/user/' + encodeURIComponent(userId) + '/two-factor/reset', { method: 'POST' });
        const data = await resp.json().catch(() => ({}));
        if (!resp.ok || !data.success) {
            notify(t('common.failed') + ': ' + (data.message || data.error || resp.status), 'error');
            return;
        }
        notify(t('users.twoFactor.resetOk', { username: data.username || username }), 'success');
        loadUsers();
    } catch (e) {
        notify(t('common.failed') + ': ' + (e.message || e), 'error');
    }
}
window.resetUserTwoFactor = resetUserTwoFactor;

// 用户来源和表单扩展：扩展（如企业版的第三方登录）可以登记额外的用户来源，
// 并往添加/编辑用户表单里加字段；字段值放进请求的 extra，由后端的 UserAccountExtension 处理
const USER_SOURCE_BADGE = 'display:inline-block;padding:2px 8px;border-radius:999px;font-size:12px;';
const USER_SOURCES = [
    { value: 'local', get label() { return t('users.source.local'); }, style: 'background:#f3f4f6;color:#4b5563;' },
    { value: 'ldap', label: 'LDAP', style: 'background:#ecfdf5;color:#047857;' }
];
const USER_FORM_EXTENSIONS = [];

/** 登记一种用户来源：{ value, label, style } */
function registerUserSource(source) {
    if (!source || !source.value || USER_SOURCES.some(s => s.value === source.value)) {
        return;
    }
    USER_SOURCES.push(source);
}
window.registerUserSource = registerUserSource;

/**
 * 登记用户表单扩展：
 * - renderFields(user)：返回要插入表单的 HTML，添加用户时 user 为 null
 * - collect(form, isEdit)：返回要并入 extra 的键值
 */
function registerUserFormExtension(extension) {
    if (extension) {
        USER_FORM_EXTENSIONS.push(extension);
    }
}
window.registerUserFormExtension = registerUserFormExtension;

function renderUserFormExtensionFields(user) {
    return USER_FORM_EXTENSIONS.map(ext => {
        try {
            return typeof ext.renderFields === 'function' ? (ext.renderFields(user || null) || '') : '';
        } catch (e) {
            console.warn('User form extension render failed', e);
            return '';
        }
    }).join('');
}

function collectUserFormExtra(form, isEdit) {
    const extra = {};
    USER_FORM_EXTENSIONS.forEach(ext => {
        try {
            if (typeof ext.collect === 'function') {
                Object.assign(extra, ext.collect(form, isEdit) || {});
            }
        } catch (e) {
            console.warn('User form extension collect failed', e);
        }
    });
    return Object.keys(extra).length ? extra : null;
}

function findUserSource(value) {
    return USER_SOURCES.find(s => s.value === value);
}

function formatUserSource(source) {
    const s = (source || 'local').toLowerCase();
    const known = findUserSource(s) || findUserSource('local');
    const label = findUserSource(s) ? known.label : s;
    return `<span style="${USER_SOURCE_BADGE}${escapeHtml(known.style)}">${escapeHtml(label)}</span>`;
}

function renderUserSourceOptions(selected) {
    const cur = (selected || 'local').toLowerCase();
    const options = USER_SOURCES.slice();
    // 已有用户的来源没有登记时（例如扩展已停用）也保留原值，避免保存时被改掉
    if (!findUserSource(cur)) {
        options.push({ value: cur, label: cur });
    }
    return options.map(o =>
        `<option value="${escapeHtml(o.value)}" ${o.value === cur ? 'selected' : ''}>${escapeHtml(o.label)}</option>`
    ).join('');
}

// 渲染用户列表
function renderUserList(users) {
    const userList = document.getElementById('userList');
    if (!userList) return;
    applyUserTwoFactorColumn();
    const canResetTwoFactor = userTwoFactorColumnVisible() && hasPermission('user:manage');
    
    userList.innerHTML = users.map(user => {
        const roles = user.roles && user.roles.length > 0 
            ? user.roles.map(r => escapeHtml(r.name)).join(', ') 
            : '-';
        const statusText = user.status === 1 ? ('<span class="status-tag is-on">' + t('common.enabled') + '</span>') : ('<span class="status-tag is-off">' + t('common.disabled') + '</span>');
        
        return `
            <tr>
                <td>${escapeHtml(user.username || '-')}</td>
                <td>${escapeHtml(user.realName || '-')}</td>
                <td>${/* html-safe: 只返回固定的徽标 HTML */ formatUserSource(user.source)}</td>
                <td>${escapeHtml(user.email || '-')}</td>
                <td>${escapeHtml(user.phone || '-')}</td>
                <td>${roles}</td>
                ${renderUserTwoFactorCell(user)}
                <td>${statusText}</td>
                <td>
                    <button class="btn-edit" onclick="editUser(${user.id})">${t('common.modify')}</button>
                    ${canResetTwoFactor && user.twoFactorEnabled ? `<button class="btn-edit" data-user-id="${user.id}" data-username="${escapeHtml(user.username || '')}" onclick="resetUserTwoFactor(this.dataset.userId, this.dataset.username)">${t('users.twoFactor.reset')}</button>` : ''}
                    <button class="btn-danger" onclick="deleteUser(${user.id})">${t('common.delete')}</button>
                </td>
            </tr>
        `;
    }).join('');
}

// 复选列表：避免 native multi-select 需 Ctrl/Cmd 才能多选的问题
function renderCheckboxList(items, inputName, selectedIds, labelHtmlFn) {
    const selected = new Set((selectedIds || []).map(id => Number(id)));
    if (!items || items.length === 0) {
        return `<div class="checkbox-list"><div class="checkbox-list-empty">${t('users.noOptions')}</div></div>`;
    }
    return `
        <div class="checkbox-list">
            ${items.map(item => `
                <label class="checkbox-list-item">
                    <input type="checkbox" name="${escapeHtml(inputName)}" value="${item.id}" ${selected.has(Number(item.id)) ? 'checked' : ''}>
                    <span>${/* html-safe: 约定：回调返回已转义的 HTML */ labelHtmlFn(item)}</span>
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
                <label>${t('users.form.username')}${REQUIRED_MARK}</label>
                <input type="text" name="username" placeholder="${t('users.form.usernamePlaceholder')}" required>
            </div>
            <div class="form-item">
                <label>${t('users.form.password')}${REQUIRED_MARK}</label>
                <input type="password" name="password" placeholder="${t('users.form.passwordPlaceholder')}" required>
            </div>
            <div class="form-item">
                <label>${t('users.form.realName')}</label>
                <input type="text" name="realName" placeholder="${t('users.form.realNamePlaceholder')}">
            </div>
            <div class="form-item">
                <label>${t('users.form.email')}</label>
                <input type="email" name="email" placeholder="${t('users.form.emailPlaceholder')}">
            </div>
            <div class="form-item">
                <label>${t('users.form.phone')}</label>
                <input type="text" name="phone" placeholder="${t('users.form.phonePlaceholder')}">
            </div>
            ${renderUserFormExtensionFields(null)}
            <div class="form-item">
                <label>${t('users.form.source')}</label>
                <select name="source">${renderUserSourceOptions('local')}</select>
                <small style="color:#6b7280;">${t('users.form.sourceHint')}</small>
            </div>
            <div class="form-item">
                <label>${t('users.form.roles')}</label>
                ${renderCheckboxList(roles, 'roleIds', [], r => escapeHtml(r.name || r.code || String(r.id)))}
            </div>
            <div class="form-item">
                <label style="display: flex; align-items: center; gap: 8px;">
                    <input type="checkbox" name="status" checked> ${t('common.enabled')}
                </label>
            </div>
        </form>
    `;
    
    showModal(t('users.form.createTitle'), content, async () => {
        const form = document.getElementById('addUserForm');
        const formData = new FormData(form);
        const selectedRoleIds = collectCheckedIds(form, 'roleIds');
        
        const data = {
            username: formData.get('username'),
            password: formData.get('password'),
            realName: formData.get('realName') || null,
            email: formData.get('email') || null,
            phone: formData.get('phone') || null,
            extra: collectUserFormExtra(form, false),
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
                notify(t('common.createOk'));
                loadUsers();
                closeModal();
            } else {
                const errorMessage = await handleApiError(response);
                notify((t('common.createFailed') + ': ') + errorMessage);
            }
        } catch (error) {
            notify((t('common.createFailed') + ': ') + error.message);
        }
    });
}

// 编辑用户
async function editUser(id) {
    try {
        const response = await fetch(`/api/user/${id}`);
        if (!response.ok) {
            notify(t('users.msg.detailFailed'));
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
                    <label>${t('users.form.username')}${REQUIRED_MARK}</label>
                    <input type="text" name="username" value="${escapeHtml(user.username || '')}" required>
                </div>
                <div class="form-item">
                    <label>${t('users.form.passwordKeepLabel')}</label>
                    <input type="password" name="password" placeholder="${t('users.form.passwordKeep')}">
                </div>
                <div class="form-item">
                    <label>${t('users.form.realName')}</label>
                    <input type="text" name="realName" value="${escapeHtml(user.realName || '')}">
                </div>
                <div class="form-item">
                    <label>${t('users.form.email')}</label>
                    <input type="email" name="email" value="${escapeHtml(user.email || '')}">
                </div>
                <div class="form-item">
                    <label>${t('users.form.phone')}</label>
                    <input type="text" name="phone" value="${escapeHtml(user.phone || '')}">
                </div>
                ${renderUserFormExtensionFields(user)}
                <div class="form-item">
                    <label>${t('users.form.source')}</label>
                    <select name="source">${renderUserSourceOptions(user.source || 'local')}</select>
                </div>
                <div class="form-item">
                    <label>${t('users.form.roles')}</label>
                    ${renderCheckboxList(roles, 'roleIds', user.roleIds || [], r => escapeHtml(r.name || r.code || String(r.id)))}
                </div>
                <div class="form-item">
                    <label style="display: flex; align-items: center; gap: 8px;">
                        <input type="checkbox" name="status" ${user.status === 1 ? 'checked' : ''}> ${t('common.enabled')}
                    </label>
                </div>
            </form>
        `;
        
        showModal(t('users.form.editTitle'), content, async () => {
            const form = document.getElementById('editUserForm');
            const formData = new FormData(form);
            const selectedRoleIds = collectCheckedIds(form, 'roleIds');
            
            const data = {
                username: formData.get('username'),
                realName: formData.get('realName') || null,
                email: formData.get('email') || null,
                phone: formData.get('phone') || null,
                extra: collectUserFormExtra(form, true),
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
                    notify(t('common.updatedOk'));
                    loadUsers();
                    closeModal();
                } else {
                    const errorMessage = await handleApiError(response);
                    notify((t('common.updatedFailed') + ': ') + errorMessage);
                }
            } catch (error) {
                notify((t('common.updatedFailed') + ': ') + error.message);
            }
        });
    } catch (error) {
        notify((t('users.msg.detailFailed') + ': ') + error.message);
    }
}

// 删除用户
async function deleteUser(id) {
    if (!await confirmDialog(t('users.msg.deleteConfirm'))) {
        return;
    }
    
    try {
        const response = await fetch(`/api/user/${id}`, { method: 'DELETE' });
        if (response.ok) {
            notify(t('common.deleteOk'));
            loadUsers();
        } else {
            notify(t('common.deleteFailed'));
        }
    } catch (error) {
        notify(t('common.deleteFailed'));
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
    
    roleList.innerHTML = ('<tr><td colspan="6" style="text-align: center; padding: 40px; color: var(--text-4);">' + t('users.role.empty') + '</td></tr>');
}

// 渲染角色列表
function renderRoleList(roles) {
    const roleList = document.getElementById('roleList');
    if (!roleList) return;
    
    roleList.innerHTML = roles.map(role => {
        const permissionCount = role.permissions ? role.permissions.length : 0;
        const statusText = role.status === 1 ? ('<span class="status-tag is-on">' + t('common.enabled') + '</span>') : ('<span class="status-tag is-off">' + t('common.disabled') + '</span>');
        
        return `
            <tr>
                <td>${escapeHtml(role.name || '-')}</td>
                <td>${escapeHtml(role.code || '-')}</td>
                <td>${escapeHtml(role.description || '-')}</td>
                <td>${permissionCount}</td>
                <td>${statusText}</td>
                <td>
                    <button class="btn-edit" onclick="editRole(${role.id})">${t('common.modify')}</button>
                    <button class="btn-danger" onclick="deleteRole(${role.id})">${t('common.delete')}</button>
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
                <label>${t('users.role.name')}${REQUIRED_MARK}</label>
                <input type="text" name="name" placeholder="${t('users.role.namePlaceholder')}" required>
            </div>
            <div class="form-item">
                <label>${t('users.role.code')}${REQUIRED_MARK}</label>
                <input type="text" name="code" placeholder="${t('users.role.codePlaceholder')}" required>
            </div>
            <div class="form-item">
                <label>${t('common.description')}</label>
                <textarea name="description" rows="2" placeholder="${t('users.role.descriptionPlaceholder')}"></textarea>
            </div>
            <div class="form-item">
                <label>${t('users.role.permissions')}</label>
                ${renderCheckboxList(permissions, 'permissionIds', [], p =>
                    `${escapeHtml(p.name || '')} <span class="perm-code">(${escapeHtml(p.code || '')})</span>`)}
            </div>
            <div class="form-item">
                <label style="display: flex; align-items: center; gap: 8px;">
                    <input type="checkbox" name="status" checked> ${t('common.enabled')}
                </label>
            </div>
        </form>
    `;
    
    showModal(t('users.role.createTitle'), content, async () => {
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
                notify(t('common.createOk'));
                loadRoles();
                closeModal();
            } else {
                const errorMessage = await handleApiError(response);
                notify((t('common.createFailed') + ': ') + errorMessage);
            }
        } catch (error) {
            notify((t('common.createFailed') + ': ') + error.message);
        }
    });
}

// 编辑角色
async function editRole(id) {
    try {
        const response = await fetch(`/api/role/${id}`);
        if (!response.ok) {
            notify(t('users.role.detailFailed'));
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
                    <label>${t('users.role.name')}${REQUIRED_MARK}</label>
                    <input type="text" name="name" value="${escapeHtml(role.name || '')}" required>
                </div>
                <div class="form-item">
                    <label>${t('users.role.code')}${REQUIRED_MARK}</label>
                    <input type="text" name="code" value="${escapeHtml(role.code || '')}" required>
                </div>
                <div class="form-item">
                    <label>${t('common.description')}</label>
                    <textarea name="description" rows="2">${escapeHtml(role.description || '')}</textarea>
                </div>
                <div class="form-item">
                    <label>${t('users.role.permissions')}</label>
                    ${renderCheckboxList(permissions, 'permissionIds', role.permissionIds || [], p =>
                        `${escapeHtml(p.name || '')} <span class="perm-code">(${escapeHtml(p.code || '')})</span>`)}
                </div>
                <div class="form-item">
                    <label style="display: flex; align-items: center; gap: 8px;">
                        <input type="checkbox" name="status" ${role.status === 1 ? 'checked' : ''}> ${t('common.enabled')}
                    </label>
                </div>
            </form>
        `;
        
        showModal(t('users.role.editTitle'), content, async () => {
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
                    notify(t('common.updatedOk'));
                    loadRoles();
                    closeModal();
                } else {
                    const errorMessage = await handleApiError(response);
                    notify((t('common.updatedFailed') + ': ') + errorMessage);
                }
            } catch (error) {
                notify((t('common.updatedFailed') + ': ') + error.message);
            }
        });
    } catch (error) {
        notify((t('users.role.detailFailed') + ': ') + error.message);
    }
}

// 删除角色
async function deleteRole(id) {
    if (!await confirmDialog(t('users.role.deleteConfirm'))) {
        return;
    }
    
    try {
        const response = await fetch(`/api/role/${id}`, { method: 'DELETE' });
        if (response.ok) {
            notify(t('common.deleteOk'));
            loadRoles();
        } else {
            const errorMessage = await handleApiError(response);
            notify((t('common.deleteFailed') + ': ') + errorMessage);
        }
    } catch (error) {
        notify(t('common.deleteFailed'));
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
    
    permissionList.innerHTML = ('<tr><td colspan="5" style="text-align: center; padding: 40px; color: var(--text-4);">' + t('users.permission.empty') + '</td></tr>');
}

// 渲染权限列表
function renderPermissionList(permissions) {
    const permissionList = document.getElementById('permissionList');
    if (!permissionList) return;
    
    permissionList.innerHTML = permissions.map(permission => {
        const statusText = permission.status === 1 ? ('<span class="status-tag is-on">' + t('common.enabled') + '</span>') : ('<span class="status-tag is-off">' + t('common.disabled') + '</span>');
        return `
            <tr>
                <td>${escapeHtml(permission.name || '-')}</td>
                <td>${escapeHtml(permission.code || '-')}</td>
                <td>${escapeHtml(permission.description || '-')}</td>
                <td>${statusText}</td>
                <td>
                    <button class="btn-edit" onclick="editPermission(${permission.id})">${t('common.modify')}</button>
                    <button class="btn-danger" onclick="deletePermission(${permission.id})">${t('common.delete')}</button>
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
                <label>${t('users.permission.name')}${REQUIRED_MARK}</label>
                <input type="text" name="name" placeholder="${t('users.permission.namePlaceholder')}" required>
            </div>
            <div class="form-item">
                <label>${t('users.permission.code')}${REQUIRED_MARK}</label>
                <input type="text" name="code" placeholder="${t('users.permission.codePlaceholder')}" required>
            </div>
            <div class="form-item">
                <label>${t('users.permission.parent')}</label>
                <select name="parentId">
                    <option value="0">${t('users.permission.noParent')}</option>
                    ${permissions.map(p => `<option value="${p.id}">${escapeHtml(p.name)}</option>`).join('')}
                </select>
            </div>
            <div class="form-item">
                <label>${t('common.description')}</label>
                <textarea name="description" rows="2" placeholder="${t('users.permission.descriptionPlaceholder')}"></textarea>
            </div>
            <div class="form-item">
                <label style="display: flex; align-items: center; gap: 8px;">
                    <input type="checkbox" name="status" checked> ${t('common.enabled')}
                </label>
            </div>
        </form>
    `;
    
    showModal(t('users.permission.createTitle'), content, async () => {
        const form = document.getElementById('addPermissionForm');
        const formData = new FormData(form);
        
        const parentIdStr = formData.get('parentId');
        const parentId = parentIdStr && parentIdStr !== '0' ? parseInt(parentIdStr) : 0;
        
        const data = {
            name: formData.get('name'),
            code: formData.get('code'),
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
                notify(t('common.createOk'));
                loadPermissions();
                closeModal();
            } else {
                const errorMessage = await handleApiError(response);
                notify((t('common.createFailed') + ': ') + errorMessage);
            }
        } catch (error) {
            notify((t('common.createFailed') + ': ') + error.message);
        }
    });
}

// 编辑权限
async function editPermission(id) {
    try {
        const response = await fetch(`/api/permission/${id}`);
        if (!response.ok) {
            notify(t('users.permission.detailFailed'));
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
                    <label>${t('users.permission.name')}${REQUIRED_MARK}</label>
                    <input type="text" name="name" value="${escapeHtml(permission.name || '')}" required>
                </div>
                <div class="form-item">
                    <label>${t('users.permission.code')}${REQUIRED_MARK}</label>
                    <input type="text" name="code" value="${escapeHtml(permission.code || '')}" required>
                </div>
                <div class="form-item">
                    <label>${t('users.permission.parent')}</label>
                    <select name="parentId">
                        <option value="0">${t('users.permission.noParent')}</option>
                        ${permissions.filter(p => p.id !== permission.id).map(p => `<option value="${p.id}" ${parentId === p.id ? 'selected' : ''}>${escapeHtml(p.name)}</option>`).join('')}
                    </select>
                </div>
                <div class="form-item">
                    <label>${t('common.description')}</label>
                    <textarea name="description" rows="2">${escapeHtml(permission.description || '')}</textarea>
                </div>
                <div class="form-item">
                    <label style="display: flex; align-items: center; gap: 8px;">
                        <input type="checkbox" name="status" ${permission.status === 1 ? 'checked' : ''}> ${t('common.enabled')}
                    </label>
                </div>
            </form>
        `;
        
        showModal(t('users.permission.editTitle'), content, async () => {
            const form = document.getElementById('editPermissionForm');
            const formData = new FormData(form);
            
            const parentIdStr = formData.get('parentId');
            const newParentId = parentIdStr && parentIdStr !== '0' ? parseInt(parentIdStr) : 0;
            
            const data = {
                name: formData.get('name'),
                code: formData.get('code'),
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
                    notify(t('common.updatedOk'));
                    loadPermissions();
                    closeModal();
                } else {
                    const errorMessage = await handleApiError(response);
                    notify((t('common.updatedFailed') + ': ') + errorMessage);
                }
            } catch (error) {
                notify((t('common.updatedFailed') + ': ') + error.message);
            }
        });
    } catch (error) {
        notify((t('users.permission.detailFailed') + ': ') + error.message);
    }
}

// 删除权限
async function deletePermission(id) {
    if (!await confirmDialog(t('users.permission.deleteConfirm'))) {
        return;
    }
    
    try {
        const response = await fetch(`/api/permission/${id}`, { method: 'DELETE' });
        if (response.ok) {
            notify(t('common.deleteOk'));
            loadPermissions();
        } else {
            const errorMessage = await handleApiError(response);
            notify((t('common.deleteFailed') + ': ') + errorMessage);
        }
    } catch (error) {
        notify(t('common.deleteFailed'));
    }
}

window.renderCheckboxList = renderCheckboxList;
window.collectCheckedIds = collectCheckedIds;


