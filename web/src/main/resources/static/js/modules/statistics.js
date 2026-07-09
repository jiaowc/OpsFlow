// ============================================
// 统计信息模块
// ============================================

// 加载统计信息
async function loadStatistics() {
    const todayBuildCountEl = document.getElementById('todayBuildCount');
    const successCountEl = document.getElementById('successCount');
    const failedCountEl = document.getElementById('failedCount');
    const buildingCountEl = document.getElementById('buildingCount');
    const recentBuildsListEl = document.getElementById('recentBuildsList');
    const recentFailuresListEl = document.getElementById('recentFailuresList');

    try {
        const [jobsResp, tasksResp] = await Promise.all([
            fetch('/api/pipeline-run/jobs?page=1&pageSize=50'),
            fetch('/api/task/list')
        ]);

        const jobsData = jobsResp.ok ? await jobsResp.json() : { items: [] };
        const tasksData = tasksResp.ok ? await tasksResp.json() : [];

        const jobs = Array.isArray(jobsData?.items) ? jobsData.items : [];
        const tasks = Array.isArray(tasksData) ? tasksData : [];

        const today = new Date();
        today.setHours(0, 0, 0, 0);

        const todayBuilds = jobs.filter(job => {
            if (!job.createTimeText) return false;
            const nowYear = new Date().getFullYear();
            const fullText = `${nowYear}-${job.createTimeText.replace(' ', 'T')}`;
            const buildDate = new Date(fullText);
            if (Number.isNaN(buildDate.getTime())) return false;
            buildDate.setHours(0, 0, 0, 0);
            return buildDate.getTime() === today.getTime();
        });

        const successCount = jobs.filter(j => String(j.status || '').toUpperCase() === 'SUCCESS').length;
        const failedCount = jobs.filter(j => String(j.status || '').toUpperCase() === 'FAILED').length;
        const buildingCount = jobs.filter(j => ['BUILDING', 'DEPLOYING'].includes(String(j.status || '').toUpperCase())).length;

        if (todayBuildCountEl) todayBuildCountEl.textContent = String(todayBuilds.length);
        if (successCountEl) successCountEl.textContent = String(successCount);
        if (failedCountEl) failedCountEl.textContent = String(failedCount);
        if (buildingCountEl) buildingCountEl.textContent = String(buildingCount);

        if (recentBuildsListEl) {
            recentBuildsListEl.innerHTML = renderStatsJobList(jobs.slice(0, 5), '暂无构建记录');
        }

        if (recentFailuresListEl) {
            const failures = jobs.filter(j => String(j.status || '').toUpperCase() === 'FAILED');
            recentFailuresListEl.innerHTML = renderStatsFailureList(failures.slice(0, 5), tasks);
        }
    } catch (error) {
        console.error('Load statistics error:', error);
        if (recentBuildsListEl) {
            recentBuildsListEl.innerHTML = '<div class="stats-list-empty">统计数据加载失败</div>';
        }
        if (recentFailuresListEl) {
            recentFailuresListEl.innerHTML = '<div class="stats-list-empty">统计数据加载失败</div>';
        }
    }
}

function renderStatsJobList(jobs, emptyText) {
    if (!jobs.length) {
        return `<div class="stats-list-empty">${emptyText}</div>`;
    }
    return jobs.map(job => `
        <div class="stats-list-item">
            <div>
                <div class="stats-list-title">${safeText(job.taskName || job.name || job.jobNumber || '-')}</div>
                <div class="stats-list-meta">
                    ${safeText(job.serviceName || '-')} / ${safeText(job.envName || '-')} / ${safeText(job.branch || '-')}
                </div>
            </div>
            <div class="stats-list-side">
                <span class="stats-status-badge ${getStatsStatusClass(job.status)}">${formatStatsStatus(job.status)}</span>
                <span class="stats-list-time">${safeText(job.createTimeText || '-')}</span>
            </div>
        </div>
    `).join('');
}

function renderStatsFailureList(failures, tasks) {
    if (failures.length) {
        return failures.map(job => `
            <div class="stats-list-item">
                <div>
                    <div class="stats-list-title">${safeText(job.taskName || job.name || job.jobNumber || '-')}</div>
                    <div class="stats-list-meta">${safeText(job.serviceName || '-')} / ${safeText(job.envName || '-')}</div>
                </div>
                <div class="stats-list-side">
                    <span class="stats-status-badge stats-status-failed">失败</span>
                    <span class="stats-list-time">${safeText(job.createTimeText || '-')}</span>
                </div>
            </div>
        `).join('');
    }

    const failedTasks = tasks.filter(t => String(t.taskStatus || t.status || '').toLowerCase() === 'failed').slice(0, 5);
    if (!failedTasks.length) {
        return '<div class="stats-list-empty">最近没有失败记录</div>';
    }

    return failedTasks.map(task => `
        <div class="stats-list-item">
            <div>
                <div class="stats-list-title">${safeText(task.taskName || task.taskNumber || '-')}</div>
                <div class="stats-list-meta">${safeText(task.serviceName || '上线任务')}</div>
            </div>
            <div class="stats-list-side">
                <span class="stats-status-badge stats-status-failed">失败</span>
                <span class="stats-list-time">${safeText(formatDateTime(task.createTime))}</span>
            </div>
        </div>
    `).join('');
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

function formatDateTime(value) {
    if (!value) return '-';
    const date = new Date(value);
    if (Number.isNaN(date.getTime())) return String(value);
    return date.toLocaleString('zh-CN', {
        month: '2-digit',
        day: '2-digit',
        hour: '2-digit',
        minute: '2-digit'
    });
}

function safeText(value) {
    return String(value == null ? '-' : value)
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;')
        .replace(/'/g, '&#39;');
}


