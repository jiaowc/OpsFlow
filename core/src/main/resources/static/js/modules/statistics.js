// ============================================
// 工作台 / 统计概览
// ============================================

async function loadStatistics() {
    const trendEl = document.getElementById('overviewBuildTrend');
    const funnelEl = document.getElementById('overviewDeployFunnel');
    const runningEl = document.getElementById('overviewRunningList');
    const failureEl = document.getElementById('overviewFailureList');

    try {
        const response = await fetch('/api/statistics/overview');
        if (!response.ok) {
            throw new Error(await response.text() || response.statusText);
        }
        const data = await response.json();
        renderOverviewAction(data.action || {});
        renderOverviewViews(data.views || []);
        if (trendEl) {
            trendEl.innerHTML = renderOverviewTrend(data.buildTrend || []);
        }
        // 待审批数和发布漏斗只有扩展提供时才有值，为 null 时整块不显示
        toggleOverviewBlock('overviewDeployFunnelCard', data.deployFunnel != null);
        if (funnelEl && data.deployFunnel != null) {
            funnelEl.innerHTML = renderOverviewFunnel(data.deployFunnel);
        }
        if (runningEl) {
            runningEl.innerHTML = renderOverviewItems(data.runningJobs || [], t('statistics.running.empty'));
        }
        if (failureEl) {
            failureEl.innerHTML = renderOverviewItems(data.recentFailures || [], t('statistics.failures.empty'));
        }
        bindOverviewKpiClicks();
    } catch (error) {
        console.error('Load statistics error:', error);
        if (trendEl) {
            trendEl.innerHTML = ('<div class="stats-list-empty">' + t('statistics.loadFailed') + '</div>');
        }
        if (funnelEl) {
            funnelEl.innerHTML = ('<div class="stats-list-empty">' + t('statistics.loadFailed') + '</div>');
        }
        if (runningEl) {
            runningEl.innerHTML = ('<div class="stats-list-empty">' + t('statistics.loadFailed') + '</div>');
        }
        if (failureEl) {
            failureEl.innerHTML = ('<div class="stats-list-empty">' + t('statistics.loadFailed') + '</div>');
        }
    }
}
window.loadStatistics = loadStatistics;

function toggleOverviewBlock(id, visible) {
    const el = document.getElementById(id);
    if (el) {
        el.style.display = visible ? '' : 'none';
    }
}

function renderOverviewAction(action) {
    toggleOverviewBlock('kpiPendingApprovals', action.pendingApprovals != null);
    setText('statPendingApprovals', action.pendingApprovals);
    setText('statRunningJobs', action.runningJobs);
    setText('statTodayFailed', action.todayFailed);
    setText('statOfflineNodes', action.offlineNodes);

    const todayHint = document.getElementById('statTodayHint');
    if (todayHint) {
        const rate = action.todaySuccessRate != null ? t('statistics.today.rate', { rate: action.todaySuccessRate }) : t('statistics.today.noBuilds');
        todayHint.textContent = t('statistics.today.hint', { count: action.todayBuilds || 0, rate: rate });
    }
    const nodesHint = document.getElementById('statNodesHint');
    if (nodesHint) {
        nodesHint.textContent = t('statistics.nodes.hint', { count: action.totalNodes || 0 });
    }

    const failedEl = document.getElementById('statTodayFailed');
    if (failedEl) {
        failedEl.classList.toggle('overview-kpi-danger', Number(action.todayFailed || 0) > 0);
    }
    const offlineEl = document.getElementById('statOfflineNodes');
    if (offlineEl) {
        offlineEl.classList.toggle('overview-kpi-danger', Number(action.offlineNodes || 0) > 0);
    }
    const pendingEl = document.getElementById('statPendingApprovals');
    if (pendingEl) {
        pendingEl.classList.toggle('overview-kpi-warn', Number(action.pendingApprovals || 0) > 0);
    }
}

function renderOverviewViews(views) {
    const box = document.getElementById('overviewViews');
    if (!box) return;
    if (!views.length) {
        box.style.display = 'none';
        box.innerHTML = '';
        return;
    }
    box.style.display = 'flex';
    box.innerHTML = `
        <div class="overview-views-label">${t('menu.pipeline')}</div>
        <div class="overview-views-list">
            ${views.map(v => `
                <button type="button" class="overview-view-chip"
                    onclick="window.jumpOverviewSection('pipeline', ${v.id != null ? v.id : 'null'})">
                    ${safeText(v.name || '-')}
                    <span>${safeText(v.envName || '')}</span>
                </button>
            `).join('')}
        </div>
    `;
}

function renderOverviewTrend(days) {
    if (!days.length) {
        return ('<div class="stats-list-empty">' + t('statistics.trend.empty') + '</div>');
    }
    const max = Math.max(1, ...days.map(d => Number(d.total || 0)));
    return `
        <div class="overview-trend-bars">
            ${days.map(d => {
                const total = Number(d.total || 0);
                const success = Number(d.success || 0);
                const failed = Number(d.failed || 0);
                const h = Math.max(total > 0 ? 8 : 2, Math.round((total / max) * 72));
                const successH = total > 0 ? Math.round((success / total) * h) : 0;
                const failedH = total > 0 ? Math.max(0, h - successH) : 0;
                return `
                    <div class="overview-trend-col" title="${safeText(t('statistics.trend.tooltip', { date: d.date, success: success, failed: failed, total: total }))}">
                        <div class="overview-trend-stack" style="height:${h}px;">
                            <div class="overview-trend-failed" style="height:${failedH}px;"></div>
                            <div class="overview-trend-success" style="height:${successH}px;"></div>
                        </div>
                        <div class="overview-trend-date">${safeText(d.date)}</div>
                        <div class="overview-trend-total">${total}</div>
                    </div>
                `;
            }).join('')}
        </div>
        <div class="overview-trend-legend">
            <span><i class="lg success"></i>${t('statistics.status.success')}</span>
            <span><i class="lg failed"></i>${t('statistics.status.failed')}</span>
        </div>
    `;
}

function renderOverviewFunnel(funnel) {
    const rows = [
        { label: t('statistics.funnel.pendingApproval'), value: funnel.pendingApproval || 0, tone: 'warn' },
        { label: t('statistics.funnel.pendingDeploy'), value: funnel.approvedPending || 0, tone: 'info' },
        { label: t('statistics.status.deploying'), value: funnel.deploying || 0, tone: 'running' },
        { label: t('statistics.status.success'), value: funnel.success || 0, tone: 'success' },
        { label: t('statistics.status.failed'), value: funnel.failed || 0, tone: 'failed' }
    ];
    const max = Math.max(1, ...rows.map(r => r.value));
    return `
        <div class="overview-funnel-list">
            ${rows.map(r => `
                <div class="overview-funnel-row">
                    <div class="overview-funnel-label">${escapeHtml(r.label)}</div>
                    <div class="overview-funnel-bar-wrap">
                        <div class="overview-funnel-bar tone-${escapeHtml(r.tone)}" style="width:${Math.max(r.value > 0 ? 6 : 0, Math.round(r.value * 100 / max))}%;"></div>
                    </div>
                    <div class="overview-funnel-value">${escapeHtml(r.value)}</div>
                </div>
            `).join('')}
        </div>
        <div class="overview-funnel-note">${t('statistics.funnel.note')}</div>
    `;
}

function renderOverviewItems(items, emptyText) {
    if (!items.length) {
        return `<div class="stats-list-empty">${escapeHtml(emptyText)}</div>`;
    }
    return items.map(item => {
        // 会拼进内联事件的 JS 字符串里，只保留页面标识允许的字符
        const section = String(item.linkSection || 'pipeline').replace(/[^\w-]/g, '');
        const jobId = item.type === 'job' ? item.id : null;
        return `
            <div class="stats-list-item overview-clickable"
                 onclick="window.jumpOverviewSection('${/* html-safe: 上面已过滤为字母数字和连字符 */ section}', null, ${jobId != null ? jobId : 'null'})">
                <div>
                    <div class="stats-list-title">${safeText(item.title || '-')}</div>
                    <div class="stats-list-meta">${safeText(item.meta || '-')}</div>
                </div>
                <div class="stats-list-side">
                    <span class="stats-status-badge ${getStatsStatusClass(item.status)}">${/* html-safe: 固定文案，兜底值已用 safeText 转义 */ formatStatsStatus(item.status)}</span>
                    <span class="stats-list-time">${safeText(item.timeText || '-')}</span>
                </div>
            </div>
        `;
    }).join('');
}

function bindOverviewKpiClicks() {
    const grid = document.getElementById('overviewActionGrid');
    if (!grid || grid.dataset.bound === 'true') return;
    grid.dataset.bound = 'true';
    grid.addEventListener('click', (event) => {
        const btn = event.target.closest('.overview-kpi[data-jump]');
        if (!btn) return;
        window.jumpOverviewSection(btn.getAttribute('data-jump'));
    });
}

function jumpOverviewSection(sectionId, viewId, jobId) {
    if (typeof showSection !== 'function') {
        return;
    }
    const menuItem = document.querySelector(`.menu-item[data-section="${sectionId}"]`);
    showSection(sectionId, menuItem);
    if (sectionId === 'pipeline' && viewId != null && typeof window.selectPipelineView === 'function') {
        setTimeout(() => window.selectPipelineView(viewId), 80);
    }
    if (sectionId === 'pipeline' && jobId != null && typeof window.showPipelineDetail === 'function') {
        setTimeout(() => window.showPipelineDetail(jobId, { focusBuild: true }), 120);
    }
}
window.jumpOverviewSection = jumpOverviewSection;

function setText(id, value) {
    const el = document.getElementById(id);
    if (el) {
        el.textContent = value == null ? '-' : String(value);
    }
}

function formatStatsStatus(status) {
    const upper = String(status || '').toUpperCase();
    return {
        SUCCESS: t('statistics.status.success'),
        FAILED: t('statistics.status.failed'),
        BUILDING: t('statistics.status.building'),
        DEPLOYING: t('statistics.status.deploying'),
        PENDING: t('statistics.status.pending')
    }[upper] || safeText(status || '-');
}

function getStatsStatusClass(status) {
    const upper = String(status || '').toUpperCase();
    if (upper === 'SUCCESS') return 'stats-status-success';
    if (upper === 'FAILED') return 'stats-status-failed';
    if (upper === 'BUILDING' || upper === 'DEPLOYING') return 'stats-status-running';
    return 'stats-status-pending';
}

// 统计页的空值显示为「-」；转义统一用 utils.js 的 escapeHtml
function safeText(value) {
    return escapeHtml(value == null ? '-' : value);
}
