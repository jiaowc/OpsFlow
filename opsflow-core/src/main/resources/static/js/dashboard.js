// ============================================
// Dashboard业务逻辑模块
// 页面初始化统一在 core.js 的 DOMContentLoaded 中完成
// ============================================

// 加载Pipeline参数输入框
// 用于在构建任务中选择Pipeline模板后，动态加载参数输入框
async function loadPipelineParameters(pipelineId) {
    const container = document.getElementById('pipelineParametersContainer');
    if (!container) return;
    
    if (!pipelineId) {
        container.style.display = 'none';
        container.innerHTML = '';
        return;
    }
    
    try {
        const response = await fetch(`/api/pipeline/${pipelineId}`);
        if (response.ok) {
            const pipeline = await response.json();
            if (pipeline.parameterDefinitions && Object.keys(pipeline.parameterDefinitions).length > 0) {
                container.style.display = 'block';
                container.innerHTML = `
                    <div class="form-item">
                        <label>${t('pipeline.param.title')}</label>
                        ${Object.entries(pipeline.parameterDefinitions).map(([paramName, paramDesc]) => `
                            <div style="margin-bottom: 8px;">
                                <label style="font-size: 12px;">${escapeHtml(paramName)}${paramDesc ? ` (${escapeHtml(paramDesc)})` : ''}</label>
                                <input type="text" name="pipelineParam_${escapeHtml(paramName)}" placeholder="${t('pipeline.param.enter')}${escapeHtml(paramName)}" style="width: 100%;">
                            </div>
                        `).join('')}
                    </div>
                `;
            } else {
                container.style.display = 'none';
                container.innerHTML = '';
            }
        }
    } catch (error) {
        console.error('Load pipeline parameters error:', error);
        container.style.display = 'none';
        container.innerHTML = '';
    }
}
window.loadPipelineParameters = loadPipelineParameters;

async function loadAppVersion() {
    const textEl = document.getElementById('appVersionText');
    const footerEl = document.getElementById('appVersionFooter');
    if (!textEl) {
        return;
    }
    try {
        const response = await fetch('/api/system/version');
        if (!response.ok) {
            throw new Error('version api failed');
        }
        const info = await response.json();
        const version = info.version || 'unknown';
        const name = info.name || 'OpsFlow';
        textEl.textContent = name + ' v' + version;
        if (footerEl) {
            const tips = [];
            if (info.buildTime) {
                tips.push(t('app.version.buildTime', { time: info.buildTime }));
            }
            if (info.artifact) {
                tips.push(t('app.version.artifact', { artifact: info.artifact }));
            }
            footerEl.title = tips.length ? tips.join('\n') : t('app.version.current', { version: version });
        }
    } catch (e) {
        textEl.textContent = 'OpsFlow';
    }
}
window.loadAppVersion = loadAppVersion;
