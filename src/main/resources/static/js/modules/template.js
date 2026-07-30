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
        ? '<span style="color:#059669;">启用</span>'
        : '<span style="color:#dc2626;">禁用</span>';
}

async function loadDockerfileTemplates() {
    const list = document.getElementById('dockerfileTemplateList');
    if (!list) return;
    list.innerHTML = '<tr><td colspan="5" style="text-align:center;padding:40px;color:#999;">加载中...</td></tr>';
    try {
        const templates = await fetchTemplates('dockerfile');
        if (!templates.length) {
            list.innerHTML = '<tr><td colspan="5" style="text-align:center;padding:40px;color:#999;">暂无 Dockerfile 模版</td></tr>';
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
    list.innerHTML = '<tr><td colspan="4" style="text-align:center;padding:40px;color:#999;">加载中...</td></tr>';
    try {
        const templates = await fetchTemplates('deployment');
        if (!templates.length) {
            list.innerHTML = '<tr><td colspan="4" style="text-align:center;padding:40px;color:#999;">暂无 Deployment 模版</td></tr>';
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
    list.innerHTML = '<tr><td colspan="5" style="text-align:center;padding:40px;color:#999;">加载中...</td></tr>';
    try {
        const templates = await fetchTemplates('service');
        if (!templates.length) {
            list.innerHTML = '<tr><td colspan="5" style="text-align:center;padding:40px;color:#999;">暂无 Service 模版</td></tr>';
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
                <small style="color:#666;">支持变量：\${servicePort}（取自服务管理「对外端口」）、\${serviceName} 等</small>
            </div>
        `;
    }

    if (type === 'deployment') {
        return commonFields + `
            <div class="form-item">
                <label>Deployment YAML *</label>
                <textarea name="content" rows="16" required placeholder="apiVersion: apps/v1...">${escTemplateHtml(data.content || DEFAULT_DEPLOYMENT_CONTENT)}</textarea>
                <small style="color:#666;">支持变量：\${deployment}、\${namespace}、\${serviceName}、\${image}、\${servicePort} 等。在步骤管理中由「渲染模版」步骤关联引用。</small>
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
            <small style="color:#666;">该值会注入模版变量 \${serviceType}（例如选择 ClusterIP 则渲染为 type: ClusterIP）</small>
        </div>
        <div class="form-item">
            <label>Service YAML *</label>
            <textarea name="content" rows="14" required placeholder="apiVersion: v1...">${escTemplateHtml(data.content || DEFAULT_SERVICE_CONTENT)}</textarea>
            <small style="color:#666;">支持变量：\${serviceName}、\${namespace}、\${servicePort}、\${serviceType} 等。在步骤管理中由「渲染模版」步骤关联引用。</small>
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
        <form id="createPipelineTemplateForm" style="max-height:75vh;overflow-y:auto;">
            ${buildTemplateFormHtml(type)}
        </form>
    `;
    window.showModal(`添加 ${label} 模版`, content, async () => {
        const form = document.getElementById('createPipelineTemplateForm');
        if (!form) return;
        const data = collectTemplateFormData(form, type);
        if (!data) return;
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
            <form id="editPipelineTemplateForm" style="max-height:75vh;overflow-y:auto;">
                ${buildTemplateFormHtml(type, template)}
            </form>
        `;
        window.showModal(`编辑 ${label} 模版`, content, async () => {
            const form = document.getElementById('editPipelineTemplateForm');
            if (!form) return;
            const data = collectTemplateFormData(form, type);
            if (!data) return;
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
