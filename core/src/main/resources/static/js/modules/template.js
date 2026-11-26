// ============================================
// 模版管理（Dockerfile / Deployment / Service）
// 使用 IIFE，避免与 services.js 等文件的顶层 const 冲突
// ============================================
(function (window, document) {
'use strict';
if (window.__opsflowTemplateModuleLoaded) { return; }
window.__opsflowTemplateModuleLoaded = true;

const TEMPLATE_TYPE_LABELS = {
    dockerfile: 'Dockerfile',
    deployment: 'Deployment',
    service: 'Service'
};

const TEMPLATE_K8S_SERVICE_TYPE_OPTIONS = [
    { value: 'ClusterIP', label: 'ClusterIP' },
    { value: 'NodePort', label: 'NodePort' },
    { value: 'LoadBalancer', label: 'LoadBalancer' }
];

// 新建模版时的起点：优先复制同类型的已有模版（见 showAddPipelineTemplateModal），
// 这里的骨架只在该类型下一份模版都没有时兜底，不要在这里维护完整的默认内容。
// 尖括号里的占位词由 templateSkeletons() 按当前语言替换；后端单测会读这个常量校验平台变量，改名或改结构时要同步
const TEMPLATE_SKELETONS = {
    dockerfile: {
        baseImage: '',
        content: `FROM <BASE_IMAGE>
WORKDIR /app
COPY <ARTIFACT> ./
EXPOSE \${servicePort}
CMD ["<START_COMMAND>"]`
    },
    deployment: {
        content: `apiVersion: apps/v1
kind: Deployment
metadata:
  name: \${serviceName}
  namespace: \${namespace}
spec:
  replicas: 1
  selector:
    matchLabels:
      app: \${serviceName}
  template:
    metadata:
      labels:
        app: \${serviceName}
    spec:
      containers:
      - name: \${serviceName}
        image: \${image}
        ports:
        - containerPort: \${servicePort}`
    },
    service: {
        serviceType: 'ClusterIP',
        content: `apiVersion: v1
kind: Service
metadata:
  name: \${serviceName}-svc
  namespace: \${namespace}
spec:
  type: \${serviceType}
  selector:
    app: \${serviceName}
  ports:
  - name: http
    port: \${servicePort}
    targetPort: \${servicePort}`
    }
};

/** 骨架的副本，占位词换成当前语言的文字 */
function templateSkeletons() {
    const skeletons = {};
    Object.keys(TEMPLATE_SKELETONS).forEach(type => {
        skeletons[type] = Object.assign({}, TEMPLATE_SKELETONS[type]);
    });
    skeletons.dockerfile.content = skeletons.dockerfile.content
        .replace('<BASE_IMAGE>', '<' + t('template.form.baseImage') + '>')
        .replace('<ARTIFACT>', '<' + t('template.skeleton.artifact') + '>')
        .replace('<START_COMMAND>', '<' + t('template.skeleton.command') + '>');
    return skeletons;
}

/** 新建时默认以哪份模版为起点：名字以「通用后端」开头的优先，否则取第一份 */
function pickDefaultTemplateSource(templates) {
    if (!templates || !templates.length) return null;
    return templates.find(item => String(item.name || '').startsWith('通用后端')) || templates[0]; // i18n-ignore
}

async function fetchTemplates(type) {
    const response = await fetch(`/api/pipeline-template/list?type=${encodeURIComponent(type)}`);
    if (!response.ok) {
        throw new Error(await response.text() || t('common.loadFailed'));
    }
    return await response.json() || [];
}

function renderStatusCell(status) {
    return (status === 1 || status == null)
        ? ('<span class="status-tag is-on">' + t('common.enabled') + '</span>')
        : ('<span class="status-tag is-off">' + t('common.disabled') + '</span>');
}

async function loadDockerfileTemplates() {
    const list = document.getElementById('dockerfileTemplateList');
    if (!list) return;
    list.innerHTML = ('<tr><td colspan="5" style="text-align:center;padding:40px;color:var(--text-4);">' + t('common.loading') + '</td></tr>');
    try {
        const templates = await fetchTemplates('dockerfile');
        if (!templates.length) {
            list.innerHTML = ('<tr><td colspan="5" style="text-align:center;padding:40px;color:var(--text-4);">' + t('template.empty.dockerfile') + '</td></tr>');
            return;
        }
        list.innerHTML = templates.map(tpl => `
            <tr>
                <td class="template-col-name"><span class="template-name">${escapeHtml(tpl.name)}</span></td>
                <td>${escapeHtml(tpl.description || '-')}</td>
                <td class="template-col-nowrap">${escapeHtml(tpl.baseImage || '-')}</td>
                <td>${renderStatusCell(tpl.status)}</td>
                <td>
                    <div class="table-actions">
                        <button class="btn-edit" onclick="editPipelineTemplate('dockerfile', ${tpl.id})">${t('common.modify')}</button>
                        <button class="btn-danger" onclick="deletePipelineTemplate(${tpl.id}, 'dockerfile')">${t('common.delete')}</button>
                    </div>
                </td>
            </tr>
        `).join('');
    } catch (e) {
        list.innerHTML = `<tr><td colspan="5" style="text-align:center;padding:40px;color:#dc2626;">${t('common.loadFailed')}: ${escapeHtml(e.message)}</td></tr>`;
    }
}
window.loadDockerfileTemplates = loadDockerfileTemplates;

async function loadDeploymentTemplates() {
    const list = document.getElementById('deploymentTemplateList');
    if (!list) return;
    list.innerHTML = ('<tr><td colspan="4" style="text-align:center;padding:40px;color:var(--text-4);">' + t('common.loading') + '</td></tr>');
    try {
        const templates = await fetchTemplates('deployment');
        if (!templates.length) {
            list.innerHTML = ('<tr><td colspan="4" style="text-align:center;padding:40px;color:var(--text-4);">' + t('template.empty.deployment') + '</td></tr>');
            return;
        }
        list.innerHTML = templates.map(tpl => `
            <tr>
                <td class="template-col-name"><span class="template-name">${escapeHtml(tpl.name)}</span></td>
                <td>${escapeHtml(tpl.description || '-')}</td>
                <td>${renderStatusCell(tpl.status)}</td>
                <td>
                    <div class="table-actions">
                        <button class="btn-edit" onclick="editPipelineTemplate('deployment', ${tpl.id})">${t('common.modify')}</button>
                        <button class="btn-danger" onclick="deletePipelineTemplate(${tpl.id}, 'deployment')">${t('common.delete')}</button>
                    </div>
                </td>
            </tr>
        `).join('');
    } catch (e) {
        list.innerHTML = `<tr><td colspan="4" style="text-align:center;padding:40px;color:#dc2626;">${t('common.loadFailed')}: ${escapeHtml(e.message)}</td></tr>`;
    }
}
window.loadDeploymentTemplates = loadDeploymentTemplates;

async function loadServiceTemplates() {
    const list = document.getElementById('serviceTemplateList');
    if (!list) return;
    list.innerHTML = ('<tr><td colspan="5" style="text-align:center;padding:40px;color:var(--text-4);">' + t('common.loading') + '</td></tr>');
    try {
        const templates = await fetchTemplates('service');
        if (!templates.length) {
            list.innerHTML = ('<tr><td colspan="5" style="text-align:center;padding:40px;color:var(--text-4);">' + t('template.empty.service') + '</td></tr>');
            return;
        }
        list.innerHTML = templates.map(tpl => `
            <tr>
                <td class="template-col-name"><span class="template-name">${escapeHtml(tpl.name)}</span></td>
                <td>${escapeHtml(tpl.description || '-')}</td>
                <td class="template-col-nowrap">${escapeHtml(tpl.serviceType || '-')}</td>
                <td>${renderStatusCell(tpl.status)}</td>
                <td>
                    <div class="table-actions">
                        <button class="btn-edit" onclick="editPipelineTemplate('service', ${tpl.id})">${t('common.modify')}</button>
                        <button class="btn-danger" onclick="deletePipelineTemplate(${tpl.id}, 'service')">${t('common.delete')}</button>
                    </div>
                </td>
            </tr>
        `).join('');
    } catch (e) {
        list.innerHTML = `<tr><td colspan="5" style="text-align:center;padding:40px;color:#dc2626;">${t('common.loadFailed')}: ${escapeHtml(e.message)}</td></tr>`;
    }
}
window.loadServiceTemplates = loadServiceTemplates;

// ============================================
// 变量检查：保存前确认内容里的 ${变量} 平台能替换
// 变量清单和判断规则都在后端（PipelineTemplateVariableChecker），这里只负责调用和展示
// 支持一个表单里挂多个检查单元（步骤管理的 Deployment + Service 内容框）
// ============================================

// 弹出提示后这段时间内的再次点击不算「仍然保存」，避免双击直接跳过提示
const TEMPLATE_VAR_ACK_DELAY_MS = 800;

function buildTemplateVarCheckHtml(options) {
    options = options || {};
    const type = options.type || '';
    const textareaName = options.textareaName || 'content';
    return `
    <div class="tpl-var-check-unit" data-tpl-type="${escapeHtml(type)}" data-textarea-name="${escapeHtml(textareaName)}">
        <div class="tpl-var-check-bar">
            <button type="button" class="btn-secondary btn-sm" data-role="tplVarCheckBtn">${t('template.var.check')}</button>
            <span class="tpl-var-check-tip">${t('template.var.checkTip')}</span>
        </div>
        <div class="tpl-var-check" data-role="tplVarCheckResult" hidden></div>
    </div>`;
}

async function requestTemplateVarCheck(type, content) {
    const response = await fetch('/api/pipeline-template/check-variables', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ type, content })
    });
    if (!response.ok) {
        throw new Error(await response.text() || ('HTTP ' + response.status));
    }
    return await response.json();
}

function templateVarCode(name) {
    return `<code>\${${escapeHtml(name)}}</code>`;
}

function formatTemplateVarLines(lines) {
    if (!lines || !lines.length) return '';
    const shown = lines.slice(0, 6).join(t('template.var.listSeparator'));
    return t(lines.length > 6 ? 'template.var.linesMore' : 'template.var.lines', { lines: shown });
}

function renderTemplateVarCheck(panel, result, blocking) {
    const issues = result.issues || [];
    const used = result.used || [];
    const passthrough = result.passthrough || [];
    const supported = result.supported || [];
    const parts = [];

    if (issues.length) {
        parts.push(`<div class="tpl-var-check-title">${t('template.var.issuesTitle', { count: issues.length })}</div>`);
        parts.push('<ul class="tpl-var-check-issues">' + issues.map(issue => `
            <li>
                <button type="button" class="tpl-var-locate" data-locate="${escapeHtml(issue.placeholder)}" title="${t('template.var.locate')}">${escapeHtml(issue.placeholder)}</button>
                <span class="tpl-var-lines">${escapeHtml(formatTemplateVarLines(issue.lines))}</span>
                <span>${escapeHtml(issue.message)}${issue.suggestion ? t('template.var.suggestion', { code: templateVarCode(issue.suggestion) }) : ''}</span>
            </li>`).join('') + '</ul>');
        if (blocking) {
            parts.push(('<div class="tpl-var-check-note">' + t('template.var.blockedNote') + '</div>'));
        }
    } else {
        parts.push(`<div class="tpl-var-check-title">${used.length
            ? t('template.var.passUsed', { count: used.length })
            : t('template.var.passNone')}</div>`);
    }

    if (used.length) {
        parts.push(`<div class="tpl-var-check-row"><span class="tpl-var-check-label">${issues.length ? t('template.var.replaceable') : t('template.var.used')}</span><span>${used.map(v => templateVarCode(v.name)).join(' ')}</span></div>`);
        const byCondition = {};
        used.filter(v => v.condition).forEach(v => {
            (byCondition[v.condition] = byCondition[v.condition] || []).push(v.name);
        });
        const conditions = Object.keys(byCondition);
        if (conditions.length) {
            parts.push(`<div class="tpl-var-check-row"><span class="tpl-var-check-label">${t('template.var.condition')}</span><span>${conditions.map(c =>
                `${byCondition[c].map(templateVarCode).join(' ')} ${escapeHtml(c)}`).join(t('template.var.conditionSeparator'))}</span></div>`);
        }
    }
    if (passthrough.length) {
        parts.push(`<div class="tpl-var-check-row"><span class="tpl-var-check-label">${t('template.var.passthrough')}</span><span>${passthrough.map(p => `<code>${escapeHtml(p)}</code>`).join(' ')} <span class="tpl-var-check-muted">${t('template.var.passthroughNote')}</span></span></div>`);
    }
    if (supported.length) {
        parts.push(`<details class="tpl-var-supported"><summary>${t('template.var.allSupported', { count: supported.length })}</summary><ul>${supported.map(v =>
            `<li>${templateVarCode(v.name)}<span>${escapeHtml(v.description || '')}${v.condition ? `（${escapeHtml(v.condition)}）` : ''}</span></li>`).join('')}</ul></details>`);
    }

    panel.className = 'tpl-var-check ' + (issues.length ? 'is-warn' : 'is-ok');
    panel.innerHTML = parts.join('');
    panel.hidden = false;
}

function renderTemplateVarCheckError(panel, error, blocking) {
    const message = (error && error.message) || t('common.unknownError');
    panel.className = 'tpl-var-check is-error';
    panel.innerHTML = `<div class="tpl-var-check-title">${t('template.var.incomplete', { message: escapeHtml(message) })}</div>`
        + (blocking ? ('<div class="tpl-var-check-note">' + t('template.var.incompleteNote') + '</div>') : '');
    panel.hidden = false;
}

function findTemplateVarCheckTextarea(unit, root) {
    if (!unit) return null;
    const name = unit.getAttribute('data-textarea-name');
    if (!name) return null;
    const scope = root || unit.closest('form') || document;
    return scope.querySelector(`[name="${name}"]`);
}

function templateVarCheckFingerprint(root) {
    return Array.from(root.querySelectorAll('.tpl-var-check-unit')).map(unit => {
        const ta = findTemplateVarCheckTextarea(unit, root);
        return (unit.getAttribute('data-tpl-type') || '') + '\n' + ((ta && ta.value) || '');
    }).join('\0');
}

/**
 * 检查单个单元并把结果画到该单元下方。
 * @returns 检查结果；请求失败时返回 null
 */
async function runTemplateVarCheckUnit(unit, root, blocking) {
    const type = unit.getAttribute('data-tpl-type');
    const textarea = findTemplateVarCheckTextarea(unit, root);
    const panel = unit.querySelector('[data-role="tplVarCheckResult"]');
    if (!type || !textarea || !panel) return null;

    const content = textarea.value;
    let result = null;
    try {
        result = await requestTemplateVarCheck(type, content);
        renderTemplateVarCheck(panel, result, blocking);
    } catch (e) {
        console.error('Template variable check failed:', e);
        renderTemplateVarCheckError(panel, e, blocking);
    }
    unit._tplVarCheckedContent = content;
    if (panel) panel.scrollIntoView({ block: 'nearest' });
    return result;
}

/**
 * 保存前检查 root 下所有非空内容的检查单元。
 * 有问题时先展示并返回 false；内容没改再点一次「确认」则放行。
 */
async function passTemplateVarChecks(root) {
    if (!root) return true;
    const units = Array.from(root.querySelectorAll('.tpl-var-check-unit')).filter(unit => {
        const ta = findTemplateVarCheckTextarea(unit, root);
        return ta && ta.value.trim();
    });
    if (!units.length) return true;

    const fingerprint = templateVarCheckFingerprint(root);
    const ack = root._tplVarAck;
    if (ack && ack.content === fingerprint) {
        return Date.now() - ack.at >= TEMPLATE_VAR_ACK_DELAY_MS;
    }

    let allPassed = true;
    let firstFailedPanel = null;
    for (const unit of units) {
        const result = await runTemplateVarCheckUnit(unit, root, true);
        if (!(result && result.passed)) {
            allPassed = false;
            if (!firstFailedPanel) {
                firstFailedPanel = unit.querySelector('[data-role="tplVarCheckResult"]');
            }
        }
    }
    if (allPassed) return true;
    root._tplVarAck = { content: fingerprint, at: Date.now() };
    if (firstFailedPanel) firstFailedPanel.scrollIntoView({ block: 'nearest' });
    return false;
}

/** 在指定 textarea 里选中文本；已选中时跳到下一处 */
function locateInTemplateContent(textarea, text) {
    if (!textarea || !text) return;
    const value = textarea.value;
    let from = 0;
    if (textarea.selectionEnd > textarea.selectionStart
        && value.slice(textarea.selectionStart, textarea.selectionEnd) === text) {
        from = textarea.selectionEnd;
    }
    let index = value.indexOf(text, from);
    if (index < 0) index = value.indexOf(text);
    if (index < 0) return;
    textarea.focus();
    textarea.setSelectionRange(index, index + text.length);
    const lineHeight = parseFloat(getComputedStyle(textarea).lineHeight) || 20;
    const line = value.slice(0, index).split('\n').length - 1;
    textarea.scrollTop = Math.max(0, (line - 3) * lineHeight);
}

/** 给 root 下尚未绑定的检查单元接上按钮、定位、过期标记 */
function wireTemplateVarChecks(root) {
    if (!root) return;
    root.querySelectorAll('.tpl-var-check-unit').forEach(unit => {
        if (unit.getAttribute('data-tpl-wired') === '1') return;
        const button = unit.querySelector('[data-role="tplVarCheckBtn"]');
        const panel = unit.querySelector('[data-role="tplVarCheckResult"]');
        const textarea = findTemplateVarCheckTextarea(unit, root);
        if (!button || !panel || !textarea) return;
        unit.setAttribute('data-tpl-wired', '1');

        button.addEventListener('click', async () => {
            button.disabled = true;
            try {
                await runTemplateVarCheckUnit(unit, root, false);
            } finally {
                button.disabled = false;
            }
        });
        panel.addEventListener('click', (e) => {
            const target = e.target.closest('[data-locate]');
            if (target) locateInTemplateContent(textarea, target.getAttribute('data-locate'));
        });
        textarea.addEventListener('input', () => {
            if (panel.hidden) return;
            panel.classList.toggle('is-stale', textarea.value !== unit._tplVarCheckedContent);
        });
    });
}

window.buildTemplateVarCheckHtml = buildTemplateVarCheckHtml;
window.wireTemplateVarChecks = wireTemplateVarChecks;
window.passTemplateVarChecks = passTemplateVarChecks;

function buildTemplateFormHtml(type, data, sources) {
    data = data || {};
    // sources 只在新建时传入：可作为起点的同类型模版
    const sourceField = sources ? `
        <div class="form-item">
            <label>${t('template.form.basedOn')}</label>
            <select name="templateSource">
                ${sources.map(t => `<option value="${t.id}" ${data.sourceId === t.id ? 'selected' : ''}>${escapeHtml(t.name)}</option>`).join('')}
                <option value="" ${data.sourceId == null ? 'selected' : ''}>${t('template.form.blank')}</option>
            </select>
            <small style="color:var(--text-3);">${t('template.form.basedOnHint')}</small>
        </div>
    ` : '';
    const commonFields = sourceField + `
        <div class="form-item">
            <label>${t('template.form.name')}${REQUIRED_MARK}</label>
            <input type="text" name="name" value="${escapeHtml(data.name || '')}" placeholder="${t('template.form.namePlaceholder')}" required>
        </div>
        <div class="form-item">
            <label>${t('common.description')}</label>
            <textarea name="description" rows="2" placeholder="${t('template.form.descriptionPlaceholder')}">${escapeHtml(data.description || '')}</textarea>
        </div>
        <div class="form-item">
            <label>${t('common.status')}</label>
            <select name="status">
                <option value="1" ${data.status !== 0 ? 'selected' : ''}>${t('common.enabled')}</option>
                <option value="0" ${data.status === 0 ? 'selected' : ''}>${t('common.disabled')}</option>
            </select>
        </div>
    `;

    if (type === 'dockerfile') {
        return commonFields + `
            <div class="form-item">
                <label>${t('template.form.baseImage')}${REQUIRED_MARK}</label>
                <input type="text" name="baseImage" value="${escapeHtml(data.baseImage || '')}" placeholder="${t('template.form.baseImagePlaceholder')}" required>
            </div>
            <div class="form-item">
                <label>${t('template.form.dockerfileContent')}${REQUIRED_MARK}</label>
                <textarea name="content" rows="14" required placeholder="FROM ...">${escapeHtml(data.content || '')}</textarea>
                <small style="color:var(--text-3);">${t('template.form.dockerfileHint')}</small>
                ${buildTemplateVarCheckHtml({ type: 'dockerfile', textareaName: 'content' })}
            </div>
        `;
    }

    if (type === 'deployment') {
        return commonFields + `
            <div class="form-item">
                <label>Deployment YAML${REQUIRED_MARK}</label>
                <textarea name="content" rows="16" required placeholder="apiVersion: apps/v1...">${escapeHtml(data.content || '')}</textarea>
                <small style="color:var(--text-3);">${t('template.form.deploymentHint')}</small>
                ${buildTemplateVarCheckHtml({ type: 'deployment', textareaName: 'content' })}
            </div>
        `;
    }

    const serviceTypeOptions = TEMPLATE_K8S_SERVICE_TYPE_OPTIONS.map(opt =>
        `<option value="${escapeHtml(opt.value)}" ${data.serviceType === opt.value ? 'selected' : ''}>${escapeHtml(opt.label)}</option>`
    ).join('');

    return commonFields + `
        <div class="form-item">
            <label>${t('template.form.serviceType')}${REQUIRED_MARK}</label>
            <select name="serviceType" required>
                <option value="">${t('common.select')}</option>
                ${serviceTypeOptions}
            </select>
            <small style="color:var(--text-3);">${t('template.form.serviceTypeHint')}</small>
        </div>
        <div class="form-item">
            <label>Service YAML${REQUIRED_MARK}</label>
            <textarea name="content" rows="14" required placeholder="apiVersion: v1...">${escapeHtml(data.content || '')}</textarea>
            <small style="color:var(--text-3);">${t('template.form.serviceHint')}</small>
            ${buildTemplateVarCheckHtml({ type: 'service', textareaName: 'content' })}
        </div>
    `;
}

function collectTemplateFormData(form, type) {
    const nameInput = form.querySelector('[name="name"]');
    const descInput = form.querySelector('[name="description"]');
    const contentInput = form.querySelector('[name="content"]');
    const name = ((nameInput && nameInput.value) || '').trim();
    const description = ((descInput && descInput.value) || '').trim();
    const content = (contentInput && contentInput.value) || '';
    if (!name) {
        notify(t('template.msg.nameRequired'));
        return null;
    }
    if (!content.trim()) {
        notify(t('template.msg.contentRequired'));
        return null;
    }
    const payload = {
        name,
        type,
        description,
        content,
        status: (form.querySelector('[name="status"]')?.value === '0') ? 0 : 1
    };
    if (type === 'dockerfile') {
        const baseImageInput = form.querySelector('[name="baseImage"]');
        const baseImage = ((baseImageInput && baseImageInput.value) || '').trim();
        if (!baseImage) {
            notify(t('template.msg.baseImageRequired'));
            return null;
        }
        payload.baseImage = baseImage;
    } else if (type === 'service') {
        const serviceTypeInput = form.querySelector('[name="serviceType"]');
        const serviceType = serviceTypeInput && serviceTypeInput.value;
        if (!serviceType) {
            notify(t('template.msg.serviceTypeRequired'));
            return null;
        }
        payload.serviceType = serviceType;
    }
    return payload;
}

function reloadTemplateList(type) {
    if (type === 'dockerfile') return loadDockerfileTemplates();
    if (type === 'deployment') return loadDeploymentTemplates();
    return loadServiceTemplates();
}

async function showAddPipelineTemplateModal(type) {
    if (typeof window.showModal !== 'function') {
        notify(t('template.msg.modalNotLoaded'));
        return;
    }
    const label = TEMPLATE_TYPE_LABELS[type] || type;
    let sources = [];
    try {
        sources = await fetchTemplates(type);
    } catch (e) {
        // 取不到已有模版时照常打开，只是没有可复制的起点
        console.warn('Load existing templates failed, using the blank skeleton', e);
    }
    const skeleton = templateSkeletons()[type] || {};
    const defaultSource = pickDefaultTemplateSource(sources);
    const seed = defaultSource
        ? { sourceId: defaultSource.id, content: defaultSource.content, baseImage: defaultSource.baseImage, serviceType: defaultSource.serviceType }
        : Object.assign({ sourceId: null }, skeleton);
    const content = `
        <form id="createPipelineTemplateForm" class="tpl-form">
            ${buildTemplateFormHtml(type, seed, sources)}
        </form>
    `;
    window.showModal(t('template.form.createTitle', { type: label }), content, async () => {
        const form = document.getElementById('createPipelineTemplateForm');
        if (!form) return;
        const data = collectTemplateFormData(form, type);
        if (!data) return;
        if (!await passTemplateVarChecks(form)) return;
        try {
            const response = await fetch('/api/pipeline-template/create', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(data)
            });
            if (response.ok) {
                notify(t('common.createOk'));
                await reloadTemplateList(type);
                window.closeModal();
            } else {
                notify((t('common.createFailed') + ': ') + await response.text());
            }
        } catch (e) {
            notify((t('common.createFailed') + ': ') + e.message);
        }
    });
    const form = document.getElementById('createPipelineTemplateForm');
    wireTemplateVarChecks(form);
    wireTemplateSourceSelect(form, sources, skeleton);
}
window.showAddPipelineTemplateModal = showAddPipelineTemplateModal;

/** 切换「基于」时把所选模版（或空白骨架）的内容填进表单 */
function wireTemplateSourceSelect(form, sources, skeleton) {
    const select = form && form.querySelector('[name="templateSource"]');
    if (!select) return;
    select.addEventListener('change', () => {
        const source = sources.find(t => String(t.id) === select.value) || skeleton;
        const fill = (name, value) => {
            const field = form.querySelector(`[name="${name}"]`);
            if (!field) return;
            field.value = value == null ? '' : value;
            // 触发变量检查等依赖 input 事件的逻辑
            field.dispatchEvent(new Event('input', { bubbles: true }));
        };
        fill('content', source.content);
        fill('baseImage', source.baseImage);
        fill('serviceType', source.serviceType);
    });
}

async function editPipelineTemplate(type, id) {
    try {
        const response = await fetch(`/api/pipeline-template/${id}`);
        if (!response.ok) {
            notify(t('common.loadFailed'));
            return;
        }
        const template = await response.json();
        const label = TEMPLATE_TYPE_LABELS[type] || type;
        const content = `
            <form id="editPipelineTemplateForm" class="tpl-form">
                ${buildTemplateFormHtml(type, template)}
            </form>
        `;
        window.showModal(t('template.form.editTitle', { type: label }), content, async () => {
            const form = document.getElementById('editPipelineTemplateForm');
            if (!form) return;
            const data = collectTemplateFormData(form, type);
            if (!data) return;
            if (!await passTemplateVarChecks(form)) return;
            try {
                const resp = await fetch(`/api/pipeline-template/${id}`, {
                    method: 'PUT',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify(data)
                });
                if (resp.ok) {
                    notify(t('common.updatedOk'));
                    await reloadTemplateList(type);
                    window.closeModal();
                } else {
                    notify((t('common.updatedFailed') + ': ') + await resp.text());
                }
            } catch (e) {
                notify((t('common.updatedFailed') + ': ') + e.message);
            }
        });
        wireTemplateVarChecks(document.getElementById('editPipelineTemplateForm'));
    } catch (e) {
        notify((t('common.loadFailed') + ': ') + e.message);
    }
}
window.editPipelineTemplate = editPipelineTemplate;

async function deletePipelineTemplate(id, type) {
    if (!await confirmDialog(t('template.msg.deleteConfirm'))) return;
    try {
        const response = await fetch(`/api/pipeline-template/${id}`, { method: 'DELETE' });
        if (response.ok) {
            notify(t('common.deleteOk'));
            await reloadTemplateList(type);
        } else {
            notify(t('common.deleteFailed'));
        }
    } catch (e) {
        notify((t('common.deleteFailed') + ': ') + e.message);
    }
}
window.deletePipelineTemplate = deletePipelineTemplate;

function showAddDockerfileTemplateModal() {
    return showAddPipelineTemplateModal('dockerfile');
}
function showAddDeploymentTemplateModal() {
    return showAddPipelineTemplateModal('deployment');
}
function showAddServiceTemplateModal() {
    return showAddPipelineTemplateModal('service');
}
window.showAddDockerfileTemplateModal = showAddDockerfileTemplateModal;
window.showAddDeploymentTemplateModal = showAddDeploymentTemplateModal;
window.showAddServiceTemplateModal = showAddServiceTemplateModal;

function openTemplateModalSafe(fn, label) {
    try {
        if (typeof fn !== 'function') {
            notify(t('template.msg.notLoaded'));
            return;
        }
        if (typeof window.showModal !== 'function') {
            notify(t('template.msg.coreNotLoaded'));
            return;
        }
        var result = fn();
        if (result && typeof result.catch === 'function') {
            result.catch(function(err) {
                console.error(label + ' failed:', err);
                notify(t('template.msg.actionFailed', { action: label }) + ': ' + (err && err.message ? err.message : err), 'error');
            });
        }
    } catch (err) {
        console.error(label + ' error:', err);
        notify(t('template.msg.actionFailed', { action: label }) + ': ' + (err && err.message ? err.message : err), 'error');
    }
}

function wireTemplateAddButtons() {
    var pairs = [
        ['addDockerfileTemplateBtn', function() { return showAddDockerfileTemplateModal(); }, t('template.action.addDockerfile')],
        ['addDeploymentTemplateBtn', function() { return showAddDeploymentTemplateModal(); }, t('template.action.addDeployment')],
        ['addServiceTemplateBtn', function() { return showAddServiceTemplateModal(); }, t('template.action.addService')]
    ];
    pairs.forEach(function(pair) {
        var btn = document.getElementById(pair[0]);
        if (!btn || btn.getAttribute('data-template-wired') === '1') {
            return;
        }
        btn.setAttribute('data-template-wired', '1');
        btn.addEventListener('click', function(e) {
            e.preventDefault();
            e.stopPropagation();
            openTemplateModalSafe(pair[1], pair[2]);
        });
    });
}

if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', wireTemplateAddButtons);
} else {
    wireTemplateAddButtons();
}
window.openTemplateModalSafe = openTemplateModalSafe;
window.wireTemplateAddButtons = wireTemplateAddButtons;

})(window, document);
