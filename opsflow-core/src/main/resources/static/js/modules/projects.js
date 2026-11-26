// ============================================
// 项目管理模块：项目列表、项目信息、成员与角色
// ============================================

const PROJECT_ROLE_KEYS = {
    ADMIN: 'projects.role.admin',
    DEVELOPER: 'projects.role.developer',
    VIEWER: 'projects.role.viewer'
};

// 角色名随当前语言变化，所以每次取，不在加载时固定
function projectRoleLabel(role) {
    const key = PROJECT_ROLE_KEYS[role];
    return key ? t(key) : '';
}

let projectUserOptionsCache = null;

function renderProjectRoleOptions(selected) {
    return Object.keys(PROJECT_ROLE_KEYS).map(role =>
        `<option value="${escapeHtml(role)}"${role === selected ? ' selected' : ''}>${escapeHtml(projectRoleLabel(role))}</option>`
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
    tbody.innerHTML = ('<tr><td colspan="7" style="text-align:center;padding:40px;color:var(--text-4);">' + t('common.loading') + '</td></tr>');
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
        tbody.innerHTML = ('<tr><td colspan="7" style="text-align:center;padding:40px;color:var(--text-4);">' + t('projects.empty') + '</td></tr>');
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
                <td><span class="project-role-badge project-role-${escapeHtml((p.myRole || '').toLowerCase())}">${escapeHtml(projectRoleLabel(p.myRole) || '-')}</span></td>
                <td>${p.memberCount || 0}</td>
                <td>${p.serviceCount || 0}</td>
                <td>
                    <div class="table-actions">
                        <button class="btn-secondary btn-sm" onclick="showProjectMembersModal(${p.id})">${t('projects.members')}</button>
                        ${isAdmin ? `<button class="btn-edit btn-sm" onclick="showProjectFormModal(${p.id})">${t('common.edit')}</button>` : ''}
                        ${canManagePlatform ? `<button class="btn-danger btn-sm" onclick="deleteProject(${p.id})">${t('common.delete')}</button>` : ''}
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
        notify((t('projects.msg.loadUsersFailed') + ': ') + e.message);
        return;
    }
    const ownerId = project.ownerUserId != null ? String(project.ownerUserId) : '';
    const content = `
        <form id="projectForm">
            <div class="form-item">
                <label>${t('projects.form.name')}${REQUIRED_MARK}</label>
                <input type="text" name="name" maxlength="100" value="${escapeHtml(project.name || '')}" required>
            </div>
            <div class="form-item">
                <label>${t('common.description')}</label>
                <textarea name="description" rows="3">${escapeHtml(project.description || '')}</textarea>
            </div>
            <div class="form-item">
                <label>${t('projects.form.owner')}</label>
                <select name="ownerUserId">
                    <option value="">${t('common.notSet')}</option>
                    ${users.map(u => `<option value="${u.id}"${String(u.id) === ownerId ? ' selected' : ''}>${escapeHtml(u.name)}</option>`).join('')}
                </select>
                <small style="color:#6b7280;">${t('projects.form.ownerHint')}</small>
            </div>
        </form>
    `;
    showModal(projectId ? t('projects.form.editTitle') : t('projects.form.createTitle'), content, async () => {
        const form = document.getElementById('projectForm');
        const name = form.querySelector('[name="name"]').value.trim();
        if (!name) {
            notify(t('projects.msg.nameRequired'));
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
            notify((t('common.saveFailed') + ': ') + e.message);
        }
    });
}
window.showProjectFormModal = showProjectFormModal;

async function deleteProject(projectId) {
    if (!await confirmDialog(t('projects.msg.deleteConfirm'))) return;
    try {
        await projectRequest(`/api/project/${projectId}`, { method: 'DELETE' });
        loadProjects();
    } catch (e) {
        notify((t('common.deleteFailed') + ': ') + e.message);
    }
}
window.deleteProject = deleteProject;

async function showProjectMembersModal(projectId) {
    const projects = await loadProjectOptions();
    const project = projects.find(p => p.id === projectId) || {};
    const isAdmin = project.myRole === 'ADMIN';
    showModal(`${t('projects.members.title')} - ${escapeHtml(project.name || '')}`, `
        <div id="projectMemberPanel" data-project-id="${Number(projectId)}" data-admin="${isAdmin ? '1' : ''}">${t('common.loading')}</div>
        <div style="color:#6b7280;font-size:12px;margin-top:12px;line-height:1.7;">
            ${t('projects.members.roleHint')}
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
                <input type="text" placeholder="${t('projects.members.searchPlaceholder')}" oninput="filterProjectMemberCandidates(this)">
                <div id="projectMemberCandidates" class="checkbox-list" style="max-height:180px;overflow:auto;">
                    ${candidates.map(u => `
                        <label class="checkbox-list-item" data-search="${escapeHtml((u.name || '').toLowerCase())}">
                            <input type="checkbox" name="projectMemberUser" value="${u.id}">
                            <span>${escapeHtml(u.name)}</span>
                        </label>`).join('')}
                </div>
                <div style="display:flex;gap:8px;align-items:center;">
                    <select id="projectMemberRole">${renderProjectRoleOptions('DEVELOPER')}</select>
                    <button type="button" class="btn-primary btn-sm" onclick="addProjectMember(${Number(projectId)})">${t('projects.members.addSelected')}</button>
                </div>
            </div>` : '';
    }
    const rows = members.length ? members.map(m => `
        <tr>
            <td>${escapeHtml(m.name)}</td>
            <td>${isAdmin
                ? `<select onchange="saveProjectMemberRole(${Number(projectId)}, ${m.userId}, this.value)">${renderProjectRoleOptions(m.role)}</select>`
                : escapeHtml(projectRoleLabel(m.role) || m.role)}</td>
            <td>${isAdmin ? `<button type="button" class="btn-danger btn-sm" onclick="removeProjectMember(${Number(projectId)}, ${m.userId})">${t('common.remove')}</button>` : ''}</td>
        </tr>`).join('')
        : ('<tr><td colspan="3" style="text-align:center;color:var(--text-4);padding:20px;">' + t('projects.members.empty') + '</td></tr>');
    panel.innerHTML = `
        ${addRow}
        <table class="data-table project-member-table">
            <thead><tr><th>${t('projects.members')}</th><th style="width:140px;">${t('projects.members.role')}</th><th style="width:80px;"></th></tr></thead>
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
        notify(t('projects.msg.selectUsers'));
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
        notify((t('common.addFailed') + ': ') + e.message);
    }
}
window.addProjectMember = addProjectMember;

async function saveProjectMemberRole(projectId, userId, role) {
    try {
        await saveProjectMember(projectId, userId, role);
    } catch (e) {
        notify((t('common.updateFailed') + ': ') + e.message);
    }
    renderProjectMembers(projectId);
}
window.saveProjectMemberRole = saveProjectMemberRole;

async function removeProjectMember(projectId, userId) {
    if (!await confirmDialog(t('projects.msg.removeConfirm'))) return;
    try {
        await projectRequest(`/api/project/${projectId}/members/${userId}`, { method: 'DELETE' });
        await renderProjectMembers(projectId);
        loadProjects();
    } catch (e) {
        notify((t('projects.msg.removeFailed') + ': ') + e.message);
    }
}
window.removeProjectMember = removeProjectMember;
