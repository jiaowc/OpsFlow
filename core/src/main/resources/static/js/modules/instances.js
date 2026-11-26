// 服务运行实例（K8s Deployment / Pod）只读视图

let instanceViewJobId = null;

async function instanceFetchJson(url) {
    const resp = await fetch(url);
    let body = {};
    try {
        body = await resp.json();
    } catch (e) {
        body = {};
    }
    if (!resp.ok) {
        throw new Error(body.message || body.error || resp.statusText || t('instances.msg.requestFailed'));
    }
    return body;
}

function showServiceInstances(jobId, title) {
    instanceViewJobId = jobId;
    const content = `
        <div class="instance-view">
            <div class="instance-toolbar">
                <span id="instanceSummary" class="instance-summary">${t('common.loading')}</span>
                <button type="button" class="btn-secondary" onclick="loadServiceInstances()">${t('common.refresh')}</button>
            </div>
            <div id="instanceTable"></div>
            <div id="instancePanel" class="instance-panel" style="display:none;"></div>
        </div>`;
    window.showModal(`${t('instances.title')} · ${escapeHtml(title || '')}`, content);
    loadServiceInstances();
}

async function loadServiceInstances() {
    const jobId = instanceViewJobId;
    const summaryEl = document.getElementById('instanceSummary');
    const tableEl = document.getElementById('instanceTable');
    if (!summaryEl || !tableEl) return;
    summaryEl.textContent = t('common.loading');
    tableEl.innerHTML = '';
    try {
        const data = await instanceFetchJson(`/api/pipeline-run/${jobId}/instances`);
        const s = data.summary || {};
        const healthy = s.replicas > 0 && s.readyReplicas === s.replicas;
        summaryEl.innerHTML = `
            <span class="instance-tag">${escapeHtml(data.envName || '')}</span>
            <code>${escapeHtml(data.namespace)}/${escapeHtml(data.deployment)}</code>
            <span class="${healthy ? 'instance-ok' : 'instance-warn'}">${t('instances.summary.ready', { ready: s.readyReplicas || 0, total: s.replicas || 0 })}</span>
            <span class="instance-muted">${t('instances.summary.updated', { updated: Number(s.updatedReplicas) || 0, available: Number(s.availableReplicas) || 0 })}</span>
            <div class="instance-muted instance-image" title="${t('instances.summary.image')}">${(s.images || []).map(escapeHtml).join('<br>')}</div>`;
        const pods = data.pods || [];
        if (!pods.length) {
            tableEl.innerHTML = ('<div class="instance-empty">' + t('instances.empty') + '</div>');
            return;
        }
        tableEl.innerHTML = `
            <table class="data-table instance-table">
                <thead><tr><th>Pod</th><th>${t('common.status')}</th><th>${t('instances.col.ready')}</th><th>${t('instances.col.restarts')}</th><th>${t('instances.col.node')}</th><th>${t('instances.col.startTime')}</th><th>${t('common.actions')}</th></tr></thead>
                <tbody>${pods.map(renderInstancePodRow).join('')}</tbody>
            </table>`;
    } catch (error) {
        summaryEl.innerHTML = `<span class="instance-warn">${escapeHtml(error.message)}</span>`;
    }
}

function renderInstancePodRow(pod) {
    const containers = pod.containers || [];
    const containerSelect = containers.length > 1
        ? `<select class="instance-container" data-pod="${escapeHtml(pod.name)}">${containers.map(c => `<option value="${escapeHtml(c)}">${escapeHtml(c)}</option>`).join('')}</select>`
        : '';
    const statusClass = pod.allReady && pod.status === 'Running' ? 'instance-ok' : 'instance-warn';
    const podArg = escapeHtml(JSON.stringify(pod.name));
    return `
        <tr>
            <td class="instance-pod-name">${escapeHtml(pod.name)}</td>
            <td><span class="${statusClass}">${escapeHtml(pod.status)}</span></td>
            <td>${escapeHtml(pod.ready)}</td>
            <td title="${escapeHtml(pod.lastTermination ? t('instances.pod.lastExit', { reason: pod.lastTermination }) : '')}">
                ${pod.restarts > 0 ? `<span class="instance-warn">${pod.restarts}</span>` : '0'}
            </td>
            <td class="instance-muted">${escapeHtml(pod.node || '-')}<br>${escapeHtml(pod.podIp || '-')}</td>
            <td class="instance-muted">${escapeHtml(formatInstanceTime(pod.startTime))}</td>
            <td class="instance-actions">
                ${containerSelect}
                <button type="button" class="btn-secondary" onclick="showPodLogs(${podArg}, false)">${t('instances.logs')}</button>
                ${pod.restarts > 0 ? `<button type="button" class="btn-secondary" onclick="showPodLogs(${podArg}, true)" title="${t('instances.logs.previousHint')}">${t('instances.logs.previous')}</button>` : ''}
                <button type="button" class="btn-secondary" onclick="showPodEvents(${podArg})">${t('instances.events')}</button>
            </td>
        </tr>`;
}

function formatInstanceTime(value) {
    if (!value) return '-';
    const d = new Date(value);
    return isNaN(d.getTime()) ? value : d.toLocaleString(getDateLocale(), { hour12: false });
}

function openInstancePanel(title) {
    const panel = document.getElementById('instancePanel');
    if (!panel) return null;
    panel.style.display = 'block';
    panel.innerHTML = `<div class="instance-panel-title">${/* html-safe: 调用方传入已转义的 HTML */ title}</div><div class="instance-panel-body">${t('common.loading')}</div>`;
    return panel.querySelector('.instance-panel-body');
}

async function showPodLogs(pod, previous) {
    const select = document.querySelector(`.instance-container[data-pod="${CSS.escape(pod)}"]`);
    const container = select ? select.value : '';
    const body = openInstancePanel(`${t(previous ? 'instances.logs.previousTitle' : 'instances.logs')} · ${escapeHtml(pod)}${container ? ' / ' + escapeHtml(container) : ''}${t('instances.logs.tail')}`);
    if (!body) return;
    try {
        const params = new URLSearchParams({ tail: '300', previous: String(!!previous) });
        if (container) params.set('container', container);
        const data = await instanceFetchJson(`/api/pipeline-run/${instanceViewJobId}/instances/${encodeURIComponent(pod)}/logs?${params}`);
        body.innerHTML = `<pre class="instance-log">${escapeHtml(data.log || t('instances.logs.empty'))}</pre>`;
        const pre = body.querySelector('pre');
        if (pre) pre.scrollTop = pre.scrollHeight;
    } catch (error) {
        body.innerHTML = `<span class="instance-warn">${escapeHtml(error.message)}</span>`;
    }
}

async function showPodEvents(pod) {
    const body = openInstancePanel(`${t('instances.events')} · ${escapeHtml(pod)}`);
    if (!body) return;
    try {
        const events = await instanceFetchJson(`/api/pipeline-run/${instanceViewJobId}/instances/${encodeURIComponent(pod)}/events`);
        if (!events.length) {
            body.innerHTML = ('<div class="instance-empty">' + t('instances.events.empty') + '</div>');
            return;
        }
        body.innerHTML = `
            <table class="data-table instance-table">
                <thead><tr><th>${t('instances.col.time')}</th><th>${t('common.type')}</th><th>${t('instances.col.reason')}</th><th>${t('instances.col.count')}</th><th>${t('instances.col.message')}</th></tr></thead>
                <tbody>${events.map(e => `
                    <tr>
                        <td class="instance-muted">${escapeHtml(formatInstanceTime(e.time))}</td>
                        <td><span class="${e.type === 'Warning' ? 'instance-warn' : 'instance-muted'}">${escapeHtml(e.type)}</span></td>
                        <td>${escapeHtml(e.reason)}</td>
                        <td>${e.count}</td>
                        <td class="instance-event-msg">${escapeHtml(e.message)}</td>
                    </tr>`).join('')}
                </tbody>
            </table>`;
    } catch (error) {
        body.innerHTML = `<span class="instance-warn">${escapeHtml(error.message)}</span>`;
    }
}

window.showServiceInstances = showServiceInstances;
window.loadServiceInstances = loadServiceInstances;
window.showPodLogs = showPodLogs;
window.showPodEvents = showPodEvents;
