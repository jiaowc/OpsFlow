// ============================================
// 项目管理模块：项目列表、项目信息、成员与角色
// ============================================

const PROJECT_ROLE_LABELS = {
    ADMIN: '项目管理员',
    DEVELOPER: '开发',
    VIEWER: '只读'
};

let projectUserOptionsCache = null;

function renderProjectRoleOptions(selected) {
    return Object.keys(PROJECT_ROLE_LABELS).map(role =>
        `<option value="${role}"${role === selected ? ' selected' : ''}>${PROJECT_ROLE_LABELS[role]}</option>`
    ).join('');
}

async function projectRequest(url, options) {
    const resp = await fetch(url, options);
    if (!resp.ok) {
        const raw = await resp.text();
        let message = raw;
        try {
            message = JSON.parse(raw).message || raw;
        } catch (e) {
            // 非 JSON 错误体，直接展示原文
        }
        throw new Error(message || resp.statusText);
    }
    const text = await resp.text();
    return text ? JSON.parse(text) : null;
}

async function loadProjectUserOptions() {
    if (projectUserOptionsCache) return projectUserOptionsCache;
    projectUserOptionsCache = await projectRequest('/api/project/user-options');
    return projectUserOptionsCache || [];
}

async function loadProjects() {
    const tbody = document.getElementById('projectList');
    if (!tbody) return;
    tbody.innerHTML = '<tr><td colspan="7" style="text-align:center;padding:40px;color:var(--text-4);">加载中...</td></tr>';
    let projects;
    try {
        projects = await loadProjectOptions(true);
    } catch (e) {
        projects = [];
    }
    if (typeof refreshProjectSwitcher === 'function') {
        refreshProjectSwitcher();
    }
    if (!projects.length) {
        tbody.innerHTML = '<tr><td colspan="7" style="text-align:center;padding:40px;color:var(--text-4);">你还没有加入任何项目，请联系项目管理员</td></tr>';
        return;
    }
    const canManagePlatform = typeof hasPermission === 'function' && hasPermission('project:manage');
    tbody.innerHTML = projects.map(p => {
        const isAdmin = p.myRole === 'ADMIN';
        return `
            <tr>
                <td><strong>${escapeHtml(p.name)}</strong></td>
                <td style="color:#6b7280;">${escapeHtml(p.description || '-')}</td>
                <td>${escapeHtml(p.ownerName || '-')}</td>
                <td><span class="project-role-badge project-role-${escapeHtml((p.myRole || '').toLowerCase())}">${escapeHtml(PROJECT_ROLE_LABELS[p.myRole] || '-')}</span></td>
                <td>${p.memberCount || 0}</td>
                <td>${p.serviceCount || 0}</td>
                <td>
                    <div class="table-actions">
                        <button class="btn-secondary btn-sm" onclick="showProjectMembersModal(${p.id})">成员</button>
                        ${isAdmin ? `<button class="btn-edit btn-sm" onclick="showProjectFormModal(${p.id})">编辑</button>` : ''}
                        ${canManagePlatform ? `<button class="btn-danger btn-sm" onclick="deleteProject(${p.id})">删除</button>` : ''}
                    </div>
                </td>
            </tr>`;
    }).join('');
}
window.loadProjects = loadProjects;

async function showProjectFormModal(projectId) {
    const projects = projectId ? await loadProjectOptions() : [];
    const project = projectId ? (projects.find(p => p.id === projectId) || {}) : {};
    let users = [];
    try {
        users = await loadProjectUserOptions();
    } catch (e) {
        alert('加载用户列表失败: ' + e.message);
        return;
    }
    const ownerId = project.ownerUserId != null ? String(project.ownerUserId) : '';
    const content = `
        <form id="projectForm">
            <div class="form-item">
                <label>项目名称 *</label>
                <input type="text" name="name" maxlength="100" value="${escapeHtml(project.name || '')}" required>
            </div>
            <div class="form-item">
                <label>描述</label>
                <textarea name="description" rows="3">${escapeHtml(project.description || '')}</textarea>
            </div>
            <div class="form-item">
                <label>负责人</label>
                <select name="ownerUserId">
                    <option value="">不设置</option>
                    ${users.map(u => `<option value="${u.id}"${String(u.id) === ownerId ? ' selected' : ''}>${escapeHtml(u.name)}</option>`).join('')}
                </select>
                <small style="color:#6b7280;">负责人会自动成为项目管理员</small>
            </div>
        </form>
    `;
    showModal(projectId ? '编辑项目' : '新建项目', content, async () => {
        const form = document.getElementById('projectForm');
        const name = form.querySelector('[name="name"]').value.trim();
        if (!name) {
            alert('请输入项目名称');
            return;
        }
        const owner = form.querySelector('[name="ownerUserId"]').value;
        const body = {
            name,
            description: form.querySelector('[name="description"]').value,
            ownerUserId: owner ? parseInt(owner, 10) : null
        };
        try {
            await projectRequest(projectId ? `/api/project/${projectId}` : '/api/project/create', {
                method: projectId ? 'PUT' : 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(body)
            });
            closeModal();
            loadProjects();
        } catch (e) {
            alert('保存失败: ' + e.message);
        }
    });
}
window.showProjectFormModal = showProjectFormModal;

async function deleteProject(projectId) {
    if (!confirm('确定删除该项目吗？仅能删除没有服务和上线任务的空项目。')) return;
    try {
        await projectRequest(`/api/project/${projectId}`, { method: 'DELETE' });
        loadProjects();
    } catch (e) {
        alert('删除失败: ' + e.message);
    }
}
window.deleteProject = deleteProject;

async function showProjectMembersModal(projectId) {
    const projects = await loadProjectOptions();
    const project = projects.find(p => p.id === projectId) || {};
    const isAdmin = project.myRole === 'ADMIN';
    showModal(`项目成员 - ${escapeHtml(project.name || '')}`, `
        <div id="projectMemberPanel" data-project-id="${projectId}" data-admin="${isAdmin ? '1' : ''}">加载中...</div>
        <div style="color:#6b7280;font-size:12px;margin-top:12px;line-height:1.7;">
            项目管理员：管理成员与项目信息，并拥有开发权限；开发：构建、回滚、编辑流水线与服务、创建上线任务；只读：仅查看。
        </div>
    `);
    renderProjectMembers(projectId);
}
window.showProjectMembersModal = showProjectMembersModal;

async function renderProjectMembers(projectId) {
    const panel = document.getElementById('projectMemberPanel');
    if (!panel) return;
    const isAdmin = panel.dataset.admin === '1';
    let members;
    try {
        members = await projectRequest(`/api/project/${projectId}/members`) || [];
    } catch (e) {
        panel.innerHTML = `<div style="color:#dc2626;">${escapeHtml(e.message)}</div>`;
        return;
    }
    let addRow = '';
    if (isAdmin) {
        let users = [];
        try {
            users = await loadProjectUserOptions();
        } catch (e) {
            users = [];
        }
        const memberIds = new Set(members.map(m => String(m.userId)));
        const candidates = users.filter(u => !memberIds.has(String(u.id)));
        addRow = candidates.length ? `
            <div class="project-member-add" style="flex-direction:column;align-items:stretch;">
                <input type="text" placeholder="搜索用户，勾选后可一次添加多人" oninput="filterProjectMemberCandidates(this)">
                <div id="projectMemberCandidates" class="checkbox-list" style="max-height:180px;overflow:auto;">
                    ${candidates.map(u => `
                        <label class="checkbox-list-item" data-search="${escapeHtml((u.name || '').toLowerCase())}">
                            <input type="checkbox" name="projectMemberUser" value="${u.id}">
                            <span>${escapeHtml(u.name)}</span>
                        </label>`).join('')}
                </div>
                <div style="display:flex;gap:8px;align-items:center;">
                    <select id="projectMemberRole">${renderProjectRoleOptions('DEVELOPER')}</select>
                    <button type="button" class="btn-primary btn-sm" onclick="addProjectMember(${projectId})">添加选中的用户</button>
                </div>
            </div>` : '';
    }
    const rows = members.length ? members.map(m => `
        <tr>
            <td>${escapeHtml(m.name)}</td>
            <td>${isAdmin
                ? `<select onchange="saveProjectMemberRole(${projectId}, ${m.userId}, this.value)">${renderProjectRoleOptions(m.role)}</select>`
                : escapeHtml(PROJECT_ROLE_LABELS[m.role] || m.role)}</td>
            <td>${isAdmin ? `<button type="button" class="btn-danger btn-sm" onclick="removeProjectMember(${projectId}, ${m.userId})">移除</button>` : ''}</td>
        </tr>`).join('')
        : '<tr><td colspan="3" style="text-align:center;color:var(--text-4);padding:20px;">暂无成员</td></tr>';
    panel.innerHTML = `
        ${addRow}
        <table class="data-table project-member-table">
            <thead><tr><th>成员</th><th style="width:140px;">角色</th><th style="width:80px;"></th></tr></thead>
            <tbody>${rows}</tbody>
        </table>`;
}

async function saveProjectMember(projectId, userId, role) {
    await projectRequest(`/api/project/${projectId}/members`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ userId, role })
    });
}

function filterProjectMemberCandidates(input) {
    const keyword = (input.value || '').trim().toLowerCase();
    document.querySelectorAll('#projectMemberCandidates .checkbox-list-item').forEach(item => {
        item.style.display = !keyword || item.dataset.search.includes(keyword) ? '' : 'none';
    });
}
window.filterProjectMemberCandidates = filterProjectMemberCandidates;

async function addProjectMember(projectId) {
    const userIds = Array.from(document.querySelectorAll('#projectMemberCandidates input[name="projectMemberUser"]:checked'))
        .map(el => parseInt(el.value, 10));
    const role = document.getElementById('projectMemberRole')?.value;
    if (!userIds.length) {
        alert('请勾选要添加的用户');
        return;
    }
    try {
        await projectRequest(`/api/project/${projectId}/members`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ userIds, role })
        });
        await renderProjectMembers(projectId);
        loadProjects();
    } catch (e) {
        alert('添加失败: ' + e.message);
    }
}
window.addProjectMember = addProjectMember;

async function saveProjectMemberRole(projectId, userId, role) {
    try {
        await saveProjectMember(projectId, userId, role);
    } catch (e) {
        alert('修改失败: ' + e.message);
    }
    renderProjectMembers(projectId);
}
window.saveProjectMemberRole = saveProjectMemberRole;

async function removeProjectMember(projectId, userId) {
    if (!confirm('确定移除该成员吗？')) return;
    try {
        await projectRequest(`/api/project/${projectId}/members/${userId}`, { method: 'DELETE' });
        await renderProjectMembers(projectId);
        loadProjects();
    } catch (e) {
        alert('移除失败: ' + e.message);
    }
}
window.removeProjectMember = removeProjectMember;
