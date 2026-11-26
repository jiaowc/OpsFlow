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
    list.innerHTML = `<div class="inbox-empty">${escapeHtml(t('inbox.loading'))}</div>`;
    try {
        const resp = await fetch('/api/inbox/list?limit=30');
        if (!resp.ok) {
            list.innerHTML = `<div class="inbox-empty">${escapeHtml(t('inbox.loadFailed'))}</div>`;
            return;
        }
        const messages = await resp.json() || [];
        if (!messages.length) {
            list.innerHTML = `<div class="inbox-empty">${escapeHtml(t('inbox.empty'))}</div>`;
            return;
        }
        list.innerHTML = messages.map(m => {
            const unread = m.readFlag === 0 || m.readFlag === '0';
            const time = m.createTime ? new Date(m.createTime).toLocaleString(getDateLocale()) : '';
            const content = escapeHtml(m.content || '').replace(/\n/g, '<br>');
            return `
                <div class="inbox-item ${unread ? 'unread' : ''}" data-id="${m.id}" onclick="openInboxMessage(${m.id}, ${escapeHtml(JSON.stringify(m.linkPath || ''))})">
                    <div class="inbox-item-title">${escapeHtml(m.title || t('inbox.defaultTitle'))}</div>
                    <div class="inbox-item-content">${content}</div>
                    <div class="inbox-item-time">${time}</div>
                </div>
            `;
        }).join('');
    } catch (e) {
        list.innerHTML = `<div class="inbox-empty">${escapeHtml(t('inbox.loadFailed'))}</div>`;
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
        const section = String(linkPath).replace(/^\//, '') || 'statistics';
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
        notify(t('common.failedWith', { reason: e.message || e }), 'error');
    }
}
window.markAllInboxRead = markAllInboxRead;

document.addEventListener('click', function () {
    const panel = document.getElementById('inboxDropdown');
    if (panel) panel.style.display = 'none';
});

document.addEventListener('DOMContentLoaded', function () {
    refreshInboxBadge();
    setInterval(() => {
        if (document.hidden) return;
        refreshInboxBadge();
    }, 60000);
    if (typeof window.onPageVisible === 'function') {
        window.onPageVisible(refreshInboxBadge);
    }
});
