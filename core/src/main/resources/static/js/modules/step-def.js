// ============================================
// Pipeline 步骤定义模块（可复用步骤）
// ============================================

// 用函数而不是常量：标签随当前语言变化，每次取值时翻译
function stepTypeLabels() {
    return {
    checkout: t('stepdef.type.checkout'),
    build: t('stepdef.type.build'),
    docker_build: t('stepdef.type.dockerBuild'),
    push_image: t('stepdef.type.pushImage'),
    deploy: t('stepdef.type.deploy'),
    check_deploy: t('stepdef.type.checkDeploy'),
    render_template: t('stepdef.type.renderTemplate'),
    clean: t('stepdef.type.clean'),
    clean_workspace: t('stepdef.type.cleanWorkspace'),
    rollback: t('stepdef.type.rollback'),
    notify: t('stepdef.type.notify')
    };
}

const CI_STEP_TYPES = ['checkout', 'build', 'docker_build', 'push_image', 'clean', 'clean_workspace'];
const CD_STEP_TYPES = ['deploy', 'check_deploy', 'render_template', 'rollback', 'notify'];

function stepContentFields() {
    const fields = {
    checkout: [
        { key: 'branch', label: t('stepdef.field.branch'), type: 'text', placeholder: t('stepdef.field.branchPlaceholder') }
    ],
    build: [
        { key: 'buildCommand', label: t('stepdef.field.buildCommand'), type: 'textarea', rows: 3, placeholder: 'mvn clean package -DskipTests', required: true }
    ],
    docker_build: [
        { key: 'dockerfileTemplateId', label: t('stepdef.field.dockerfileTemplate'), type: 'template-select', templateType: 'dockerfile', contentKey: 'dockerfileContent', required: true },
        { key: 'dockerfilePath', label: t('stepdef.field.dockerfilePath'), type: 'text', placeholder: 'Dockerfile', defaultValue: 'Dockerfile' },
        { key: 'dockerfileContent', label: t('stepdef.field.dockerfileContent'), type: 'textarea', rows: 10, placeholder: t('stepdef.field.dockerfileContentPlaceholder') },
        { key: 'dockerBuildArgs', label: t('stepdef.field.dockerBuildArgs'), type: 'text', placeholder: t('stepdef.field.dockerBuildArgsPlaceholder') },
        { key: 'dockerBuildCommand', label: t('stepdef.field.dockerBuildCommand'), type: 'text', required: true,
          placeholder: 'docker build -f ${dockerfilePath} -t ${harbor}/${namespace}/${serviceName}:${tag} ${workspace}',
          defaultValue: 'docker build -f ${dockerfilePath} -t ${harbor}/${namespace}/${serviceName}:${tag} ${workspace}',
          hint: (t('stepdef.field.variables') + '${harbor} ${namespace} ${serviceName} ${tag} ${dockerfilePath} ${workspace}') }
    ],
    push_image: [
        { type: 'hint', text: (t('stepdef.field.pushHintBefore') + '${imageFullName}' + t('stepdef.field.pushHintAfter')) },
        { key: 'dockerPushCommand', label: t('stepdef.field.dockerPushCommand'), type: 'text', required: true,
          placeholder: 'docker push ${imageFullName}',
          defaultValue: 'docker push ${imageFullName}',
          hint: (t('stepdef.field.variables') + '${imageFullName} / ${image}' + t('stepdef.field.pushVariablesNote')) },
        { key: 'dockerPushArgs', label: t('stepdef.field.dockerPushArgs'), type: 'text', placeholder: t('stepdef.field.dockerPushArgsPlaceholder') }
    ],
    render_template: [
        { key: 'deploymentTemplateId', label: t('stepdef.field.deploymentTemplate'), type: 'template-select', templateType: 'deployment', contentKey: 'deploymentTemplateContent', required: true },
        { key: 'serviceTemplateId', label: t('stepdef.field.serviceTemplate'), type: 'template-select', templateType: 'service', contentKey: 'serviceTemplateContent' },
        { key: 'serviceType', type: 'hidden' },
        { key: 'outputDir', label: t('stepdef.field.outputDir'), type: 'text', placeholder: 'manifests', defaultValue: 'manifests' },
        { key: 'deploymentTemplateContent', label: t('stepdef.field.deploymentContent'), type: 'textarea', rows: 10, placeholder: t('stepdef.field.deploymentContentPlaceholder') },
        { key: 'serviceTemplateContent', label: t('stepdef.field.serviceContent'), type: 'textarea', rows: 6, placeholder: t('stepdef.field.serviceContentPlaceholder') }
    ],
    deploy: [
        { key: 'deployScript', label: t('stepdef.field.deployScript'), type: 'textarea', rows: 3, placeholder: 'kubectl apply -f ...' }
    ],
    check_deploy: [
        { key: 'timeoutSeconds', label: t('stepdef.field.timeoutSeconds'), type: 'text', placeholder: '300', defaultValue: '300' }
    ],
    clean: [
        { key: 'cleanCommand', label: t('stepdef.field.cleanCommand'), type: 'textarea', rows: 2, placeholder: 'mvn clean' }
    ],
    clean_workspace: [
        { key: 'workspaceBase', label: t('stepdef.field.workspaceBase'), type: 'text', placeholder: '~/opsflow', defaultValue: '~/opsflow' },
        { key: 'workspacePath', label: t('stepdef.field.workspacePath'), type: 'text', placeholder: '~/opsflow/demo/dev/develop' },
        { key: 'cleanDocker', label: t('stepdef.field.cleanDocker'), type: 'text', placeholder: 'true / false', defaultValue: 'false' }
    ],
    rollback: [
        { key: 'toRevision', label: t('stepdef.field.toRevision'), type: 'text', placeholder: t('stepdef.field.toRevisionPlaceholder') },
        { key: 'waitRollout', label: t('stepdef.field.waitRollout'), type: 'text', placeholder: 'true / false', defaultValue: 'true' },
        { key: 'timeoutSeconds', label: t('stepdef.field.waitTimeout'), type: 'text', placeholder: '300', defaultValue: '300' }
    ],
    notify: [
        { key: 'message', label: t('stepdef.field.notifyMessage'), type: 'textarea', rows: 3, required: true,
          placeholder: t('stepdef.field.notifyMessagePlaceholder'),
          hint: t('stepdef.field.notifyMessageHint') + '${service} ${env} ${branch} ${image} ${jobNumber} ${trigger} ${project} ${pipeline}' },
        // 有扩展字段（如外部通知通道）时不显示
        { type: 'hint', text: t('stepdef.field.notifyNoChannelHint'), hideWhenExtended: true }
    ]
    };
    Object.keys(stepContentFieldExtensions).forEach(stepType => {
        const extra = availableStepContentFieldExtensions(stepType);
        if (extra.length) {
            fields[stepType] = (fields[stepType] || []).concat(extra);
        }
    });
    return fields;
}

/**
 * 步骤内容字段的扩展点：其他模块（如扩展脚本）可给某种步骤类型追加字段，追加在核心字段之后。
 * 字段格式同 stepContentFields()；另支持 type: 'custom'：
 *   render(value, ctx) → HTML 字符串（可返回 Promise），HTML 由扩展自己负责转义，
 *       其中取值的控件不要求 name；ctx = { stepType, contentConfig }
 *   collect(holder) → 字符串（空串表示不保存该项），holder 是包住 render 结果的元素
 * 可选 available() → false 时该字段不出现（如对应功能未开通）。
 * 同一步骤类型下 key 重复时后注册的替换先注册的。
 */
const stepContentFieldExtensions = {};

function registerStepContentFieldExtension(stepType, field) {
    if (!stepType || !field || !field.key) return;
    const list = (stepContentFieldExtensions[stepType] || []).filter(f => f.key !== field.key);
    list.push(field);
    stepContentFieldExtensions[stepType] = list;
}
window.registerStepContentFieldExtension = registerStepContentFieldExtension;

function availableStepContentFieldExtensions(stepType) {
    return (stepContentFieldExtensions[stepType] || []).filter(f => {
        try {
            return typeof f.available !== 'function' || f.available();
        } catch (e) {
            return false;
        }
    });
}

function hasStepContentFieldExtension(stepType) {
    return availableStepContentFieldExtensions(stepType).length > 0;
}

let pipelineStepDefCache = [];
const stepTemplateCache = {};

async function loadStepTemplates(type, force) {
    if (!force && stepTemplateCache[type]) {
        return stepTemplateCache[type];
    }
    try {
        const response = await fetch(`/api/pipeline-template/list?type=${encodeURIComponent(type)}`);
        if (response.ok) {
            const list = (await response.json() || []).filter(tpl => tpl.status === 1 || tpl.status == null);
            stepTemplateCache[type] = list;
            return list;
        }
    } catch (e) {
        console.error('Load templates for step error:', e);
    }
    stepTemplateCache[type] = stepTemplateCache[type] || [];
    return stepTemplateCache[type];
}

function renderTemplateSelectOptions(templates, selectedId, emptyLabel) {
    let html = `<option value="">${escapeHtml(emptyLabel || t('stepdef.template.select'))}</option>`;
    if (!templates.length) {
        html += ('<option value="" disabled>' + t('stepdef.template.none') + '</option>');
        return html;
    }
    templates.forEach(tpl => {
        const selected = selectedId != null && String(selectedId) === String(tpl.id) ? ' selected' : '';
        html += `<option value="${tpl.id}"${selected}>${escapeHtml(tpl.name)}</option>`;
    });
    return html;
}

function inferPhase(stepType) {
    return CI_STEP_TYPES.includes(stepType) ? 'ci' : 'cd';
}

function getStepTypeLabel(stepType) {
    return stepTypeLabels()[stepType] || stepType;
}

async function loadPipelineStepDefs(force) {
    if (!force && pipelineStepDefCache.length > 0) {
        return pipelineStepDefCache;
    }
    try {
        const response = await fetch('/api/pipeline-step-def/list');
        if (response.ok) {
            pipelineStepDefCache = await response.json() || [];
            window.pipelineStepDefCache = pipelineStepDefCache;
            return pipelineStepDefCache;
        }
    } catch (error) {
        console.error('Load pipeline step defs error:', error);
    }
    return pipelineStepDefCache;
}
window.loadPipelineStepDefs = loadPipelineStepDefs;

async function loadStepDefs() {
    const list = document.getElementById('stepDefList');
    if (!list) return;
    list.innerHTML = ('<tr><td colspan="7" style="text-align:center;padding:40px;color:var(--text-4);">' + t('common.loading') + '</td></tr>');
    const defs = await loadPipelineStepDefs(true);
    renderStepDefList(defs);
}
window.loadStepDefs = loadStepDefs;

function renderStepDefList(defs) {
    const list = document.getElementById('stepDefList');
    if (!list) return;
    if (!defs.length) {
        list.innerHTML = ('<tr><td colspan="7" style="text-align:center;padding:40px;color:var(--text-4);">' + t('stepdef.empty') + '</td></tr>');
        return;
    }
    list.innerHTML = defs.map(def => {
        const phaseText = def.phase === 'cd' ? 'CD' : 'CI';
        const statusText = (def.status === 1 || def.status == null)
            ? ('<span class="status-tag is-on">' + t('common.enabled') + '</span>')
            : ('<span class="status-tag is-off">' + t('common.disabled') + '</span>');
        const timeoutText = def.timeoutSeconds != null ? def.timeoutSeconds : 60;
        return `
            <tr>
                <td>${escapeHtml(def.name)}</td>
                <td>${escapeHtml(getStepTypeLabel(def.stepType))}</td>
                <td>${phaseText}</td>
                <td>${timeoutText}</td>
                <td>${escapeHtml(def.description || '-')}</td>
                <td>${statusText}</td>
                <td>
                    <button class="btn-edit" onclick="editStepDef(${def.id})">${t('common.modify')}</button>
                    <button class="btn-danger" onclick="deleteStepDef(${def.id})">${t('common.delete')}</button>
                </td>
            </tr>
        `;
    }).join('');
}

/** 步骤内容框 → 模版类型；挂上模版管理同一套变量检查 */
const STEP_CONTENT_TEMPLATE_TYPE = {
    dockerfileContent: 'dockerfile',
    deploymentTemplateContent: 'deployment',
    serviceTemplateContent: 'service'
};

function appendTemplateVarCheckHtml(textareaName, contentKey) {
    const type = STEP_CONTENT_TEMPLATE_TYPE[contentKey];
    if (!type || typeof window.buildTemplateVarCheckHtml !== 'function') {
        return '';
    }
    return window.buildTemplateVarCheckHtml({ type, textareaName });
}

function wireStepTemplateVarChecks(root) {
    if (root && typeof window.wireTemplateVarChecks === 'function') {
        window.wireTemplateVarChecks(root);
    }
}

async function passStepTemplateVarChecks(root) {
    if (!root || typeof window.passTemplateVarChecks !== 'function') {
        return true;
    }
    return window.passTemplateVarChecks(root);
}

async function buildStepContentFieldsHtml(stepType, contentConfig) {
    contentConfig = contentConfig || {};
    const fields = stepContentFields()[stepType] || [];
    if (!fields.length) {
        return ('<div style="color:var(--text-3);font-size:13px;padding:8px 0;">' + t('stepdef.content.none') + '</div>');
    }
    const parts = [];
    for (const field of fields) {
        const value = contentConfig[field.key] != null ? contentConfig[field.key] : (field.defaultValue || '');
        const required = field.required ? ' required' : '';
        const fieldMark = field.required ? REQUIRED_MARK : '';
        if (field.type === 'hint') {
            if (field.hideWhenExtended && hasStepContentFieldExtension(stepType)) {
                continue;
            }
            parts.push(`
                <div style="margin:8px 0 12px;padding:10px 12px;background:#f8fafc;border:1px solid #e5e7eb;border-radius:6px;font-size:13px;color:#4b5563;line-height:1.5;">
                    ${escapeHtml(field.text || '')}
                </div>
            `);
            continue;
        }
        if (field.type === 'template-select') {
            const templates = await loadStepTemplates(field.templateType);
            const emptyLabel = field.required ? t('stepdef.template.select') : t('stepdef.template.noLink');
            parts.push(`
                <div class="form-item">
                    <label>${escapeHtml(field.label)}${fieldMark}</label>
                    <select name="content_${escapeHtml(field.key)}" data-content-key="${escapeHtml(field.contentKey || '')}"
                            data-template-type="${escapeHtml(field.templateType || '')}"
                            onchange="onStepTemplateSelectChange(this)"${required}>
                        ${renderTemplateSelectOptions(templates, value, emptyLabel)}
                    </select>
                    <small style="color:var(--text-3);">${t('stepdef.template.hint')}</small>
                </div>
            `);
            continue;
        }
        if (field.type === 'custom') {
            if (typeof field.render === 'function') {
                try {
                    parts.push(`<div class="form-item step-content-custom" data-content-custom="${escapeHtml(field.key)}">`
                        + (await field.render(contentConfig[field.key] != null ? String(contentConfig[field.key]) : '',
                            { stepType, contentConfig }) || '')
                        + '</div>');
                } catch (e) {
                    console.error('Render step content extension error:', field.key, e);
                }
            }
            continue;
        }
        if (field.type === 'hidden') {
            parts.push(`<input type="hidden" name="content_${escapeHtml(field.key)}" value="${escapeHtml(value)}">`);
            continue;
        }
        if (field.type === 'textarea') {
            const textareaName = `content_${field.key}`;
            parts.push(`
                <div class="form-item">
                    <label>${escapeHtml(field.label)}${fieldMark}</label>
                    <textarea name="${textareaName}" rows="${escapeHtml(field.rows || 3)}" placeholder="${escapeHtml(field.placeholder || '')}"${required}>${escapeHtml(value)}</textarea>
                    ${field.hint ? `<small style="color:var(--text-3);">${escapeHtml(field.hint)}</small>` : ''}
                    ${appendTemplateVarCheckHtml(textareaName, field.key)}
                </div>
            `);
            continue;
        }
        parts.push(`
            <div class="form-item">
                <label>${escapeHtml(field.label)}${fieldMark}</label>
                <input type="text" name="content_${escapeHtml(field.key)}" value="${escapeHtml(value)}"
                       placeholder="${escapeHtml(field.placeholder || '')}"
                       style="width:100%;box-sizing:border-box;font-family:ui-monospace,SFMono-Regular,Menlo,Monaco,Consolas,monospace;font-size:13px;"${required}>
                ${field.hint ? `<small style="color:var(--text-3);">${escapeHtml(field.hint)}</small>` : ''}
            </div>
        `);
    }
    return parts.join('');
}

async function onStepTemplateSelectChange(selectEl) {
    const contentKey = selectEl.getAttribute('data-content-key');
    if (!contentKey) return;
    const form = selectEl.closest('form');
    if (!form) return;
    const contentInput = form.querySelector(`[name="content_${contentKey}"]`);
    if (!contentInput) return;
    const templateType = selectEl.getAttribute('data-template-type') || '';
    const templateId = selectEl.value;
    if (!templateId) {
        contentInput.value = '';
        if (templateType === 'service') {
            setHiddenStepField(form, 'serviceType', '');
        }
        return;
    }
    try {
        const response = await fetch(`/api/pipeline-template/${templateId}`);
        if (!response.ok) {
            notify(t('stepdef.msg.templateLoadFailed'));
            return;
        }
        const template = await response.json();
        contentInput.value = template.content || '';
        contentInput.dispatchEvent(new Event('input', { bubbles: true }));
        if (templateType === 'service' || template.type === 'service') {
            setHiddenStepField(form, 'serviceType', template.serviceType || 'ClusterIP');
        }
    } catch (e) {
        notify((t('stepdef.msg.templateLoadFailed') + ': ') + e.message);
    }
}

function setHiddenStepField(form, key, value) {
    let input = form.querySelector(`[name="content_${key}"]`);
    if (!input) {
        input = document.createElement('input');
        input.type = 'hidden';
        input.name = `content_${key}`;
        form.appendChild(input);
    }
    input.value = value == null ? '' : String(value);
}
window.onStepTemplateSelectChange = onStepTemplateSelectChange;

async function refreshContentConfigFromTemplates(contentConfig) {
    contentConfig = contentConfig || {};
    const bindings = [
        { idKey: 'dockerfileTemplateId', contentKey: 'dockerfileContent' },
        { idKey: 'deploymentTemplateId', contentKey: 'deploymentTemplateContent' },
        { idKey: 'serviceTemplateId', contentKey: 'serviceTemplateContent', serviceTypeKey: 'serviceType' }
    ];
    for (const binding of bindings) {
        const id = contentConfig[binding.idKey];
        if (!id) continue;
        try {
            const response = await fetch(`/api/pipeline-template/${id}`);
            if (!response.ok) continue;
            const template = await response.json();
            if (template.content != null) {
                contentConfig[binding.contentKey] = template.content;
            }
            if (binding.serviceTypeKey && template.serviceType) {
                contentConfig[binding.serviceTypeKey] = template.serviceType;
            }
        } catch (e) {
            console.warn('Refresh linked template failed:', binding.idKey, e);
        }
    }
    return contentConfig;
}

async function onStepDefTypeChange(selectEl) {
    const form = selectEl.closest('form');
    if (!form) return;
    const container = form.querySelector('#stepDefContentFields');
    if (!container) return;
    const stepType = selectEl.value;
    const phaseSelect = form.querySelector('[name="phase"]');
    if (phaseSelect && stepType) {
        phaseSelect.value = inferPhase(stepType);
    }
    container.innerHTML = ('<div style="color:var(--text-3);font-size:13px;">' + t('stepdef.content.loading') + '</div>');
    container.innerHTML = await buildStepContentFieldsHtml(stepType, {});
    wireStepTemplateVarChecks(form);
}

function collectStepDefFormData(form) {
    const name = (form.querySelector('[name="name"]')?.value || '').trim();
    const stepType = form.querySelector('[name="stepType"]')?.value;
    const phase = form.querySelector('[name="phase"]')?.value;
    const description = (form.querySelector('[name="description"]')?.value || '').trim();
    const timeoutRaw = (form.querySelector('[name="timeoutSeconds"]')?.value || '').trim();
    const timeoutSeconds = timeoutRaw ? parseInt(timeoutRaw, 10) : 60;
    if (!name) { notify(t('stepdef.msg.nameRequired')); return null; }
    if (!stepType) { notify(t('stepdef.msg.typeRequired')); return null; }
    if (!phase) { notify(t('stepdef.msg.phaseRequired')); return null; }
    if (!Number.isFinite(timeoutSeconds) || timeoutSeconds < 1) {
        notify(t('stepdef.msg.timeoutInvalid'));
        return null;
    }

    const contentConfig = {};
    const fields = stepContentFields()[stepType] || [];
    for (const field of fields) {
        let val;
        if (field.type === 'custom') {
            const holder = form.querySelector(`[data-content-custom="${CSS.escape(field.key)}"]`);
            if (!holder || typeof field.collect !== 'function') continue;
            val = String(field.collect(holder) || '').trim();
        } else {
            const input = form.querySelector(`[name="content_${field.key}"]`);
            if (!input) continue;
            val = (input.value || '').trim();
        }
        if (field.required && !val) {
            notify(t('stepdef.msg.fieldRequired', { field: field.label }));
            return null;
        }
        if (val) contentConfig[field.key] = val;
    }
    if (stepType === 'docker_build' && !contentConfig.dockerfileContent) {
        notify(t('stepdef.msg.dockerfileRequired'));
        return null;
    }
    if (stepType === 'render_template' && !contentConfig.deploymentTemplateContent) {
        notify(t('stepdef.msg.deploymentRequired'));
        return null;
    }
    const statusRaw = form.querySelector('[name="status"]')?.value;
    const status = statusRaw === '0' ? 0 : 1;
    return { name, stepType, phase, description, timeoutSeconds, contentConfig, status };
}

function showCreateStepDefModal() {
    if (typeof window.showModal !== 'function') {
        notify(t('stepdef.msg.modalNotLoaded'));
        return;
    }
    const stepTypeOptions = Object.entries(stepTypeLabels()).map(([k, v]) => `<option value="${escapeHtml(k)}">${escapeHtml(v)}</option>`).join('');
    const content = `
        <form id="createStepDefForm" class="tpl-form">
            <div class="form-item"><label>${t('stepdef.form.name')}${REQUIRED_MARK}</label><input type="text" name="name" placeholder="${t('stepdef.form.namePlaceholder')}" required></div>
            <div class="form-item">
                <label>${t('stepdef.form.type')}${REQUIRED_MARK}</label>
                <select name="stepType" required onchange="onStepDefTypeChange(this)">
                    <option value="">${t('common.select')}</option>${stepTypeOptions}
                </select>
            </div>
            <div class="form-item">
                <label>${t('stepdef.form.phase')}${REQUIRED_MARK}</label>
                <select name="phase" required>
                    <option value="ci">${t('stepdef.phase.ci')}</option>
                    <option value="cd">${t('stepdef.phase.cd')}</option>
                </select>
            </div>
            <div class="form-item"><label>${t('common.description')}</label><textarea name="description" rows="2"></textarea></div>
            <div class="form-item">
                <label>${t('stepdef.form.timeout')}${REQUIRED_MARK}</label>
                <input type="number" name="timeoutSeconds" value="60" min="1" placeholder="${t('stepdef.form.timeoutPlaceholder')}" required>
            </div>
            <div class="form-item">
                <label>${t('common.status')}</label>
                <select name="status">
                    <option value="1" selected>${t('common.enabled')}</option>
                    <option value="0">${t('common.disabled')}</option>
                </select>
            </div>
            <div style="border-top:1px solid var(--border);padding-top:12px;margin-top:8px;">
                <h4 style="margin:0 0 12px 0;">${t('stepdef.content.title')}</h4>
                <div id="stepDefContentFields"><div style="color:var(--text-3);font-size:13px;">${t('stepdef.content.selectTypeFirst')}</div></div>
            </div>
        </form>
    `;
    window.showModal(t('stepdef.form.createTitle'), content, async () => {
        const form = document.getElementById('createStepDefForm');
        const data = collectStepDefFormData(form);
        if (!data) return;
        if (!await passStepTemplateVarChecks(form)) return;
        try {
            const response = await fetch('/api/pipeline-step-def/create', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(data)
            });
            if (response.ok) {
                notify(t('common.createOk'));
                pipelineStepDefCache = [];
                loadStepDefs();
                if (typeof window.closeModal === 'function') {
                    window.closeModal();
                } else if (typeof closeModal === 'function') {
                    closeModal();
                }
            } else {
                notify((t('common.createFailed') + ': ') + await response.text());
            }
        } catch (e) {
            notify((t('common.createFailed') + ': ') + e.message);
        }
    });
    wireStepTemplateVarChecks(document.getElementById('createStepDefForm'));
}
window.showCreateStepDefModal = showCreateStepDefModal;

function bindAddStepDefButton() {
    const btn = document.getElementById('addStepDefBtn');
    if (!btn || btn.dataset.bound === '1') {
        return;
    }
    btn.dataset.bound = '1';
    btn.addEventListener('click', function (e) {
        e.preventDefault();
        e.stopPropagation();
        showCreateStepDefModal();
    });
}
if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', bindAddStepDefButton);
} else {
    bindAddStepDefButton();
}

async function editStepDef(id) {
    try {
        const response = await fetch(`/api/pipeline-step-def/${id}`);
        if (!response.ok) { notify(t('common.loadFailed')); return; }
        const def = await response.json();
        const contentConfig = await refreshContentConfigFromTemplates(def.contentConfig || {});
        const stepTypeOptions = Object.entries(stepTypeLabels()).map(([k, v]) =>
            `<option value="${escapeHtml(k)}" ${def.stepType === k ? 'selected' : ''}>${escapeHtml(v)}</option>`).join('');
        const content = `
            <form id="editStepDefForm" class="tpl-form">
                <div class="form-item"><label>${t('stepdef.form.name')}${REQUIRED_MARK}</label><input type="text" name="name" value="${escapeHtml(def.name || '')}" required></div>
                <div class="form-item">
                    <label>${t('stepdef.form.type')}${REQUIRED_MARK}</label>
                    <select name="stepType" required onchange="onStepDefTypeChange(this)">
                        ${stepTypeOptions}
                    </select>
                </div>
                <div class="form-item">
                    <label>${t('stepdef.form.phase')}${REQUIRED_MARK}</label>
                    <select name="phase" required>
                        <option value="ci" ${def.phase === 'ci' ? 'selected' : ''}>${t('stepdef.phase.ci')}</option>
                        <option value="cd" ${def.phase === 'cd' ? 'selected' : ''}>${t('stepdef.phase.cd')}</option>
                    </select>
                </div>
                <div class="form-item"><label>${t('common.description')}</label><textarea name="description" rows="2">${escapeHtml(def.description || '')}</textarea></div>
                <div class="form-item">
                <label>${t('stepdef.form.timeout')}${REQUIRED_MARK}</label>
                <input type="number" name="timeoutSeconds" value="${def.timeoutSeconds != null ? def.timeoutSeconds : 60}" min="1" required>
            </div>
                <div class="form-item">
                    <label>${t('common.status')}</label>
                    <select name="status">
                        <option value="1" ${def.status !== 0 ? 'selected' : ''}>${t('common.enabled')}</option>
                        <option value="0" ${def.status === 0 ? 'selected' : ''}>${t('common.disabled')}</option>
                    </select>
                </div>
                <div style="border-top:1px solid var(--border);padding-top:12px;margin-top:8px;">
                    <h4 style="margin:0 0 12px 0;">${t('stepdef.content.title')}</h4>
                    <div id="stepDefContentFields">${await buildStepContentFieldsHtml(def.stepType, contentConfig)}</div>
                </div>
            </form>
        `;
        showModal(t('stepdef.form.editTitle'), content, async () => {
            const form = document.getElementById('editStepDefForm');
            const data = collectStepDefFormData(form);
            if (!data) return;
            if (!await passStepTemplateVarChecks(form)) return;
            try {
                const resp = await fetch(`/api/pipeline-step-def/${id}`, {
                    method: 'PUT',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify(data)
                });
                if (resp.ok) {
                    notify(t('common.updatedOk'));
                    pipelineStepDefCache = [];
                    loadStepDefs();
                    closeModal();
                } else {
                    notify((t('common.updatedFailed') + ': ') + await resp.text());
                }
            } catch (e) {
                notify((t('common.updatedFailed') + ': ') + e.message);
            }
        });
        wireStepTemplateVarChecks(document.getElementById('editStepDefForm'));
    } catch (e) {
        notify((t('common.loadFailed') + ': ') + e.message);
    }
}
window.editStepDef = editStepDef;
window.onStepDefTypeChange = onStepDefTypeChange;

async function deleteStepDef(id) {
    if (!await confirmDialog(t('stepdef.msg.deleteConfirm'))) return;
    try {
        const response = await fetch(`/api/pipeline-step-def/${id}`, { method: 'DELETE' });
        if (response.ok) {
            notify(t('common.deleteOk'));
            pipelineStepDefCache = [];
            loadStepDefs();
        } else {
            notify(t('common.deleteFailed'));
        }
    } catch (e) {
        notify(t('common.deleteFailed'));
    }
}
window.deleteStepDef = deleteStepDef;

// 供 Pipeline 模板使用的步骤下拉选项
function renderStepDefSelectOptions(phase, selectedId) {
    const defs = (window.pipelineStepDefCache || pipelineStepDefCache || [])
        .filter(d => d.phase === phase && (d.status === 1 || d.status == null));
    let html = ('<option value="">' + t('stepdef.select') + '</option>');
    if (!defs.length) {
        html += ('<option value="" disabled>' + t('stepdef.selectNone') + '</option>');
    }
    defs.forEach(d => {
        const selected = selectedId != null && String(selectedId) === String(d.id) ? ' selected' : '';
        html += `<option value="${d.id}"${selected}>${escapeHtml(d.name)} (${escapeHtml(getStepTypeLabel(d.stepType))})</option>`;
    });
    return html;
}
window.renderStepDefSelectOptions = renderStepDefSelectOptions;

// ============================================
// 方案 A：流水线步骤差异覆盖（第一期）
// 步骤定义 = 默认；流水线只写差异项；空值 = 不覆盖
// ============================================

/** 第一期允许在流水线侧覆盖的 content_config 键 */
const PIPELINE_STEP_OVERRIDE_KEYS = {
    build: ['buildCommand'],
    docker_build: ['dockerfileTemplateId', 'dockerfileContent'],
    render_template: [
        'deploymentTemplateId', 'deploymentTemplateContent',
        'serviceTemplateId', 'serviceTemplateContent'
    ]
};

function getStepDefById(id) {
    if (id == null || id === '') return null;
    return (window.pipelineStepDefCache || pipelineStepDefCache || [])
        .find(d => String(d.id) === String(id)) || null;
}

function findStepContentField(stepType, key) {
    const fields = stepContentFields()[stepType] || [];
    return fields.find(f => f.key === key) || null;
}

/**
 * 构建流水线步骤「差异配置」面板 HTML。
 * @param {string} stepId DOM step id
 * @param {string} stepType 步骤类型
 * @param {object} overrides 已有 parameters
 * @param {number|null} timeoutOverride 引擎超时覆盖
 * @param {object} defaults 步骤定义默认 contentConfig（作 placeholder）
 * @param {number|null} defaultTimeout 步骤定义默认超时
 */
async function buildPipelineStepOverrideHtml(stepId, stepType, overrides, timeoutOverride, defaults, defaultTimeout) {
    overrides = overrides || {};
    defaults = defaults || {};
    const keys = PIPELINE_STEP_OVERRIDE_KEYS[stepType] || [];
    const parts = [];

    parts.push(`
        <div class="pipeline-step-override" data-step-id="${escapeHtml(stepId)}"
             style="margin-top:10px;padding:10px 12px;background:#f8fafc;border:1px dashed #cbd5e1;border-radius:6px;">
            <div style="display:flex;justify-content:space-between;align-items:center;margin-bottom:8px;">
                <strong style="font-size:12px;color:#334155;">${t('stepdef.override.title')}</strong>
                <span style="font-size:11px;color:#64748b;">${t('stepdef.override.hint')}</span>
            </div>
            <div class="form-item" style="margin:0 0 8px;">
                <label style="font-size:12px;">${t('stepdef.override.timeout')}</label>
                <input type="number" name="override_timeout_${escapeHtml(stepId)}" min="1"
                       value="${escapeHtml(timeoutOverride != null && timeoutOverride >= 1 ? timeoutOverride : '')}"
                       placeholder="${t('stepdef.override.default')} ${escapeHtml(defaultTimeout != null ? defaultTimeout : 60)}"
                       style="width:100%;box-sizing:border-box;font-size:13px;">
            </div>
    `);

    if (!keys.length) {
        parts.push(`<div style="font-size:12px;color:#94a3b8;">${t('stepdef.override.none')}</div></div>`);
        return parts.join('');
    }

    for (const key of keys) {
        const field = findStepContentField(stepType, key) || { key, label: key, type: 'text' };
        const value = overrides[key] != null ? overrides[key] : '';
        const hintDefault = defaults[key] != null ? String(defaults[key]) : '';
        const label = (field.label || key) + t('stepdef.override.suffix');

        if (field.type === 'template-select') {
            const templates = await loadStepTemplates(field.templateType);
            parts.push(`
                <div class="form-item" style="margin:0 0 8px;">
                    <label style="font-size:12px;">${escapeHtml(label)}</label>
                    <select name="override_${escapeHtml(key)}_${escapeHtml(stepId)}"
                            data-override-key="${escapeHtml(key)}"
                            data-content-key="${escapeHtml(field.contentKey || '')}"
                            data-template-type="${escapeHtml(field.templateType || '')}"
                            onchange="onPipelineOverrideTemplateChange(this, '${escapeHtml(stepId)}')"
                            style="width:100%;font-size:13px;">
                        ${renderTemplateSelectOptions(templates, value, t('stepdef.override.keepDefault'))}
                    </select>
                </div>
            `);
            continue;
        }

        if (field.type === 'textarea' || key.endsWith('Content') || key === 'buildCommand') {
            const ph = hintDefault
                ? t('stepdef.override.keepDefaultSet')
                : (field.placeholder || t('stepdef.override.keepEmpty'));
            const textareaName = `override_${key}_${stepId}`;
            parts.push(`
                <div class="form-item" style="margin:0 0 8px;">
                    <label style="font-size:12px;">${escapeHtml(label)}</label>
                    <textarea name="${escapeHtml(textareaName)}"
                              data-override-key="${escapeHtml(key)}"
                              rows="${escapeHtml(field.rows || 4)}" placeholder="${escapeHtml(ph)}"
                              style="width:100%;box-sizing:border-box;font-family:ui-monospace,SFMono-Regular,Menlo,Monaco,Consolas,monospace;font-size:12px;">${escapeHtml(value)}</textarea>
                    ${appendTemplateVarCheckHtml(textareaName, key)}
                </div>
            `);
            continue;
        }

        parts.push(`
            <div class="form-item" style="margin:0 0 8px;">
                <label style="font-size:12px;">${escapeHtml(label)}</label>
                <input type="text" name="override_${escapeHtml(key)}_${escapeHtml(stepId)}"
                       data-override-key="${escapeHtml(key)}"
                       value="${escapeHtml(value)}"
                       placeholder="${escapeHtml(hintDefault || field.placeholder || t('stepdef.override.keepEmpty'))}"
                       style="width:100%;box-sizing:border-box;font-size:13px;">
            </div>
        `);
    }

    parts.push('</div>');
    return parts.join('');
}

async function onPipelineOverrideTemplateChange(selectEl, stepId) {
    const contentKey = selectEl.getAttribute('data-content-key');
    if (!contentKey) return;
    const stepItem = document.querySelector(`[data-step-id="${stepId}"]`);
    if (!stepItem) return;
    const contentInput = stepItem.querySelector(`[name="override_${contentKey}_${stepId}"]`);
    if (!contentInput) return;
    const templateId = selectEl.value;
    if (!templateId) {
        // 不覆盖模版时，也不强制清空已手写正文（用户可单独覆盖正文）
        return;
    }
    try {
        const response = await fetch(`/api/pipeline-template/${templateId}`);
        if (!response.ok) {
            notify(t('stepdef.msg.templateLoadFailed'));
            return;
        }
        const template = await response.json();
        contentInput.value = template.content || '';
        contentInput.dispatchEvent(new Event('input', { bubbles: true }));
    } catch (e) {
        notify((t('stepdef.msg.templateLoadFailed') + ': ') + e.message);
    }
}
window.onPipelineOverrideTemplateChange = onPipelineOverrideTemplateChange;

/**
 * 根据所选步骤定义刷新差异配置面板。
 */
async function refreshPipelineStepOverridePanel(stepId, preserved) {
    const stepItem = document.querySelector(`[data-step-id="${stepId}"]`);
    if (!stepItem) return;
    const select = stepItem.querySelector(`[name="stepTemplateId_${stepId}"]`);
    let panel = stepItem.querySelector('.pipeline-step-override-host');
    if (!panel) {
        panel = document.createElement('div');
        panel.className = 'pipeline-step-override-host';
        stepItem.appendChild(panel);
    }

    const defId = select ? select.value : '';
    const def = getStepDefById(defId);
    if (!def) {
        panel.innerHTML = '';
        return;
    }

    const overrides = (preserved && preserved.parameters) || {};
    const timeoutOverride = preserved && preserved.timeoutSeconds != null ? preserved.timeoutSeconds : null;
    panel.innerHTML = await buildPipelineStepOverrideHtml(
        stepId,
        def.stepType,
        overrides,
        timeoutOverride,
        def.contentConfig || {},
        def.timeoutSeconds
    );
    wireStepTemplateVarChecks(stepItem.closest('form') || stepItem);
}
window.refreshPipelineStepOverridePanel = refreshPipelineStepOverridePanel;

/**
 * 从步骤 DOM 收集差异覆盖（仅非空值）。
 * @returns {{ parameters: Object, timeoutSeconds: number|null }}
 */
function collectPipelineStepOverrides(stepItem, stepId) {
    const parameters = {};
    const inputs = stepItem.querySelectorAll('[data-override-key]');
    inputs.forEach(input => {
        const key = input.getAttribute('data-override-key');
        if (!key) return;
        const val = (input.value || '').trim();
        if (val) {
            parameters[key] = val;
        }
    });

    let timeoutSeconds = null;
    const timeoutInput = stepItem.querySelector(`[name="override_timeout_${stepId}"]`);
    if (timeoutInput) {
        const raw = (timeoutInput.value || '').trim();
        if (raw) {
            const n = parseInt(raw, 10);
            if (Number.isFinite(n) && n >= 1) {
                timeoutSeconds = n;
            }
        }
    }
    return { parameters, timeoutSeconds };
}
window.collectPipelineStepOverrides = collectPipelineStepOverrides;
window.PIPELINE_STEP_OVERRIDE_KEYS = PIPELINE_STEP_OVERRIDE_KEYS;
window.getStepDefById = getStepDefById;

