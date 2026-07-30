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
        if (funnelEl) {
            funnelEl.innerHTML = renderOverviewFunnel(data.deployFunnel || {});
        }
        if (runningEl) {
            runningEl.innerHTML = renderOverviewItems(data.runningJobs || [], '当前没有运行中的任务');
        }
        if (failureEl) {
            failureEl.innerHTML = renderOverviewItems(data.recentFailures || [], '最近没有失败记录');
        }
        bindOverviewKpiClicks();
    } catch (error) {
        console.error('Load statistics error:', error);
        if (trendEl) {
            trendEl.innerHTML = '<div class="stats-list-empty">统计数据加载失败</div>';
        }
        if (funnelEl) {
            funnelEl.innerHTML = '<div class="stats-list-empty">统计数据加载失败</div>';
        }
        if (runningEl) {
            runningEl.innerHTML = '<div class="stats-list-empty">统计数据加载失败</div>';
        }
        if (failureEl) {
            failureEl.innerHTML = '<div class="stats-list-empty">统计数据加载失败</div>';
        }
    }
}
window.loadStatistics = loadStatistics;

function renderOverviewAction(action) {
    setText('statPendingApprovals', action.pendingApprovals);
    setText('statRunningJobs', action.runningJobs);
    setText('statTodayFailed', action.todayFailed);
    setText('statOfflineNodes', action.offlineNodes);

    const todayHint = document.getElementById('statTodayHint');
    if (todayHint) {
        const rate = action.todaySuccessRate != null ? `成功率 ${action.todaySuccessRate}%` : '暂无已完成构建';
        todayHint.textContent = `今日 ${action.todayBuilds || 0} 次 · ${rate}`;
    }
    const nodesHint = document.getElementById('statNodesHint');
    if (nodesHint) {
        nodesHint.textContent = `共 ${action.totalNodes || 0} 个节点`;
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
        <div class="overview-views-label">流水线视图</div>
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
        return '<div class="stats-list-empty">暂无趋势数据</div>';
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
                    <div class="overview-trend-col" title="${safeText(d.date)}：成功 ${success} / 失败 ${failed} / 共 ${total}">
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
            <span><i class="lg success"></i>成功</span>
            <span><i class="lg failed"></i>失败</span>
        </div>
    `;
}

function renderOverviewFunnel(funnel) {
    const rows = [
        { label: '待审批', value: funnel.pendingApproval || 0, tone: 'warn' },
        { label: '待部署', value: funnel.approvedPending || 0, tone: 'info' },
        { label: '部署中', value: funnel.deploying || 0, tone: 'running' },
        { label: '成功', value: funnel.success || 0, tone: 'success' },
        { label: '失败', value: funnel.failed || 0, tone: 'failed' }
    ];
    const max = Math.max(1, ...rows.map(r => r.value));
    return `
        <div class="overview-funnel-list">
            ${rows.map(r => `
                <div class="overview-funnel-row">
                    <div class="overview-funnel-label">${r.label}</div>
                    <div class="overview-funnel-bar-wrap">
                        <div class="overview-funnel-bar tone-${r.tone}" style="width:${Math.max(r.value > 0 ? 6 : 0, Math.round(r.value * 100 / max))}%;"></div>
                    </div>
                    <div class="overview-funnel-value">${r.value}</div>
                </div>
            `).join('')}
        </div>
        <div class="overview-funnel-note">基于最近 500 条上线任务统计</div>
    `;
}

function renderOverviewItems(items, emptyText) {
    if (!items.length) {
        return `<div class="stats-list-empty">${emptyText}</div>`;
    }
    return items.map(item => {
        const section = item.linkSection || (item.type === 'deploy_task' ? 'tasks' : 'pipeline');
        const jobId = item.type === 'job' ? item.id : null;
        return `
            <div class="stats-list-item overview-clickable"
                 onclick="window.jumpOverviewSection('${section}', null, ${jobId != null ? jobId : 'null'})">
                <div>
                    <div class="stats-list-title">${safeText(item.title || '-')}</div>
                    <div class="stats-list-meta">${safeText(item.meta || '-')}</div>
                </div>
                <div class="stats-list-side">
                    <span class="stats-status-badge ${getStatsStatusClass(item.status)}">${formatStatsStatus(item.status)}</span>
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
        setTimeout(() => window.showPipelineDetail(jobId), 120);
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
        SUCCESS: '成功',
        FAILED: '失败',
        BUILDING: '构建中',
        DEPLOYING: '部署中',
        PENDING: '等待中'
    }[upper] || safeText(status || '-');
}

function getStatsStatusClass(status) {
    const upper = String(status || '').toUpperCase();
    if (upper === 'SUCCESS') return 'stats-status-success';
    if (upper === 'FAILED') return 'stats-status-failed';
    if (upper === 'BUILDING' || upper === 'DEPLOYING') return 'stats-status-running';
    return 'stats-status-pending';
}

function safeText(value) {
    return String(value == null ? '-' : value)
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;')
        .replace(/'/g, '&#39;');
}
