// ============================================
// 站内信（审批通知）
// ============================================

async function refreshInboxBadge() {
    const badge = document.getElementById('inboxUnreadBadge');
    if (!badge) return;
    try {
        const resp = await fetch('/api/inbox/unread-count');
        if (!resp.ok) return;
        const data = await resp.json();
        const count = data.count || 0;
        if (count > 0) {
            badge.textContent = count > 99 ? '99+' : String(count);
            badge.style.display = 'inline-flex';
        } else {
            badge.textContent = '';
            badge.style.display = 'none';
        }
    } catch (e) {
        console.warn('Load inbox unread count failed', e);
    }
}
window.refreshInboxBadge = refreshInboxBadge;

function toggleInboxDropdown(event) {
    if (event) event.stopPropagation();
    const panel = document.getElementById('inboxDropdown');
    if (!panel) return;
    const visible = panel.style.display === 'block';
    if (visible) {
        panel.style.display = 'none';
        return;
    }
    panel.style.display = 'block';
    loadInboxMessages();
}
window.toggleInboxDropdown = toggleInboxDropdown;

async function loadInboxMessages() {
    const list = document.getElementById('inboxMessageList');
    if (!list) return;
    list.innerHTML = '<div class="inbox-empty">加载中...</div>';
    try {
        const resp = await fetch('/api/inbox/list?limit=30');
        if (!resp.ok) {
            list.innerHTML = '<div class="inbox-empty">加载失败</div>';
            return;
        }
        const messages = await resp.json() || [];
        if (!messages.length) {
            list.innerHTML = '<div class="inbox-empty">暂无站内信</div>';
            return;
        }
        list.innerHTML = messages.map(m => {
            const unread = m.readFlag === 0 || m.readFlag === '0';
            const time = m.createTime ? new Date(m.createTime).toLocaleString('zh-CN') : '';
            const content = escapeHtml(m.content || '').replace(/\n/g, '<br>');
            return `
                <div class="inbox-item ${unread ? 'unread' : ''}" data-id="${m.id}" onclick="openInboxMessage(${m.id}, ${escapeHtml(JSON.stringify(m.linkPath || '/tasks'))})">
                    <div class="inbox-item-title">${escapeHtml(m.title || '通知')}</div>
                    <div class="inbox-item-content">${content}</div>
                    <div class="inbox-item-time">${time}</div>
                </div>
            `;
        }).join('');
    } catch (e) {
        list.innerHTML = '<div class="inbox-empty">加载失败</div>';
    }
}
window.loadInboxMessages = loadInboxMessages;

async function openInboxMessage(id, linkPath) {
    try {
        await fetch(`/api/inbox/${id}/read`, { method: 'POST' });
    } catch (e) { /* ignore */ }
    refreshInboxBadge();
    const panel = document.getElementById('inboxDropdown');
    if (panel) panel.style.display = 'none';
    if (linkPath) {
        const section = String(linkPath).replace(/^\//, '') || 'tasks';
        if (typeof showSection === 'function') {
            const menuItem = document.querySelector(`.menu-item[data-section="${section}"]`);
            showSection(section, menuItem);
        } else {
            window.location.href = linkPath.startsWith('/') ? linkPath : ('/' + linkPath);
        }
    }
}
window.openInboxMessage = openInboxMessage;

async function markAllInboxRead() {
    try {
        await fetch('/api/inbox/read-all', { method: 'POST' });
        await loadInboxMessages();
        refreshInboxBadge();
    } catch (e) {
        notify('操作失败: ' + (e.message || e));
    }
}
window.markAllInboxRead = markAllInboxRead;

document.addEventListener('click', function () {
    const panel = document.getElementById('inboxDropdown');
    if (panel) panel.style.display = 'none';
});

document.addEventListener('DOMContentLoaded', function () {
    refreshInboxBadge();
    setInterval(refreshInboxBadge, 60000);
});
