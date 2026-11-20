// ============================================
// 操作审计模块
// ============================================

const AUDIT_PAGE_SIZE = 20;
let auditCurrentPage = 1;
let auditModulesLoaded = false;

function toAuditTimeParam(value) {
    // datetime-local: 2026-11-01T10:30 → 2026-11-01 10:30:00
    return value ? value.replace('T', ' ') + ':00' : '';
}

async function loadAuditModules() {
    if (auditModulesLoaded) return;
    try {
        const resp = await fetch('/api/audit/modules');
        if (!resp.ok) return;
        const modules = await resp.json();
        const select = document.getElementById('auditModuleFilter');
        if (!select) return;
        modules.forEach(m => {
            const opt = document.createElement('option');
            opt.value = m;
            opt.textContent = m;
            select.appendChild(opt);
        });
        auditModulesLoaded = true;
    } catch (e) {
        console.error('Load audit modules error:', e);
    }
}

function loadAuditPage() {
    loadAuditModules();
    loadAuditLogs(auditCurrentPage);
}

async function loadAuditLogs(page) {
    const tbody = document.getElementById('auditList');
    if (!tbody) return;
    auditCurrentPage = page || 1;

    const params = new URLSearchParams({ page: auditCurrentPage, size: AUDIT_PAGE_SIZE });
    const user = document.getElementById('auditUserFilter').value.trim();
    const module = document.getElementById('auditModuleFilter').value;
    const success = document.getElementById('auditSuccessFilter').value;
    const start = toAuditTimeParam(document.getElementById('auditStartFilter').value);
    const end = toAuditTimeParam(document.getElementById('auditEndFilter').value);
    const keyword = document.getElementById('auditKeywordFilter').value.trim();
    if (user) params.set('username', user);
    if (module) params.set('module', module);
    if (success) params.set('success', success);
    if (start) params.set('start', start);
    if (end) params.set('end', end);
    if (keyword) params.set('keyword', keyword);

    try {
        const resp = await fetch('/api/audit/list?' + params.toString());
        if (!resp.ok) {
            const msg = typeof handleApiError === 'function' ? await handleApiError(resp) : ('HTTP ' + resp.status);
            tbody.innerHTML = `<tr><td colspan="9" style="text-align:center;padding:40px;color:#dc2626;">加载失败：${escapeHtml(msg)}</td></tr>`;
            return;
        }
        const data = await resp.json();
        renderAuditList(data.records || []);
        renderAuditPager(data.total || 0);
    } catch (e) {
        console.error('Load audit logs error:', e);
        tbody.innerHTML = '<tr><td colspan="9" style="text-align:center;padding:40px;color:#dc2626;">加载失败</td></tr>';
    }
}

function renderAuditList(records) {
    const tbody = document.getElementById('auditList');
    if (!records.length) {
        tbody.innerHTML = '<tr><td colspan="9" style="text-align:center;padding:40px;color:var(--text-4);">暂无审计记录</td></tr>';
        return;
    }
    tbody.innerHTML = records.map(r => {
        const ok = r.success === 1;
        const result = ok
            ? '<span style="color:#059669;">成功</span>'
            : '<span style="color:#dc2626;">失败</span>';
        const detail = [
            `<code>${escapeHtml(r.httpMethod)} ${escapeHtml(r.path)}</code>`,
            r.requestSummary ? `<div title="${escapeHtml(r.requestSummary)}">${escapeHtml(r.requestSummary.length > 160 ? r.requestSummary.slice(0, 160) + '…' : r.requestSummary)}</div>` : '',
            !ok && r.errorMessage ? `<div class="audit-error">${escapeHtml(r.errorMessage)}</div>` : ''
        ].join('');
        return `<tr>
            <td>${escapeHtml(formatDate(r.createTime))}</td>
            <td>${escapeHtml(r.username || '-')}</td>
            <td>${escapeHtml(r.module || '-')}</td>
            <td>${escapeHtml(r.action || '-')}</td>
            <td>${escapeHtml(r.targetId || '-')}</td>
            <td>${result}</td>
            <td class="audit-detail">${detail}</td>
            <td>${escapeHtml(r.clientIp || '-')}</td>
            <td>${r.durationMs != null ? r.durationMs + ' ms' : '-'}</td>
        </tr>`;
    }).join('');
}

function renderAuditPager(total) {
    const pager = document.getElementById('auditPager');
    if (!pager) return;
    const pages = Math.max(1, Math.ceil(total / AUDIT_PAGE_SIZE));
    pager.innerHTML = `
        <span>共 ${total} 条，第 ${auditCurrentPage} / ${pages} 页</span>
        <button class="btn-secondary" ${auditCurrentPage <= 1 ? 'disabled' : ''} onclick="loadAuditLogs(${auditCurrentPage - 1})">上一页</button>
        <button class="btn-secondary" ${auditCurrentPage >= pages ? 'disabled' : ''} onclick="loadAuditLogs(${auditCurrentPage + 1})">下一页</button>`;
}

window.loadAuditPage = loadAuditPage;
window.loadAuditLogs = loadAuditLogs;
