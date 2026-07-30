// ============================================
// Pipeline 步骤定义模块（可复用步骤）
// ============================================

const STEP_TYPE_LABELS = {
    checkout: '拉取代码',
    build: '代码构建',
    docker_build: '镜像制作',
    push_image: '上传镜像',
    deploy: '开始部署',
    check_deploy: '检查部署状态',
    render_template: '渲染模版',
    clean: '清理缓存',
    clean_workspace: '清理空间',
    rollback: '回滚',
    notify: '通知'
};

const CI_STEP_TYPES = ['checkout', 'build', 'docker_build', 'push_image', 'clean', 'clean_workspace'];
const CD_STEP_TYPES = ['deploy', 'check_deploy', 'render_template', 'rollback', 'notify'];

const STEP_CONTENT_FIELDS = {
    checkout: [
        { key: 'branch', label: '默认分支（可选）', type: 'text', placeholder: '留空则使用任务分支' }
    ],
    build: [
        { key: 'buildCommand', label: '构建命令 *', type: 'textarea', rows: 3, placeholder: 'mvn clean package -DskipTests', required: true }
    ],
    docker_build: [
        { key: 'dockerfileTemplateId', label: 'Dockerfile 模版 *', type: 'template-select', templateType: 'dockerfile', contentKey: 'dockerfileContent', required: true },
        { key: 'dockerfilePath', label: 'Dockerfile 路径', type: 'text', placeholder: 'Dockerfile', defaultValue: 'Dockerfile' },
        { key: 'dockerfileContent', label: 'Dockerfile 内容（由模版带入，可微调）', type: 'textarea', rows: 10, placeholder: '请先选择 Dockerfile 模版' },
        { key: 'dockerBuildArgs', label: 'docker build 额外参数（可选）', type: 'text', placeholder: '例如：--no-cache --pull' },
        { key: 'dockerBuildCommand', label: 'docker build 命令 *', type: 'text', required: true,
          placeholder: 'docker build -f ${dockerfilePath} -t ${harbor}/${namespace}/${serviceName}:${tag} ${workspace}',
          defaultValue: 'docker build -f ${dockerfilePath} -t ${harbor}/${namespace}/${serviceName}:${tag} ${workspace}',
          hint: '可用变量：${harbor} ${namespace} ${serviceName} ${tag} ${dockerfilePath} ${workspace}' }
    ],
    push_image: [
        { type: 'hint', text: '本步骤对应「镜像制作」里的 docker build：自动使用上一步输出的镜像地址（${imageFullName}），先 docker login，再执行下面的 docker push。' },
        { key: 'dockerPushCommand', label: 'docker push 命令 *', type: 'text', required: true,
          placeholder: 'docker push ${imageFullName}',
          defaultValue: 'docker push ${imageFullName}',
          hint: '可用变量：${imageFullName} / ${image}（来自镜像制作步骤，二者相同）' },
        { key: 'dockerPushArgs', label: 'docker push 额外参数（可选）', type: 'text', placeholder: '一般留空；若填写自定义命令则此项不生效' }
    ],
    render_template: [
        { key: 'deploymentTemplateId', label: 'Deployment 模版 *', type: 'template-select', templateType: 'deployment', contentKey: 'deploymentTemplateContent', required: true },
        { key: 'serviceTemplateId', label: 'Service 模版（可选）', type: 'template-select', templateType: 'service', contentKey: 'serviceTemplateContent' },
        { key: 'serviceType', type: 'hidden' },
        { key: 'outputDir', label: '输出目录', type: 'text', placeholder: 'manifests', defaultValue: 'manifests' },
        { key: 'deploymentTemplateContent', label: 'Deployment 内容（由模版带入，可微调）', type: 'textarea', rows: 10, placeholder: '请先选择 Deployment 模版' },
        { key: 'serviceTemplateContent', label: 'Service 内容（由模版带入，可微调）', type: 'textarea', rows: 6, placeholder: '可选：选择 Service 模版后自动填充' }
    ],
    deploy: [
        { key: 'deployScript', label: '额外部署脚本（可选）', type: 'textarea', rows: 3, placeholder: 'kubectl apply -f ...' }
    ],
    check_deploy: [
        { key: 'timeoutSeconds', label: '超时秒数', type: 'text', placeholder: '300', defaultValue: '300' }
    ],
    clean: [
        { key: 'cleanCommand', label: '清理命令', type: 'textarea', rows: 2, placeholder: 'mvn clean' }
    ],
    clean_workspace: [
        { key: 'workspaceBase', label: '工作目录根路径', type: 'text', placeholder: '~/opsflow', defaultValue: '~/opsflow' },
        { key: 'workspacePath', label: '完整工作目录（可选，留空则按服务/环境/分支自动生成）', type: 'text', placeholder: '~/opsflow/demo/dev/develop' },
        { key: 'cleanDocker', label: '同时清理 Docker 缓存', type: 'text', placeholder: 'true / false', defaultValue: 'false' }
    ],
    rollback: [
        { key: 'toRevision', label: '回滚到指定 revision（可选，留空则回滚上一版本）', type: 'text', placeholder: '例如 3' },
        { key: 'waitRollout', label: '等待回滚完成', type: 'text', placeholder: 'true / false', defaultValue: 'true' },
        { key: 'timeoutSeconds', label: '等待超时秒数', type: 'text', placeholder: '300', defaultValue: '300' }
    ],
    notify: [
        { key: 'notifyWebhook', label: '通知 Webhook', type: 'text', placeholder: 'https://...' }
    ]
};

let pipelineStepDefCache = [];
const stepTemplateCache = {};

async function loadStepTemplates(type, force) {
    if (!force && stepTemplateCache[type]) {
        return stepTemplateCache[type];
    }
    try {
        const response = await fetch(`/api/pipeline-template/list?type=${encodeURIComponent(type)}`);
        if (response.ok) {
            const list = (await response.json() || []).filter(t => t.status === 1 || t.status == null);
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
    let html = `<option value="">${escStepHtml(emptyLabel || '请选择模版')}</option>`;
    if (!templates.length) {
        html += '<option value="" disabled>暂无模版，请先在「模版管理」中创建</option>';
        return html;
    }
    templates.forEach(t => {
        const selected = selectedId != null && String(selectedId) === String(t.id) ? ' selected' : '';
        html += `<option value="${t.id}"${selected}>${escStepHtml(t.name)}</option>`;
    });
    return html;
}

function escStepHtml(text) {
    if (text == null) return '';
    return String(text).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
}

function inferPhase(stepType) {
    return CI_STEP_TYPES.includes(stepType) ? 'ci' : 'cd';
}

function getStepTypeLabel(stepType) {
    return STEP_TYPE_LABELS[stepType] || stepType;
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
    list.innerHTML = '<tr><td colspan="7" style="text-align:center;padding:40px;color:#999;">加载中...</td></tr>';
    const defs = await loadPipelineStepDefs(true);
    renderStepDefList(defs);
}
window.loadStepDefs = loadStepDefs;

function renderStepDefList(defs) {
    const list = document.getElementById('stepDefList');
    if (!list) return;
    if (!defs.length) {
        list.innerHTML = '<tr><td colspan="7" style="text-align:center;padding:40px;color:#999;">暂无步骤，请点击「添加步骤」创建</td></tr>';
        return;
    }
    list.innerHTML = defs.map(def => {
        const phaseText = def.phase === 'cd' ? 'CD' : 'CI';
        const statusText = (def.status === 1 || def.status == null)
            ? '<span style="color:#059669;">启用</span>'
            : '<span style="color:#dc2626;">禁用</span>';
        const timeoutText = def.timeoutSeconds != null ? def.timeoutSeconds : 60;
        return `
            <tr>
                <td>${escStepHtml(def.name)}</td>
                <td>${getStepTypeLabel(def.stepType)}</td>
                <td>${phaseText}</td>
                <td>${timeoutText}</td>
                <td>${escStepHtml(def.description || '-')}</td>
                <td>${statusText}</td>
                <td>
                    <button class="btn-edit" onclick="editStepDef(${def.id})">修改</button>
                    <button class="btn-danger" onclick="deleteStepDef(${def.id})">删除</button>
                </td>
            </tr>
        `;
    }).join('');
}

async function buildStepContentFieldsHtml(stepType, contentConfig) {
    contentConfig = contentConfig || {};
    const fields = STEP_CONTENT_FIELDS[stepType] || [];
    if (!fields.length) {
        return '<div style="color:#666;font-size:13px;padding:8px 0;">此步骤类型无需额外配置</div>';
    }
    const parts = [];
    for (const field of fields) {
        const value = contentConfig[field.key] != null ? contentConfig[field.key] : (field.defaultValue || '');
        const required = field.required ? ' required' : '';
        if (field.type === 'hint') {
            parts.push(`
                <div style="margin:8px 0 12px;padding:10px 12px;background:#f8fafc;border:1px solid #e5e7eb;border-radius:6px;font-size:13px;color:#4b5563;line-height:1.5;">
                    ${escStepHtml(field.text || '')}
                </div>
            `);
            continue;
        }
        if (field.type === 'template-select') {
            const templates = await loadStepTemplates(field.templateType);
            const emptyLabel = field.required ? '请选择模版' : '不关联（可选）';
            parts.push(`
                <div class="form-item">
                    <label>${field.label}</label>
                    <select name="content_${field.key}" data-content-key="${escStepHtml(field.contentKey || '')}"
                            data-template-type="${escStepHtml(field.templateType || '')}"
                            onchange="onStepTemplateSelectChange(this)"${required}>
                        ${renderTemplateSelectOptions(templates, value, emptyLabel)}
                    </select>
                    <small style="color:#666;">从「模版管理」选择；执行时会自动使用模版最新内容</small>
                </div>
            `);
            continue;
        }
        if (field.type === 'hidden') {
            parts.push(`<input type="hidden" name="content_${field.key}" value="${escStepHtml(value)}">`);
            continue;
        }
        if (field.type === 'textarea') {
            parts.push(`
                <div class="form-item">
                    <label>${field.label}</label>
                    <textarea name="content_${field.key}" rows="${field.rows || 3}" placeholder="${escStepHtml(field.placeholder || '')}"${required}>${escStepHtml(value)}</textarea>
                    ${field.hint ? `<small style="color:#666;">${escStepHtml(field.hint)}</small>` : ''}
                </div>
            `);
            continue;
        }
        parts.push(`
            <div class="form-item">
                <label>${field.label}</label>
                <input type="text" name="content_${field.key}" value="${escStepHtml(value)}"
                       placeholder="${escStepHtml(field.placeholder || '')}"
                       style="width:100%;box-sizing:border-box;font-family:ui-monospace,SFMono-Regular,Menlo,Monaco,Consolas,monospace;font-size:13px;"${required}>
                ${field.hint ? `<small style="color:#666;">${escStepHtml(field.hint)}</small>` : ''}
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
            alert('加载模版失败');
            return;
        }
        const template = await response.json();
        contentInput.value = template.content || '';
        if (templateType === 'service' || template.type === 'service') {
            setHiddenStepField(form, 'serviceType', template.serviceType || 'ClusterIP');
        }
    } catch (e) {
        alert('加载模版失败: ' + e.message);
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
            console.warn('刷新关联模版失败:', binding.idKey, e);
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
    container.innerHTML = '<div style="color:#666;font-size:13px;">加载配置中...</div>';
    container.innerHTML = await buildStepContentFieldsHtml(stepType, {});
}

function collectStepDefFormData(form) {
    const name = (form.querySelector('[name="name"]')?.value || '').trim();
    const stepType = form.querySelector('[name="stepType"]')?.value;
    const phase = form.querySelector('[name="phase"]')?.value;
    const description = (form.querySelector('[name="description"]')?.value || '').trim();
    const timeoutRaw = (form.querySelector('[name="timeoutSeconds"]')?.value || '').trim();
    const timeoutSeconds = timeoutRaw ? parseInt(timeoutRaw, 10) : 60;
    if (!name) { alert('步骤名称不能为空'); return null; }
    if (!stepType) { alert('请选择步骤类型'); return null; }
    if (!phase) { alert('请选择阶段'); return null; }
    if (!Number.isFinite(timeoutSeconds) || timeoutSeconds < 1) {
        alert('超时时间必须是大于0的整数（秒）');
        return null;
    }

    const contentConfig = {};
    const fields = STEP_CONTENT_FIELDS[stepType] || [];
    for (const field of fields) {
        const input = form.querySelector(`[name="content_${field.key}"]`);
        if (!input) continue;
        const val = (input.value || '').trim();
        if (field.required && !val) {
            alert(`请填写 ${field.label.replace(' *', '')}`);
            return null;
        }
        if (val) contentConfig[field.key] = val;
    }
    if (stepType === 'docker_build' && !contentConfig.dockerfileContent) {
        alert('请选择 Dockerfile 模版并确认内容已填充');
        return null;
    }
    if (stepType === 'render_template' && !contentConfig.deploymentTemplateContent) {
        alert('请选择 Deployment 模版并确认内容已填充');
        return null;
    }
    const statusRaw = form.querySelector('[name="status"]')?.value;
    const status = statusRaw === '0' ? 0 : 1;
    return { name, stepType, phase, description, timeoutSeconds, contentConfig, status };
}

function showCreateStepDefModal() {
    if (typeof window.showModal !== 'function') {
        alert('模态框功能未加载，请刷新页面');
        return;
    }
    const stepTypeOptions = Object.entries(STEP_TYPE_LABELS).map(([k, v]) => `<option value="${k}">${v}</option>`).join('');
    const content = `
        <form id="createStepDefForm" style="max-height:75vh;overflow-y:auto;">
            <div class="form-item"><label>步骤名称 *</label><input type="text" name="name" placeholder="例如: 我的镜像制作" required></div>
            <div class="form-item">
                <label>步骤类型 *</label>
                <select name="stepType" required onchange="onStepDefTypeChange(this)">
                    <option value="">请选择</option>${stepTypeOptions}
                </select>
            </div>
            <div class="form-item">
                <label>阶段 *</label>
                <select name="phase" required>
                    <option value="ci">CI（构建）</option>
                    <option value="cd">CD（部署）</option>
                </select>
            </div>
            <div class="form-item"><label>描述</label><textarea name="description" rows="2"></textarea></div>
            <div class="form-item">
                <label>超时时间（秒） *</label>
                <input type="number" name="timeoutSeconds" value="60" min="1" placeholder="默认 60" required>
            </div>
            <div class="form-item">
                <label>状态</label>
                <select name="status">
                    <option value="1" selected>启用</option>
                    <option value="0">禁用</option>
                </select>
            </div>
            <div style="border-top:1px solid #eee;padding-top:12px;margin-top:8px;">
                <h4 style="margin:0 0 12px 0;">步骤自定义内容</h4>
                <div id="stepDefContentFields"><div style="color:#666;font-size:13px;">请先选择步骤类型</div></div>
            </div>
        </form>
    `;
    window.showModal('添加步骤', content, async () => {
        const form = document.getElementById('createStepDefForm');
        const data = collectStepDefFormData(form);
        if (!data) return;
        try {
            const response = await fetch('/api/pipeline-step-def/create', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(data)
            });
            if (response.ok) {
                alert('创建成功');
                pipelineStepDefCache = [];
                loadStepDefs();
                if (typeof window.closeModal === 'function') {
                    window.closeModal();
                } else if (typeof closeModal === 'function') {
                    closeModal();
                }
            } else {
                alert('创建失败: ' + await response.text());
            }
        } catch (e) {
            alert('创建失败: ' + e.message);
        }
    });
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
        if (!response.ok) { alert('加载失败'); return; }
        const def = await response.json();
        const contentConfig = await refreshContentConfigFromTemplates(def.contentConfig || {});
        const stepTypeOptions = Object.entries(STEP_TYPE_LABELS).map(([k, v]) =>
            `<option value="${k}" ${def.stepType === k ? 'selected' : ''}>${v}</option>`).join('');
        const content = `
            <form id="editStepDefForm" style="max-height:75vh;overflow-y:auto;">
                <div class="form-item"><label>步骤名称 *</label><input type="text" name="name" value="${escStepHtml(def.name || '')}" required></div>
                <div class="form-item">
                    <label>步骤类型 *</label>
                    <select name="stepType" required onchange="onStepDefTypeChange(this)">
                        ${stepTypeOptions}
                    </select>
                </div>
                <div class="form-item">
                    <label>阶段 *</label>
                    <select name="phase" required>
                        <option value="ci" ${def.phase === 'ci' ? 'selected' : ''}>CI（构建）</option>
                        <option value="cd" ${def.phase === 'cd' ? 'selected' : ''}>CD（部署）</option>
                    </select>
                </div>
                <div class="form-item"><label>描述</label><textarea name="description" rows="2">${escStepHtml(def.description || '')}</textarea></div>
                <div class="form-item">
                <label>超时时间（秒） *</label>
                <input type="number" name="timeoutSeconds" value="${def.timeoutSeconds != null ? def.timeoutSeconds : 60}" min="1" required>
            </div>
                <div class="form-item">
                    <label>状态</label>
                    <select name="status">
                        <option value="1" ${def.status !== 0 ? 'selected' : ''}>启用</option>
                        <option value="0" ${def.status === 0 ? 'selected' : ''}>禁用</option>
                    </select>
                </div>
                <div style="border-top:1px solid #eee;padding-top:12px;margin-top:8px;">
                    <h4 style="margin:0 0 12px 0;">步骤自定义内容</h4>
                    <div id="stepDefContentFields">${await buildStepContentFieldsHtml(def.stepType, contentConfig)}</div>
                </div>
            </form>
        `;
        showModal('编辑步骤', content, async () => {
            const form = document.getElementById('editStepDefForm');
            const data = collectStepDefFormData(form);
            if (!data) return;
            try {
                const resp = await fetch(`/api/pipeline-step-def/${id}`, {
                    method: 'PUT',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify(data)
                });
                if (resp.ok) {
                    alert('更新成功');
                    pipelineStepDefCache = [];
                    loadStepDefs();
                    closeModal();
                } else {
                    alert('更新失败: ' + await resp.text());
                }
            } catch (e) {
                alert('更新失败: ' + e.message);
            }
        });
    } catch (e) {
        alert('加载失败: ' + e.message);
    }
}
window.editStepDef = editStepDef;
window.onStepDefTypeChange = onStepDefTypeChange;

async function deleteStepDef(id) {
    if (!confirm('确定删除此步骤？已被 Pipeline 模板引用的步骤删除后需重新配置模板。')) return;
    try {
        const response = await fetch(`/api/pipeline-step-def/${id}`, { method: 'DELETE' });
        if (response.ok) {
            alert('删除成功');
            pipelineStepDefCache = [];
            loadStepDefs();
        } else {
            alert('删除失败');
        }
    } catch (e) {
        alert('删除失败');
    }
}
window.deleteStepDef = deleteStepDef;

// 供 Pipeline 模板使用的步骤下拉选项
function renderStepDefSelectOptions(phase, selectedId) {
    const defs = (window.pipelineStepDefCache || pipelineStepDefCache || [])
        .filter(d => d.phase === phase && (d.status === 1 || d.status == null));
    let html = '<option value="">请选择步骤</option>';
    if (!defs.length) {
        html += '<option value="" disabled>暂无步骤，请先在「步骤管理」中创建</option>';
    }
    defs.forEach(d => {
        const selected = selectedId != null && String(selectedId) === String(d.id) ? ' selected' : '';
        html += `<option value="${d.id}"${selected}>${escStepHtml(d.name)} (${getStepTypeLabel(d.stepType)})</option>`;
    });
    return html;
}
window.renderStepDefSelectOptions = renderStepDefSelectOptions;
