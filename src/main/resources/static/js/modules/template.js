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

const DEFAULT_DOCKERFILE_CONTENT = `FROM openjdk:8-jre-slim
WORKDIR /app
COPY target/*.jar app.jar
EXPOSE \${servicePort}
ENTRYPOINT ["java", "-jar", "app.jar"]`;

const DEFAULT_DEPLOYMENT_CONTENT = `apiVersion: apps/v1
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
      restartPolicy: Always
      # 全局共享：Harbor 拉镜像凭证（各服务通用）
      imagePullSecrets:
      - name: harbor-registry-secret
      containers:
      - name: \${serviceName}
        image: \${image}
        ports:
        - containerPort: \${servicePort}
        # ---------- 配置注入（同一模版覆盖：只用公用 / 公用+私有）----------
        # 公用 ConfigMap/Secret：多项目共用；optional=true 表示集群未创建时不阻断部署
        # 若要求必须存在，把对应项的 optional 改为 false
        envFrom:
        - configMapRef:
            name: shared-config
            optional: true
        - secretRef:
            name: shared-secret
            optional: true
        # 服务私有：有 \${serviceName}-config / \${serviceName}-secret 则自动挂载；没有则忽略
        - configMapRef:
            name: \${serviceName}-config
            optional: true
        - secretRef:
            name: \${serviceName}-secret
            optional: true
        startupProbe:
          tcpSocket:
            port: \${servicePort}
          initialDelaySeconds: 30
          periodSeconds: 5
          timeoutSeconds: 2
          failureThreshold: 40
        livenessProbe:
          tcpSocket:
            port: \${servicePort}
          initialDelaySeconds: 30
          periodSeconds: 5
          timeoutSeconds: 3
          failureThreshold: 40
        # readinessProbe:
        #   httpGet:
        #     path: /api/health
        #     port: \${servicePort}
        #     scheme: HTTP
        #   initialDelaySeconds: 20
        #   periodSeconds: 10
        #   timeoutSeconds: 2
        #   failureThreshold: 5
        #   successThreshold: 1
        resources:
          requests:
            cpu: "100m"
          limits:
            cpu: "4"
        volumeMounts:
        - name: host-localtime
          mountPath: /etc/localtime
          readOnly: true
        - name: host-timezone
          mountPath: /etc/timezone
          readOnly: true
        - name: host-data-logs
          mountPath: /data/logs
      volumes:
      - name: host-localtime
        hostPath:
          path: /etc/localtime
          type: File
      - name: host-timezone
        hostPath:
          path: /etc/timezone
          type: FileOrCreate
      - name: host-data-logs
        hostPath:
          path: /data/logs
          type: DirectoryOrCreate`;

const DEFAULT_SERVICE_CONTENT = `apiVersion: v1
kind: Service
metadata:
  name: \${serviceName}-svc
  namespace: \${namespace}
  labels:
    app: \${serviceName}-svc
    monitor: "true"
spec:
  # 可选值: ClusterIP / NodePort / LoadBalancer（由模版「服务类型」下拉注入）
  type: \${serviceType}
  selector:
    app: \${serviceName}
  ports:
  - name: http
    # 暴露端口和容器端口一致
    port: \${servicePort}
    # Pod 容器端口
    targetPort: \${servicePort}`;

function escTemplateHtml(text) {
    if (text == null) return '';
    return String(text).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
}

async function fetchTemplates(type) {
    const response = await fetch(`/api/pipeline-template/list?type=${encodeURIComponent(type)}`);
    if (!response.ok) {
        throw new Error(await response.text() || '加载失败');
    }
    return await response.json() || [];
}

function renderStatusCell(status) {
    return (status === 1 || status == null)
        ? '<span class="status-tag is-on">启用</span>'
        : '<span class="status-tag is-off">禁用</span>';
}

async function loadDockerfileTemplates() {
    const list = document.getElementById('dockerfileTemplateList');
    if (!list) return;
    list.innerHTML = '<tr><td colspan="5" style="text-align:center;padding:40px;color:var(--text-4);">加载中...</td></tr>';
    try {
        const templates = await fetchTemplates('dockerfile');
        if (!templates.length) {
            list.innerHTML = '<tr><td colspan="5" style="text-align:center;padding:40px;color:var(--text-4);">暂无 Dockerfile 模版</td></tr>';
            return;
        }
        list.innerHTML = templates.map(t => `
            <tr>
                <td>${escTemplateHtml(t.name)}</td>
                <td>${escTemplateHtml(t.description || '-')}</td>
                <td>${escTemplateHtml(t.baseImage || '-')}</td>
                <td>${renderStatusCell(t.status)}</td>
                <td>
                    <button class="btn-edit" onclick="editPipelineTemplate('dockerfile', ${t.id})">修改</button>
                    <button class="btn-danger" onclick="deletePipelineTemplate(${t.id}, 'dockerfile')">删除</button>
                </td>
            </tr>
        `).join('');
    } catch (e) {
        list.innerHTML = `<tr><td colspan="5" style="text-align:center;padding:40px;color:#dc2626;">加载失败: ${escTemplateHtml(e.message)}</td></tr>`;
    }
}
window.loadDockerfileTemplates = loadDockerfileTemplates;

async function loadDeploymentTemplates() {
    const list = document.getElementById('deploymentTemplateList');
    if (!list) return;
    list.innerHTML = '<tr><td colspan="4" style="text-align:center;padding:40px;color:var(--text-4);">加载中...</td></tr>';
    try {
        const templates = await fetchTemplates('deployment');
        if (!templates.length) {
            list.innerHTML = '<tr><td colspan="4" style="text-align:center;padding:40px;color:var(--text-4);">暂无 Deployment 模版</td></tr>';
            return;
        }
        list.innerHTML = templates.map(t => `
            <tr>
                <td>${escTemplateHtml(t.name)}</td>
                <td>${escTemplateHtml(t.description || '-')}</td>
                <td>${renderStatusCell(t.status)}</td>
                <td>
                    <button class="btn-edit" onclick="editPipelineTemplate('deployment', ${t.id})">修改</button>
                    <button class="btn-danger" onclick="deletePipelineTemplate(${t.id}, 'deployment')">删除</button>
                </td>
            </tr>
        `).join('');
    } catch (e) {
        list.innerHTML = `<tr><td colspan="4" style="text-align:center;padding:40px;color:#dc2626;">加载失败: ${escTemplateHtml(e.message)}</td></tr>`;
    }
}
window.loadDeploymentTemplates = loadDeploymentTemplates;

async function loadServiceTemplates() {
    const list = document.getElementById('serviceTemplateList');
    if (!list) return;
    list.innerHTML = '<tr><td colspan="5" style="text-align:center;padding:40px;color:var(--text-4);">加载中...</td></tr>';
    try {
        const templates = await fetchTemplates('service');
        if (!templates.length) {
            list.innerHTML = '<tr><td colspan="5" style="text-align:center;padding:40px;color:var(--text-4);">暂无 Service 模版</td></tr>';
            return;
        }
        list.innerHTML = templates.map(t => `
            <tr>
                <td>${escTemplateHtml(t.name)}</td>
                <td>${escTemplateHtml(t.description || '-')}</td>
                <td>${escTemplateHtml(t.serviceType || '-')}</td>
                <td>${renderStatusCell(t.status)}</td>
                <td>
                    <button class="btn-edit" onclick="editPipelineTemplate('service', ${t.id})">修改</button>
                    <button class="btn-danger" onclick="deletePipelineTemplate(${t.id}, 'service')">删除</button>
                </td>
            </tr>
        `).join('');
    } catch (e) {
        list.innerHTML = `<tr><td colspan="5" style="text-align:center;padding:40px;color:#dc2626;">加载失败: ${escTemplateHtml(e.message)}</td></tr>`;
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
    <div class="tpl-var-check-unit" data-tpl-type="${escTemplateHtml(type)}" data-textarea-name="${escTemplateHtml(textareaName)}">
        <div class="tpl-var-check-bar">
            <button type="button" class="btn-secondary btn-sm" data-role="tplVarCheckBtn">检查变量</button>
            <span class="tpl-var-check-tip">检查内容里的 \${变量} 平台能否替换；点「确认」保存时也会自动检查</span>
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
    return `<code>\${${escTemplateHtml(name)}}</code>`;
}

function formatTemplateVarLines(lines) {
    if (!lines || !lines.length) return '';
    const shown = lines.slice(0, 6).join('、');
    return `第 ${shown}${lines.length > 6 ? ' 等' : ''} 行`;
}

function renderTemplateVarCheck(panel, result, blocking) {
    const issues = result.issues || [];
    const used = result.used || [];
    const passthrough = result.passthrough || [];
    const supported = result.supported || [];
    const parts = [];

    if (issues.length) {
        parts.push(`<div class="tpl-var-check-title">有 ${issues.length} 处变量写法渲染时不会被替换</div>`);
        parts.push('<ul class="tpl-var-check-issues">' + issues.map(issue => `
            <li>
                <button type="button" class="tpl-var-locate" data-locate="${escTemplateHtml(issue.placeholder)}" title="在内容中定位">${escTemplateHtml(issue.placeholder)}</button>
                <span class="tpl-var-lines">${formatTemplateVarLines(issue.lines)}</span>
                <span>${escTemplateHtml(issue.message)}${issue.suggestion ? `，可改为 ${templateVarCode(issue.suggestion)}` : ''}</span>
            </li>`).join('') + '</ul>');
        if (blocking) {
            parts.push('<div class="tpl-var-check-note">还没有保存。改好后再点「确认」；如果这些写法是有意保留的（shell 变量、构建时传入的自定义参数等），再点一次「确认」会按当前内容保存。</div>');
        }
    } else {
        parts.push(`<div class="tpl-var-check-title">${used.length
            ? `变量检查通过，用到的 ${used.length} 个变量平台都能替换`
            : '变量检查通过，内容里没有用到平台变量'}</div>`);
    }

    if (used.length) {
        parts.push(`<div class="tpl-var-check-row"><span class="tpl-var-check-label">${issues.length ? '能替换' : '用到'}</span><span>${used.map(v => templateVarCode(v.name)).join(' ')}</span></div>`);
        const byCondition = {};
        used.filter(v => v.condition).forEach(v => {
            (byCondition[v.condition] = byCondition[v.condition] || []).push(v.name);
        });
        const conditions = Object.keys(byCondition);
        if (conditions.length) {
            parts.push(`<div class="tpl-var-check-row"><span class="tpl-var-check-label">前提</span><span>${conditions.map(c =>
                `${byCondition[c].map(templateVarCode).join(' ')} ${escTemplateHtml(c)}`).join('；')}</span></div>`);
        }
    }
    if (passthrough.length) {
        parts.push(`<div class="tpl-var-check-row"><span class="tpl-var-check-label">原样保留</span><span>${passthrough.map(p => `<code>${escTemplateHtml(p)}</code>`).join(' ')} <span class="tpl-var-check-muted">按 shell / Docker 自己的变量处理，平台不替换</span></span></div>`);
    }
    if (supported.length) {
        parts.push(`<details class="tpl-var-supported"><summary>全部可用变量（${supported.length} 个）</summary><ul>${supported.map(v =>
            `<li>${templateVarCode(v.name)}<span>${escTemplateHtml(v.description || '')}${v.condition ? `（${escTemplateHtml(v.condition)}）` : ''}</span></li>`).join('')}</ul></details>`);
    }

    panel.className = 'tpl-var-check ' + (issues.length ? 'is-warn' : 'is-ok');
    panel.innerHTML = parts.join('');
    panel.hidden = false;
}

function renderTemplateVarCheckError(panel, error, blocking) {
    const message = (error && error.message) || '未知错误';
    panel.className = 'tpl-var-check is-error';
    panel.innerHTML = `<div class="tpl-var-check-title">变量检查没有完成：${escTemplateHtml(message)}</div>`
        + (blocking ? '<div class="tpl-var-check-note">还没有保存。再点一次「确认」会跳过检查直接保存。</div>' : '');
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
        console.error('模版变量检查失败:', e);
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

function buildTemplateFormHtml(type, data) {
    data = data || {};
    const commonFields = `
        <div class="form-item">
            <label>模版名称 *</label>
            <input type="text" name="name" value="${escTemplateHtml(data.name || '')}" placeholder="例如: Java 标准构建" required>
        </div>
        <div class="form-item">
            <label>描述</label>
            <textarea name="description" rows="2" placeholder="模版用途说明">${escTemplateHtml(data.description || '')}</textarea>
        </div>
        <div class="form-item">
            <label>状态</label>
            <select name="status">
                <option value="1" ${data.status !== 0 ? 'selected' : ''}>启用</option>
                <option value="0" ${data.status === 0 ? 'selected' : ''}>禁用</option>
            </select>
        </div>
    `;

    if (type === 'dockerfile') {
        return commonFields + `
            <div class="form-item">
                <label>基础镜像 *</label>
                <input type="text" name="baseImage" value="${escTemplateHtml(data.baseImage || 'openjdk:8-jre-slim')}" placeholder="openjdk:8-jre-slim" required>
            </div>
            <div class="form-item">
                <label>Dockerfile 内容 *</label>
                <textarea name="content" rows="14" required placeholder="FROM ...">${escTemplateHtml(data.content || DEFAULT_DOCKERFILE_CONTENT)}</textarea>
                <small style="color:var(--text-3);">支持变量：\${servicePort}（取自服务管理「对外端口」）、\${serviceName} 等</small>
                ${buildTemplateVarCheckHtml({ type: 'dockerfile', textareaName: 'content' })}
            </div>
        `;
    }

    if (type === 'deployment') {
        return commonFields + `
            <div class="form-item">
                <label>Deployment YAML *</label>
                <textarea name="content" rows="16" required placeholder="apiVersion: apps/v1...">${escTemplateHtml(data.content || DEFAULT_DEPLOYMENT_CONTENT)}</textarea>
                <small style="color:var(--text-3);">支持变量：\${deployment}、\${namespace}、\${serviceName}、\${image}、\${servicePort} 等。在步骤管理中由「渲染模版」步骤关联引用。</small>
                ${buildTemplateVarCheckHtml({ type: 'deployment', textareaName: 'content' })}
            </div>
        `;
    }

    const serviceTypeOptions = TEMPLATE_K8S_SERVICE_TYPE_OPTIONS.map(opt =>
        `<option value="${opt.value}" ${data.serviceType === opt.value ? 'selected' : ''}>${opt.label}</option>`
    ).join('');

    return commonFields + `
        <div class="form-item">
            <label>服务类型 *</label>
            <select name="serviceType" required>
                <option value="">请选择</option>
                ${serviceTypeOptions}
            </select>
            <small style="color:var(--text-3);">该值会注入模版变量 \${serviceType}（例如选择 ClusterIP 则渲染为 type: ClusterIP）</small>
        </div>
        <div class="form-item">
            <label>Service YAML *</label>
            <textarea name="content" rows="14" required placeholder="apiVersion: v1...">${escTemplateHtml(data.content || DEFAULT_SERVICE_CONTENT)}</textarea>
            <small style="color:var(--text-3);">支持变量：\${serviceName}、\${namespace}、\${servicePort}、\${serviceType} 等。在步骤管理中由「渲染模版」步骤关联引用。</small>
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
        alert('模版名称不能为空');
        return null;
    }
    if (!content.trim()) {
        alert('模版内容不能为空');
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
            alert('请填写基础镜像');
            return null;
        }
        payload.baseImage = baseImage;
    } else if (type === 'service') {
        const serviceTypeInput = form.querySelector('[name="serviceType"]');
        const serviceType = serviceTypeInput && serviceTypeInput.value;
        if (!serviceType) {
            alert('请选择服务类型');
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
        alert('模态框功能未加载，请刷新页面');
        return;
    }
    const label = TEMPLATE_TYPE_LABELS[type] || type;
    const content = `
        <form id="createPipelineTemplateForm" class="tpl-form">
            ${buildTemplateFormHtml(type)}
        </form>
    `;
    window.showModal(`添加 ${label} 模版`, content, async () => {
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
                alert('创建成功');
                await reloadTemplateList(type);
                window.closeModal();
            } else {
                alert('创建失败: ' + await response.text());
            }
        } catch (e) {
            alert('创建失败: ' + e.message);
        }
    });
    wireTemplateVarChecks(document.getElementById('createPipelineTemplateForm'));
}
window.showAddPipelineTemplateModal = showAddPipelineTemplateModal;

async function editPipelineTemplate(type, id) {
    try {
        const response = await fetch(`/api/pipeline-template/${id}`);
        if (!response.ok) {
            alert('加载失败');
            return;
        }
        const template = await response.json();
        const label = TEMPLATE_TYPE_LABELS[type] || type;
        const content = `
            <form id="editPipelineTemplateForm" class="tpl-form">
                ${buildTemplateFormHtml(type, template)}
            </form>
        `;
        window.showModal(`编辑 ${label} 模版`, content, async () => {
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
                    alert('更新成功');
                    await reloadTemplateList(type);
                    window.closeModal();
                } else {
                    alert('更新失败: ' + await resp.text());
                }
            } catch (e) {
                alert('更新失败: ' + e.message);
            }
        });
        wireTemplateVarChecks(document.getElementById('editPipelineTemplateForm'));
    } catch (e) {
        alert('加载失败: ' + e.message);
    }
}
window.editPipelineTemplate = editPipelineTemplate;

async function deletePipelineTemplate(id, type) {
    if (!confirm('确定删除此模版？')) return;
    try {
        const response = await fetch(`/api/pipeline-template/${id}`, { method: 'DELETE' });
        if (response.ok) {
            alert('删除成功');
            await reloadTemplateList(type);
        } else {
            alert('删除失败');
        }
    } catch (e) {
        alert('删除失败: ' + e.message);
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
            alert('模版功能未加载，请 Cmd+Shift+R 强制刷新');
            return;
        }
        if (typeof window.showModal !== 'function') {
            alert('模态框未加载（core.js），请强制刷新页面');
            return;
        }
        var result = fn();
        if (result && typeof result.catch === 'function') {
            result.catch(function(err) {
                console.error(label + ' failed:', err);
                alert(label + '失败: ' + (err && err.message ? err.message : err));
            });
        }
    } catch (err) {
        console.error(label + ' error:', err);
        alert(label + '失败: ' + (err && err.message ? err.message : err));
    }
}

function wireTemplateAddButtons() {
    var pairs = [
        ['addDockerfileTemplateBtn', function() { return showAddDockerfileTemplateModal(); }, '添加 Dockerfile 模版'],
        ['addDeploymentTemplateBtn', function() { return showAddDeploymentTemplateModal(); }, '添加 Deployment 模版'],
        ['addServiceTemplateBtn', function() { return showAddServiceTemplateModal(); }, '添加 Service 模版']
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
// 切换到系统设置/模版页后再次尝试绑定
setTimeout(wireTemplateAddButtons, 500);
setTimeout(wireTemplateAddButtons, 1500);
window.openTemplateModalSafe = openTemplateModalSafe;
window.wireTemplateAddButtons = wireTemplateAddButtons;

console.log('✓ template.js loaded, showAddDockerfileTemplateModal=', typeof window.showAddDockerfileTemplateModal);
})(window, document);
