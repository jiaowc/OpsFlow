// ============================================
// 系统设置模块（组件、Pipeline、SSO）
// ============================================

// 立即输出日志，确认脚本开始执行
(function() {
    'use strict';
    console.log('=== system.js script started loading ===');
    console.log('Current time:', new Date().toISOString());
    console.log('Document ready state:', document.readyState);
})();

// ============================================
// 标签页切换功能
// ============================================

// 系统/配置区标签页切换（流水线配置、凭据、集成）
function showConfigTab(tabName, element) {
    console.log('showConfigTab called with:', tabName);
    
    try {
        const scope = (element && element.closest('.content-section'))
            || document.querySelector('.content-section.active')
            || document.getElementById('pipeline-config-section');
        if (!scope) return;

        scope.querySelectorAll('.tab-buttons > .tab-btn').forEach(btn => btn.classList.remove('active'));
        scope.querySelectorAll('.config-panel').forEach(panel => panel.classList.remove('active'));
    
        if (element) {
            element.classList.add('active');
        } else {
            const btn = scope.querySelector(`.tab-buttons > .tab-btn[data-tab="${tabName}"]`);
            if (btn) btn.classList.add('active');
        }
        
        const panel = scope.querySelector('#' + tabName + '-config') || document.getElementById(tabName + '-config');
        if (panel) {
            panel.classList.add('active');
            console.log('Panel activated:', tabName + '-config');
        } else {
            console.error('Panel not found:', tabName + '-config');
        }
    
        switch(tabName) {
        case 'component':
                if (typeof window.loadComponents === 'function') {
                    window.loadComponents();
            }
            break;
        case 'keychain':
                if (typeof window.loadCredentials === 'function') {
                    window.loadCredentials();
                }
            break;
        case 'pipeline-mgmt':
                if (typeof window.loadPipelines === 'function') {
                    window.loadPipelines();
                }
            break;
        case 'template':
                const firstTemplateBtn = scope.querySelector('.template-tab-btn[data-template-type="dockerfile"]');
                if (typeof window.showTemplateTab === 'function') {
                    window.showTemplateTab('dockerfile', firstTemplateBtn);
                } else if (typeof window.loadDockerfileTemplates === 'function') {
                    window.loadDockerfileTemplates();
                }
            break;
        case 'step-def':
                if (typeof window.loadStepDefs === 'function') {
                    window.loadStepDefs();
            }
            break;
        case 'pipeline-view':
                if (typeof window.loadPipelineViewsAdmin === 'function') {
                    window.loadPipelineViewsAdmin();
                }
            break;
        case 'sso':
                if (typeof window.bindSystemConfigForms === 'function') {
                    window.bindSystemConfigForms();
                }
                if (typeof window.loadSSOConfigs === 'function') {
                    window.loadSSOConfigs();
            }
            break;
        case 'notify':
                if (typeof window.bindSystemConfigForms === 'function') {
                    window.bindSystemConfigForms();
                }
                if (typeof window.loadNotifyConfigs === 'function') {
                    window.loadNotifyConfigs();
                }
                break;
            case 'ldap':
                if (typeof window.bindSystemConfigForms === 'function') {
                    window.bindSystemConfigForms();
                }
                if (typeof window.loadSystemConfigs === 'function') {
                    window.loadSystemConfigs();
                }
                break;
        }
    } catch (error) {
        console.error('Error in showConfigTab:', error);
        alert('切换标签页时出错: ' + error.message);
    }
}
// 立即注册到全局作用域
try {
window.showConfigTab = showConfigTab;
    console.log('✓ showConfigTab registered to window:', typeof window.showConfigTab);
} catch (error) {
    console.error('✗ Failed to register showConfigTab:', error);
}

// 模版管理子标签页切换
function showTemplateTab(templateType, element) {
    const scope = (element && element.closest('.content-section'))
        || document.getElementById('pipeline-config-section')
        || document.querySelector('.content-section.active');
    if (!scope) return;

    scope.querySelectorAll('.template-tab-btn').forEach(btn => btn.classList.remove('active'));
    scope.querySelectorAll('.template-sub-panel').forEach(panel => panel.classList.remove('active'));

    if (element) {
        element.classList.add('active');
    }

    const panelMap = {
        dockerfile: 'dockerfile-template-panel',
        deployment: 'deployment-template-panel',
        service: 'service-template-panel'
    };
    const panel = document.getElementById(panelMap[templateType]);
    if (panel) {
        panel.classList.add('active');
    }

    switch (templateType) {
        case 'deployment':
            if (typeof window.loadDeploymentTemplates === 'function') {
                window.loadDeploymentTemplates();
            }
            if (typeof window.wireTemplateAddButtons === 'function') {
                window.wireTemplateAddButtons();
            }
            break;
        case 'dockerfile':
            if (typeof window.loadDockerfileTemplates === 'function') {
                window.loadDockerfileTemplates();
            }
            if (typeof window.wireTemplateAddButtons === 'function') {
                window.wireTemplateAddButtons();
            }
            break;
        case 'service':
            if (typeof window.loadServiceTemplates === 'function') {
                window.loadServiceTemplates();
            }
            if (typeof window.wireTemplateAddButtons === 'function') {
                window.wireTemplateAddButtons();
            }
            break;
    }
}
window.showTemplateTab = showTemplateTab;

// 模版列表加载由 template.js 提供

// SSO标签页切换
function showSSOTab(ssoType, element) {
    document.querySelectorAll('.sso-tab-btn').forEach(btn => btn.classList.remove('active'));
    document.querySelectorAll('.sso-config-panel').forEach(panel => panel.classList.remove('active'));
    
    if (element) {
        element.classList.add('active');
    }
    
    const panel = document.getElementById(ssoType + '-sso');
    if (panel) {
        panel.classList.add('active');
    }
}
window.showSSOTab = showSSOTab;

// ============================================
// SSO配置功能
// ============================================

// 加载SSO / 通知等系统配置到表单
async function loadSSOConfigs() {
    return applySystemConfigsToForms();
}
window.loadSSOConfigs = loadSSOConfigs;

async function loadNotifyConfigs() {
    return applySystemConfigsToForms();
}
window.loadNotifyConfigs = loadNotifyConfigs;

async function applySystemConfigsToForms() {
    try {
        const response = await fetch('/api/system/config/list');
        if (!response.ok) return;
        const configs = await response.json();
        if (!configs || !configs.length) {
            // 通知页默认勾选
            const inboxEnabled = document.querySelector('[name="notify.inboxEnabled"]');
            const defaultInbox = document.querySelector('[name="notify.defaultInbox"]');
            if (inboxEnabled) inboxEnabled.checked = true;
            if (defaultInbox) defaultInbox.checked = true;
            return;
        }
        configs.forEach(config => {
            if (!config.configType || !config.configKey) return;
            const input = document.querySelector(`[name="${config.configType}.${config.configKey}"]`);
            if (!input) return;
            fillConfigInput(input, config.configValue);
        });
        // 旧 feishu.* 兼容填充到拆分后的表单（仅当新键未保存时）
        fillLegacyFeishuIntoSplitForms(configs);
        // 未落库时站内信默认开启
        const inboxEnabled = document.querySelector('[name="notify.inboxEnabled"]');
        if (inboxEnabled && !configs.some(c => c.configType === 'notify' && c.configKey === 'inboxEnabled')) {
            inboxEnabled.checked = true;
        }
        const defaultInbox = document.querySelector('[name="notify.defaultInbox"]');
        if (defaultInbox && !configs.some(c => c.configType === 'notify' && c.configKey === 'defaultInbox')) {
            defaultInbox.checked = true;
        }
    } catch (error) {
        console.error('Load system configs error:', error);
    }
}
window.applySystemConfigsToForms = applySystemConfigsToForms;

function fillConfigInput(input, value) {
    if (!input) return;
    if (input.type === 'checkbox') {
        input.checked = value === 'true' || value === '1';
    } else if (input.tagName === 'SELECT') {
        input.value = value || (input.options[0] && input.options[0].value) || '';
    } else {
        input.value = value || '';
    }
}

function fillLegacyFeishuIntoSplitForms(configs) {
    const legacy = {};
    configs.filter(c => c.configType === 'feishu').forEach(c => {
        legacy[c.configKey] = c.configValue;
    });
    const applyIfMissing = (newType, keys) => {
        keys.forEach(key => {
            const hasNew = configs.some(c => c.configType === newType && c.configKey === key
                && c.configValue != null && String(c.configValue).trim() !== '');
            if (hasNew) return;
            if (legacy[key] === undefined) return;
            const input = document.querySelector(`[name="${newType}.${key}"]`);
            if (!input) return;
            if (input.type === 'checkbox') {
                // 仅当新类型完全没有该键时填充
                const exists = configs.some(c => c.configType === newType && c.configKey === key);
                if (!exists) fillConfigInput(input, legacy[key]);
            } else if (!input.value) {
                fillConfigInput(input, legacy[key]);
            }
        });
    };
    applyIfMissing('feishu_notify', [
        'approvalEnabled', 'appId', 'appSecret', 'apiUrl', 'receiveIdType', 'portalUrl'
    ]);
    applyIfMissing('feishu_sso', ['enabled', 'appId', 'appSecret', 'redirectUri']);
}

function collectFormConfigValues(form) {
    const byType = {};
    form.querySelectorAll('[name]').forEach(el => {
        const name = el.getAttribute('name') || '';
        const idx = name.indexOf('.');
        if (idx <= 0) return;
        const type = name.substring(0, idx);
        const key = name.substring(idx + 1);
        if (!key) return;
        if (el.readOnly) return;
        let value;
        if (el.type === 'checkbox') {
            value = el.checked ? 'true' : 'false';
        } else {
            value = el.value != null ? String(el.value) : '';
        }
        if (!byType[type]) byType[type] = {};
        byType[type][key] = value;
    });
    return byType;
}

async function saveConfigForm(form) {
    const multi = form.getAttribute('data-config-multi') === 'true';
    let byType = {};
    if (multi) {
        byType = collectFormConfigValues(form);
    } else {
        const configType = form.getAttribute('data-config-type');
        if (!configType) {
            alert('表单缺少配置类型');
            return;
        }
        const all = collectFormConfigValues(form);
        byType[configType] = all[configType] || {};
    }
    const types = Object.keys(byType);
    if (!types.length) {
        alert('没有可保存的配置项');
        return;
    }

    try {
        for (let i = 0; i < types.length; i++) {
            const configType = types[i];
            const response = await fetch('/api/system/config/batch', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ configType, values: byType[configType] })
            });
            if (!response.ok) {
                const text = await response.text();
                alert('保存失败 (' + configType + '): ' + (text || response.status));
                return;
            }
        }
        alert('保存成功');
        if (typeof applySystemConfigsToForms === 'function') {
            applySystemConfigsToForms();
        }
    } catch (error) {
        alert('保存失败: ' + error.message);
    }
}
window.saveConfigForm = saveConfigForm;

function bindSystemConfigForms() {
    document.querySelectorAll('form.config-form[data-config-type], form.config-form[data-config-multi]').forEach(form => {
        if (form.dataset.bound === '1') return;
        form.dataset.bound = '1';
        form.addEventListener('submit', function (e) {
            e.preventDefault();
            saveConfigForm(form);
        });
    });
}
window.bindSystemConfigForms = bindSystemConfigForms;

// 页面就绪后绑定
if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', bindSystemConfigForms);
} else {
    bindSystemConfigForms();
}

// ============================================
// 组件管理功能
// ============================================

// 加载组件列表
async function loadComponents() {
    const componentList = document.getElementById('componentList');
    if (!componentList) return;
    
    try {
        const response = await fetch('/api/component/list');
        if (response.ok) {
            const components = await response.json();
            if (components && components.length > 0) {
                // 先渲染列表，显示"检测中..."
                renderComponentList(components);
                // 然后异步检测每个组件的连接状态
                checkAllComponents(components);
                return;
            }
        }
    } catch (error) {
        console.error('Load components error:', error);
    }
    
    componentList.innerHTML = '<tr><td colspan="6" style="text-align: center; padding: 40px; color: #999;">暂无组件</td></tr>';
}
window.loadComponents = loadComponents;

// 检测所有组件的连接状态（批量接口，服务端并行检测）
async function checkAllComponents(components) {
    if (!components || components.length === 0) return;

    try {
        const ids = components.map(c => c.id);
        const response = await fetch('/api/component/test/batch', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(ids)
        });

        if (!response.ok) {
            throw new Error('批量检测请求失败');
        }

        const results = await response.json();
        components.forEach(component => {
            const result = results[String(component.id)];
            updateComponentStatus(component.id, result ? result.success : false);
        });
    } catch (error) {
        console.error('Batch check components error:', error);
        components.forEach(component => updateComponentStatus(component.id, false));
    }
}

// 更新组件状态显示
function updateComponentStatus(componentId, isAvailable) {
    // 通过 data-component-id 属性查找状态单元格
    const statusCell = document.querySelector(`td[data-component-id="${componentId}"]`);
    if (!statusCell) {
        console.warn(`Status cell not found for component ${componentId}`);
        return;
    }
    
    // 只显示检测状态：可用 或 不可用
    if (isAvailable) {
        statusCell.innerHTML = '<span style="color: #059669; font-size: 14px;">可用</span>';
    } else {
        statusCell.innerHTML = '<span style="color: #dc2626; font-size: 14px;">不可用</span>';
    }
}

// 渲染组件列表
function renderComponentList(components) {
    const componentList = document.getElementById('componentList');
    if (!componentList) return;
    
    if (components.length === 0) {
        componentList.innerHTML = '<tr><td colspan="6" style="text-align: center; padding: 40px; color: #999;">暂无组件</td></tr>';
        return;
    }
    
    componentList.innerHTML = components.map(component => {
        const componentTypeText = {
            'gitlab': 'GitLab',
            'github': 'GitHub',
            'harbor': 'Harbor',
            'nexus': 'Nexus',
            'sonarqube': 'SonarQube',
            'k8s': 'K8s',
            'other': '其他'
        }[component.type] || component.type;
        const authTypeText = {
            'api_key': 'API密钥',
            'username_password': '账户密码',
            'token': 'Token',
            'oauth': 'OAuth',
            'kubeconfig': 'K8s集群',
            'ssh_key': 'SSH密钥'
        }[component.authType] || component.authType;
        const authDisplay = component.credentialName
            ? `钥匙串: ${component.credentialName}`
            : authTypeText;
        
        return `
            <tr>
                <td>${component.name}</td>
                <td>${componentTypeText}</td>
                <td>${component.url || '-'}</td>
                <td>${authDisplay}</td>
                <td data-component-id="${component.id}">
                    <span style="color: #6b7280; font-size: 14px;">检测中...</span>
                </td>
                <td>
                    <button class="btn-secondary" onclick="window.testComponent(${component.id})" style="margin-right: 5px;">检测</button>
                    <button class="btn-edit" onclick="window.editComponent(${component.id})">修改</button>
                    <button class="btn-danger" onclick="window.deleteComponent(${component.id})">删除</button>
                </td>
            </tr>
        `;
    }).join('');
}
window.renderComponentList = renderComponentList;

// 显示添加组件模态框
function showAddComponentModal() {
    console.log('showAddComponentModal called');
    
    if (typeof window.showModal !== 'function') {
        console.error('showModal function not available');
        alert('模态框功能未加载，请刷新页面');
        return;
    }
    
    const content = `
        <form id="createComponentForm">
            <div class="form-item">
                <label>组件名称 *</label>
                <input type="text" name="name" placeholder="例如: GitLab、Harbor" required>
            </div>
            <div class="form-item">
                <label>组件类型 *</label>
                <select name="type" required>
                    <option value="">请选择类型</option>
                    <option value="gitlab">GitLab</option>
                    <option value="github">GitHub</option>
                    <option value="harbor">Harbor</option>
                    <option value="nexus">Nexus</option>
                    <option value="sonarqube">SonarQube</option>
                    <option value="k8s">K8s</option>
                    <option value="other">其他</option>
                </select>
            </div>
            <div class="form-item">
                <label>访问地址 *</label>
                <input type="text" name="url" placeholder="例如: https://github.com 或 https://gitlab.example.com" required>
            </div>
            ${typeof window.buildComponentCredentialField === 'function' ? window.buildComponentCredentialField() : ''}
            <div class="form-item">
                <label>描述</label>
                <textarea name="description" rows="3" placeholder="组件描述"></textarea>
            </div>
        </form>
    `;
    
    window.showModal('添加组件', content, async () => {
        const form = document.getElementById('createComponentForm');
        const formData = new FormData(form);
        const authData = typeof window.collectComponentAuthData === 'function'
            ? window.collectComponentAuthData(form) : {};
        
        const data = {
            name: formData.get('name'),
            type: formData.get('type'),
            url: formData.get('url'),
            description: formData.get('description') || '',
            ...authData
        };
        
        try {
            const response = await fetch('/api/component/create', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(data)
            });
            
            if (response.ok) {
                alert('创建成功');
                window.loadComponents();
                window.closeModal();
            } else {
                const errorMessage = await (typeof window.handleApiError === 'function' ? window.handleApiError(response) : response.text());
                alert('创建失败: ' + errorMessage);
            }
        } catch (error) {
            console.error('Create component error:', error);
            alert('创建失败: ' + (error.message || '网络错误'));
        }
    });

    setTimeout(() => {
        if (typeof window.initComponentCredentialSelect === 'function') {
            const typeSelect = document.querySelector('#createComponentForm select[name="type"]');
            window.initComponentCredentialSelect(typeSelect ? typeSelect.value : '', '');
            if (typeSelect) {
                typeSelect.addEventListener('change', () => {
                    window.initComponentCredentialSelect(typeSelect.value, '');
                });
            }
        }
        if (typeof window.updateAuthConfigFields === 'function') {
            window.updateAuthConfigFields();
        }
    }, 50);
}
// 立即注册到全局作用域
try {
window.showAddComponentModal = showAddComponentModal;
    console.log('✓ showAddComponentModal registered to window:', typeof window.showAddComponentModal);
} catch (error) {
    console.error('✗ Failed to register showAddComponentModal:', error);
}

// 更新认证配置字段
function updateAuthConfigFields() {
    const authTypeSelect = document.getElementById('authTypeSelect');
    if (!authTypeSelect) return;
    
    const authType = authTypeSelect.value;
    const authConfigFields = document.getElementById('authConfigFields');
    if (!authConfigFields) return;
    
    let fieldsHtml = '';
    
    if (authType === 'api_key') {
        fieldsHtml = `
            <div class="form-item">
                <label>API密钥 *</label>
                <input type="text" name="apiKey" placeholder="请输入API密钥" required>
            </div>
        `;
    } else if (authType === 'username_password') {
        fieldsHtml = `
            <div class="form-item">
                <label>用户名 *</label>
                <input type="text" name="username" placeholder="请输入用户名" required>
            </div>
            <div class="form-item">
                <label>密码 *</label>
                <input type="password" name="password" placeholder="请输入密码" required>
            </div>
        `;
    } else if (authType === 'token') {
        fieldsHtml = `
            <div class="form-item">
                <label>Token *</label>
                <input type="text" name="token" placeholder="请输入Token" required>
            </div>
        `;
    } else if (authType === 'oauth') {
        fieldsHtml = `
            <div class="form-item">
                <label>Client ID *</label>
                <input type="text" name="clientId" placeholder="请输入Client ID" required>
            </div>
            <div class="form-item">
                <label>Client Secret *</label>
                <input type="password" name="clientSecret" placeholder="请输入Client Secret" required>
            </div>
        `;
    }
    
    authConfigFields.innerHTML = fieldsHtml;
}
window.updateAuthConfigFields = updateAuthConfigFields;

// 编辑组件
async function editComponent(id) {
    try {
        const response = await fetch(`/api/component/${id}`);
        if (response.ok) {
            const component = await response.json();
            showEditComponentModal(component);
        } else {
            alert('加载失败');
        }
    } catch (error) {
        alert('加载失败: ' + error.message);
    }
}
window.editComponent = editComponent;

// 显示编辑组件模态框
function showEditComponentModal(component) {
    const content = `
        <form id="editComponentForm">
            <div class="form-item">
                <label>组件名称 *</label>
                <input type="text" name="name" value="${component.name || ''}" required>
            </div>
            <div class="form-item">
                <label>组件类型 *</label>
                <select name="type" id="editComponentTypeSelect" required>
                    <option value="gitlab" ${component.type === 'gitlab' ? 'selected' : ''}>GitLab</option>
                    <option value="github" ${component.type === 'github' ? 'selected' : ''}>GitHub</option>
                    <option value="harbor" ${component.type === 'harbor' ? 'selected' : ''}>Harbor</option>
                    <option value="nexus" ${component.type === 'nexus' ? 'selected' : ''}>Nexus</option>
                    <option value="sonarqube" ${component.type === 'sonarqube' ? 'selected' : ''}>SonarQube</option>
                    <option value="k8s" ${component.type === 'k8s' ? 'selected' : ''}>K8s</option>
                    <option value="other" ${component.type === 'other' ? 'selected' : ''}>其他</option>
                </select>
            </div>
            <div class="form-item">
                <label>访问地址 *</label>
                <input type="text" name="url" value="${component.url || ''}" required>
            </div>
            ${typeof window.buildComponentCredentialField === 'function' ? window.buildComponentCredentialField(component) : ''}
            <div class="form-item">
                <label>描述</label>
                <textarea name="description" rows="3">${component.description || ''}</textarea>
            </div>
        </form>
    `;
    
    if (typeof window.showModal !== 'function') {
        alert('模态框功能未加载，请刷新页面');
        return;
    }
    
    window.showModal('编辑组件', content, async () => {
        const form = document.getElementById('editComponentForm');
        const formData = new FormData(form);
        const authData = typeof window.collectComponentAuthData === 'function'
            ? window.collectComponentAuthData(form) : {};

        const data = {
            name: formData.get('name'),
            type: formData.get('type'),
            url: formData.get('url'),
            description: formData.get('description') || '',
            ...authData
        };
        
        try {
            const response = await fetch(`/api/component/${component.id}`, {
                method: 'PUT',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(data)
            });
            
            if (response.ok) {
                alert('更新成功');
                window.loadComponents();
                window.closeModal();
            } else {
                const error = await response.json().catch(() => ({ message: '未知错误' }));
                alert('更新失败: ' + (error.message || '未知错误'));
            }
        } catch (error) {
            alert('更新失败: ' + error.message);
        }
    });

    setTimeout(() => {
        if (typeof window.initComponentCredentialSelect === 'function') {
            const typeSelect = document.getElementById('editComponentTypeSelect');
            window.initComponentCredentialSelect(component.type, component.credentialId || '');
            if (typeSelect) {
                typeSelect.addEventListener('change', () => {
                    window.initComponentCredentialSelect(typeSelect.value, '');
                });
            }
        }
        if (!component.credentialId && typeof window.updateAuthConfigFields === 'function') {
            const authConfig = component.authConfig || {};
            const authTypeSelect = document.getElementById('authTypeSelect');
            if (authTypeSelect) authTypeSelect.value = component.authType || '';
            window.updateAuthConfigFields();
            if (component.authType === 'api_key' && authConfig.apiKey) {
                const el = document.querySelector('#editComponentForm input[name="apiKey"]');
                if (el) el.value = authConfig.apiKey;
            }
            if (component.authType === 'username_password') {
                const u = document.querySelector('#editComponentForm input[name="username"]');
                if (u) u.value = authConfig.username || '';
            }
            if (component.authType === 'token' && authConfig.token) {
                const el = document.querySelector('#editComponentForm input[name="token"]');
                if (el) el.value = authConfig.token;
            }
            if (component.authType === 'oauth' && authConfig.clientId) {
                const el = document.querySelector('#editComponentForm input[name="clientId"]');
                if (el) el.value = authConfig.clientId;
            }
        }
    }, 50);
}
window.showEditComponentModal = showEditComponentModal;

// 删除组件
async function deleteComponent(id) {
    if (!confirm('确定要删除这个组件吗？')) return;
    
    try {
        const response = await fetch(`/api/component/${id}`, { method: 'DELETE' });
        if (response.ok) {
            alert('删除成功');
            window.loadComponents();
        } else {
            alert('删除失败');
        }
    } catch (error) {
        alert('删除失败: ' + error.message);
    }
}
window.deleteComponent = deleteComponent;

// 测试组件连接（手动检测）
async function testComponent(id) {
    // 显示加载状态 - 通过查找按钮元素
    const testBtn = document.querySelector(`button[onclick*="testComponent(${id})"]`);
    const originalText = testBtn ? testBtn.textContent : '检测';
    if (testBtn) {
        testBtn.disabled = true;
        testBtn.textContent = '检测中...';
    }
    
    // 更新状态显示为"检测中..."
    const statusCell = document.querySelector(`td[data-component-id="${id}"]`);
    if (statusCell) {
        statusCell.innerHTML = '<span style="color: #6b7280; font-size: 14px;">检测中...</span>';
    }
    
    try {
        const response = await fetch(`/api/component/${id}/test?force=true`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' }
        });
        
        const result = await response.json();
        
        // 更新状态显示
        updateComponentStatus(id, result.success);
        
        // 显示结果提示
        if (result.success) {
            alert('✓ 连接成功！\n\n' + result.message);
        } else {
            alert('✗ 连接失败\n\n' + result.message);
        }
    } catch (error) {
        console.error('Test component error:', error);
        updateComponentStatus(id, false);
        alert('测试连接时出错: ' + (error.message || '网络错误'));
    } finally {
        // 恢复按钮状态
        if (testBtn) {
            testBtn.disabled = false;
            testBtn.textContent = originalText;
        }
    }
}
window.testComponent = testComponent;

// ============================================
// Pipeline 模板功能
// ============================================

const PIPELINE_CI_STEP_TYPES = {
    'checkout': '拉取代码',
    'build': '代码构建',
    'docker_build': '镜像制作',
    'push_image': '上传镜像',
    'clean': '清理缓存',
    'clean_workspace': '清理空间'
};
const PIPELINE_CD_STEP_TYPES = {
    'deploy': '开始部署',
    'check_deploy': '检查部署状态',
    'render_template': '渲染模版',
    'rollback': '回滚',
    'notify': '通知'
};
const PIPELINE_CI_STEP_TYPE_KEYS = Object.keys(PIPELINE_CI_STEP_TYPES);

function renderPipelineNodeSelectOptions(nodeType, selectedValue) {
    const nodes = (mockNodes || []).filter(n => n.nodeType === nodeType);
    const selectedIds = Array.isArray(selectedValue)
        ? selectedValue.map(v => String(v))
        : (selectedValue != null ? [String(selectedValue)] : []);
    let html = '<option value="">不使用</option>';
    if (nodes.length === 0) {
        html += '<option value="" disabled>暂无节点，请先在「节点管理」中添加</option>';
    }
    nodes.forEach(n => {
        const selected = selectedIds.includes(String(n.id)) ? ' selected' : '';
        const host = n.host ? ` (${n.host})` : '';
        const esc = typeof escapeHtml === 'function' ? escapeHtml : (s => s);
        html += `<option value="${n.id}"${selected}>${esc(n.name)}${esc(host)}</option>`;
    });
    return html;
}

function getPipelineNodeMultiSelectState(pipeline) {
    const selectedIds = Array.isArray(pipeline.buildNodeIds) && pipeline.buildNodeIds.length
        ? pipeline.buildNodeIds.map(v => String(v))
        : (pipeline.buildNodeId != null ? [String(pipeline.buildNodeId)] : []);
    const nodes = (mockNodes || []).filter(n => n.nodeType === 'build');
    return { selectedIds, nodes };
}

function renderPipelineBuildNodeMultiSelect(pipeline) {
    const { selectedIds, nodes } = getPipelineNodeMultiSelectState(pipeline || {});
    const esc = typeof escapeHtml === 'function' ? escapeHtml : (s => s);
    const selectedNodes = nodes.filter(n => selectedIds.includes(String(n.id)));
    const displayHtml = selectedNodes.length
        ? selectedNodes.map(n => {
            const host = n.host ? ` (${n.host})` : '';
            return `<span class="tag-item" data-node-id="${n.id}">
                ${esc(n.name)}${esc(host)}
                <span class="tag-item-remove" onclick="removePipelineBuildNode(event, '${n.id}')">×</span>
            </span>`;
        }).join('')
        : '<span class="tag-placeholder">可选：选择一个或多个构建节点</span>';
    const optionsHtml = nodes.length
        ? nodes.map(n => {
            const host = n.host ? ` (${n.host})` : '';
            const checked = selectedIds.includes(String(n.id)) ? ' checked' : '';
            return `<label class="tag-option">
                <input type="checkbox" value="${n.id}" data-name="${esc(n.name)}" data-host="${esc(n.host || '')}" onchange="togglePipelineBuildNode(this)"${checked}>
                <span>${esc(n.name)}${esc(host)}</span>
            </label>`;
        }).join('')
        : '<div class="tag-option"><span>暂无构建节点，请先在「节点管理」中添加</span></div>';

    return `
        <div class="tag-input-container" onclick="togglePipelineBuildNodeDropdown()">
            <div class="tag-input-display" id="pipelineBuildNodeDisplay">${displayHtml}</div>
            <span class="tag-arrow">▼</span>
        </div>
        <input type="hidden" name="buildNodeIds" id="pipelineBuildNodeIdsInput" value="${esc(selectedIds.join(','))}">
        <div class="tag-dropdown" id="pipelineBuildNodeDropdown" style="display:none;">
            ${optionsHtml}
        </div>
    `;
}

function togglePipelineBuildNodeDropdown() {
    const dropdown = document.getElementById('pipelineBuildNodeDropdown');
    if (!dropdown) return;
    const isVisible = dropdown.style.display !== 'none';
    dropdown.style.display = isVisible ? 'none' : 'block';
    if (!isVisible) {
        setTimeout(() => {
            document.addEventListener('click', function closeDropdown(e) {
                if (!dropdown.contains(e.target) && !e.target.closest('#pipelineBuildNodeMultiSelect')) {
                    dropdown.style.display = 'none';
                    document.removeEventListener('click', closeDropdown);
                }
            });
        }, 0);
    }
}
window.togglePipelineBuildNodeDropdown = togglePipelineBuildNodeDropdown;

function syncPipelineBuildNodeSelection() {
    const dropdown = document.getElementById('pipelineBuildNodeDropdown');
    const display = document.getElementById('pipelineBuildNodeDisplay');
    const input = document.getElementById('pipelineBuildNodeIdsInput');
    if (!dropdown || !display || !input) return;

    const checked = Array.from(dropdown.querySelectorAll('input[type="checkbox"]:checked'));
    input.value = checked.map(cb => cb.value).join(',');

    if (!checked.length) {
        display.innerHTML = '<span class="tag-placeholder">可选：选择一个或多个构建节点</span>';
        return;
    }

    display.innerHTML = checked.map(cb => {
        const name = cb.dataset.name || cb.value;
        const host = cb.dataset.host ? ` (${cb.dataset.host})` : '';
        return `<span class="tag-item" data-node-id="${cb.value}">
            ${name}${host}
            <span class="tag-item-remove" onclick="removePipelineBuildNode(event, '${cb.value}')">×</span>
        </span>`;
    }).join('');
}

function togglePipelineBuildNode(checkbox) {
    if (!checkbox) return;
    syncPipelineBuildNodeSelection();
}
window.togglePipelineBuildNode = togglePipelineBuildNode;

function removePipelineBuildNode(event, nodeId) {
    event.stopPropagation();
    const dropdown = document.getElementById('pipelineBuildNodeDropdown');
    if (!dropdown) return;
    const checkbox = dropdown.querySelector(`input[value="${nodeId}"]`);
    if (checkbox) {
        checkbox.checked = false;
    }
    syncPipelineBuildNodeSelection();
}
window.removePipelineBuildNode = removePipelineBuildNode;

function buildPipelineNodeSectionHtml(pipeline) {
    pipeline = pipeline || {};
    return `
        <div style="border: 1px solid #bfdbfe; border-radius: 6px; padding: 16px; margin-bottom: 16px; background: #eff6ff;">
            <h4 style="margin: 0 0 12px 0; color: #1d4ed8;">CI 阶段（持续集成，可选）</h4>
            <div class="form-item">
                <label>候选构建节点</label>
                <div id="pipelineBuildNodeMultiSelect" style="position:relative;">
                    ${renderPipelineBuildNodeMultiSelect(pipeline)}
                </div>
                <small style="color:#666;display:block;margin-top:4px;">仅在配置了 CI 步骤时需要；运行 Job 时会优先调度到当前负载较低的节点执行 CI 步骤</small>
            </div>
            <div class="form-item">
                <label style="display:flex;justify-content:space-between;align-items:center;">
                    <span>CI 步骤</span>
                    <button type="button" class="btn-secondary" onclick="window.addPipelineCiStep()" style="padding:4px 12px;font-size:12px;">关联 CI 步骤</button>
                </label>
                <div id="pipelineCiStepsContainer" style="border:1px solid #ddd;border-radius:4px;padding:12px;background:#f9f9f9;min-height:40px;"></div>
            </div>
        </div>
        <div style="border: 1px solid #bbf7d0; border-radius: 6px; padding: 16px; background: #f0fdf4;">
            <h4 style="margin: 0 0 12px 0; color: #15803d;">CD 阶段（持续部署，可选）</h4>
            <div class="form-item">
                <label>部署节点</label>
                <select name="deployNodeId">${renderPipelineNodeSelectOptions('deploy', pipeline.deployNodeId)}</select>
                <small style="color:#666;display:block;margin-top:4px;">仅在配置了 CD 步骤时需要；部署、检查部署状态等步骤在部署节点执行</small>
            </div>
            <div class="form-item">
                <label style="display:flex;justify-content:space-between;align-items:center;">
                    <span>CD 步骤</span>
                    <button type="button" class="btn-secondary" onclick="window.addPipelineCdStep()" style="padding:4px 12px;font-size:12px;">关联 CD 步骤</button>
                </label>
                <div id="pipelineCdStepsContainer" style="border:1px solid #ddd;border-radius:4px;padding:12px;background:#f9f9f9;min-height:40px;"></div>
            </div>
        </div>
        <p style="margin:12px 0 0;font-size:13px;color:#6b7280;">CI / CD 至少配置其中一个阶段（例如仅 CD：直接发布已有镜像，无需构建）</p>
    `;
}

function resolvePipelineFormPhases(steps) {
    let hasCi = false;
    let hasCd = false;
    const defs = window.pipelineStepDefCache || [];
    (steps || []).forEach(step => {
        const def = defs.find(d => String(d.id) === String(step.stepTemplateId));
        if (def) {
            if (def.phase === 'cd') {
                hasCd = true;
            } else {
                hasCi = true;
            }
            return;
        }
        if (step.stepType && PIPELINE_CI_STEP_TYPE_KEYS.includes(step.stepType)) {
            hasCi = true;
        } else if (step.stepType) {
            hasCd = true;
        }
    });
    return { hasCi, hasCd };
}

function collectPipelineFormData(form) {
    const steps = window.collectPipelineSteps();
    if (!steps || steps.length === 0) {
        alert('请至少添加一个 CI 或 CD 步骤');
        return null;
    }
    const { hasCi, hasCd } = resolvePipelineFormPhases(steps);
    if (!hasCi && !hasCd) {
        alert('请至少添加一个 CI 或 CD 步骤');
        return null;
    }

    const pipelineType = (form.querySelector('[name="pipelineType"]')?.value || '').trim();
    if (!['ci', 'cd', 'cicd'].includes(pipelineType)) {
        alert('请选择流水线类型：CI / CD / CI/CD');
        return null;
    }
    if (pipelineType === 'ci' && !hasCi) {
        alert('类型为 CI 时，请至少配置一个 CI 步骤');
        return null;
    }
    if (pipelineType === 'cd' && !hasCd) {
        alert('类型为 CD 时，请至少配置一个 CD 步骤');
        return null;
    }
    if (pipelineType === 'cicd' && (!hasCi || !hasCd)) {
        alert('类型为 CI/CD 时，请同时配置 CI 与 CD 步骤');
        return null;
    }
    if (pipelineType === 'ci' && hasCd) {
        alert('类型为 CI 时不应包含 CD 步骤，请移除 CD 步骤或改为 CI/CD');
        return null;
    }
    if (pipelineType === 'cd' && hasCi) {
        alert('类型为 CD 时不应包含 CI 步骤，请移除 CI 步骤或改为 CI/CD');
        return null;
    }

    const buildNodeInput = form.querySelector('#pipelineBuildNodeIdsInput');
    const buildNodeIds = buildNodeInput && buildNodeInput.value
        ? buildNodeInput.value.split(',').map(v => parseInt(v, 10)).filter(v => !isNaN(v))
        : [];
    const deployNodeRaw = form.querySelector('[name="deployNodeId"]')?.value;
    const deployNodeId = deployNodeRaw ? parseInt(deployNodeRaw, 10) : null;

    if (hasCi && !buildNodeIds.length) {
        alert('已配置 CI 步骤，请至少选择一个 CI 构建节点');
        return null;
    }
    if (hasCd && !deployNodeId) {
        alert('已配置 CD 步骤，请选择 CD 部署节点');
        return null;
    }

    const nameInput = form.querySelector('[name="name"]');
    if (!nameInput || !nameInput.value.trim()) {
        alert('Pipeline 名称不能为空');
        return null;
    }
    return {
        name: nameInput.value.trim(),
        pipelineType: pipelineType,
        buildNodeId: buildNodeIds.length ? buildNodeIds[0] : null,
        buildNodeIds: buildNodeIds,
        deployNodeId: deployNodeId,
        status: form.querySelector('[name="status"]')?.value === '0' ? 0 : 1,
        steps: steps
    };
}

async function initDefaultPipelineSteps() {
    const defs = await (typeof window.loadPipelineStepDefs === 'function' ? window.loadPipelineStepDefs(true) : Promise.resolve([]));
    const ciDefs = defs.filter(d => d.phase === 'ci').sort((a, b) => a.id - b.id);
    const cdDefs = defs.filter(d => d.phase === 'cd').sort((a, b) => a.id - b.id);
    for (const def of ciDefs) {
        await window.addPipelineStepRef('ci', def.id);
    }
    for (const def of cdDefs) {
        await window.addPipelineStepRef('cd', def.id);
    }
}

async function loadPipelineStepsFromConfig(steps) {
    const sorted = [...steps].sort((a, b) => (a.order || 0) - (b.order || 0));
    for (const step of sorted) {
        if (step.stepTemplateId) {
            const def = (window.pipelineStepDefCache || []).find(d => d.id === step.stepTemplateId);
            const stepPhase = def ? def.phase : (PIPELINE_CI_STEP_TYPE_KEYS.includes(step.stepType) ? 'ci' : 'cd');
            await window.addPipelineStepRef(stepPhase, step.stepTemplateId);
        } else if (step.stepType) {
            const phase = PIPELINE_CI_STEP_TYPE_KEYS.includes(step.stepType) ? 'ci' : 'cd';
            const def = (window.pipelineStepDefCache || []).find(d => d.stepType === step.stepType && d.phase === phase);
            if (def) {
                await window.addPipelineStepRef(phase, def.id);
            }
        }
    }
}

// 加载Pipeline列表
async function loadPipelines() {
    const pipelineList = document.getElementById('pipelineList');
    if (!pipelineList) {
        console.warn('Pipeline列表容器不存在');
        return;
    }
    
    pipelineList.innerHTML = '<tr><td colspan="5" style="text-align: center; padding: 40px; color: #999;">加载中...</td></tr>';
    
    try {
        if (typeof window.loadPipelineStepDefs === 'function') {
            await window.loadPipelineStepDefs(true);
        }
        const response = await fetch('/api/pipeline/list');
        if (response.ok) {
            const pipelines = await response.json();
            if (pipelines && pipelines.length > 0) {
                renderPipelineList(pipelines);
            } else {
                pipelineList.innerHTML = '<tr><td colspan="5" style="text-align: center; padding: 40px; color: #999;">暂无 Pipeline 模版</td></tr>';
            }
        } else {
            const errorText = await response.text();
            pipelineList.innerHTML = '<tr><td colspan="5" style="text-align: center; padding: 40px; color: #dc2626;">加载失败: ' + (errorText || '未知错误') + '</td></tr>';
        }
    } catch (error) {
        console.error('Load pipelines error:', error);
        pipelineList.innerHTML = '<tr><td colspan="5" style="text-align: center; padding: 40px; color: #dc2626;">加载失败: ' + error.message + '</td></tr>';
    }
}
window.loadPipelines = loadPipelines;

function escPipelineHtml(text) {
    if (text == null) return '';
    return String(text).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
}

function pipelineTypeLabel(type) {
    if (type === 'ci') return 'CI';
    if (type === 'cd') return 'CD';
    if (type === 'cicd') return 'CI/CD';
    return type || 'CI/CD';
}

function pipelineTypeBadgeHtml(type) {
    const t = type || 'cicd';
    const colors = {
        ci: { bg: '#eff6ff', fg: '#1d4ed8', border: '#bfdbfe' },
        cd: { bg: '#f0fdf4', fg: '#15803d', border: '#bbf7d0' },
        cicd: { bg: '#f5f3ff', fg: '#6d28d9', border: '#ddd6fe' }
    };
    const c = colors[t] || colors.cicd;
    return `<span style="display:inline-block;padding:2px 8px;border-radius:999px;font-size:12px;font-weight:600;background:${c.bg};color:${c.fg};border:1px solid ${c.border};">${pipelineTypeLabel(t)}</span>`;
}

function resolvePipelinePhaseSteps(pipeline) {
    const defs = window.pipelineStepDefCache || [];
    const ciSteps = [];
    const cdSteps = [];
    const steps = (pipeline.steps || [])
        .filter(step => step.enabled !== false)
        .sort((a, b) => (a.order || 0) - (b.order || 0));

    steps.forEach(step => {
        let name = step.stepName;
        let phase = null;
        if (step.stepTemplateId) {
            const def = defs.find(d => d.id === step.stepTemplateId);
            if (def) {
                name = def.name;
                phase = def.phase;
            }
        }
        if (!phase) {
            phase = PIPELINE_CI_STEP_TYPE_KEYS.includes(step.stepType) ? 'ci' : 'cd';
        }
        if (!name && step.stepType) {
            name = PIPELINE_CI_STEP_TYPES[step.stepType] || PIPELINE_CD_STEP_TYPES[step.stepType] || step.stepType;
        }
        const label = name || '-';
        if (phase === 'cd') {
            cdSteps.push(label);
        } else {
            ciSteps.push(label);
        }
    });

    return { ciSteps, cdSteps };
}

function formatPipelineStepsInline(stepNames, phase) {
    const tagClass = phase === 'cd' ? 'pipeline-step-tag-cd' : 'pipeline-step-tag';
    if (!stepNames.length) {
        return '<span class="pipeline-flow-empty">暂无步骤</span>';
    }
    return stepNames.map(stepName =>
        `<span class="${tagClass}">${escPipelineHtml(stepName)}</span>`
    ).join('<span class="pipeline-step-arrow">→</span>');
}

function formatPipelineFlowHtml(buildNodeName, deployNodeName, ciSteps, cdSteps) {
    return `
        <div class="pipeline-flow">
            <div class="pipeline-flow-tracks">
                <div class="pipeline-flow-track pipeline-flow-ci">
                    <div class="pipeline-flow-track-header">
                        <span class="pipeline-flow-badge pipeline-flow-badge-ci">CI</span>
                        <span class="pipeline-flow-node" title="CI 候选构建节点（运行时按负载择一）">${escPipelineHtml(buildNodeName || '-')}</span>
                    </div>
                    <div class="pipeline-flow-track-steps">${formatPipelineStepsInline(ciSteps, 'ci')}</div>
                </div>
                <span class="pipeline-flow-divider" title="进入部署阶段">▸</span>
                <div class="pipeline-flow-track pipeline-flow-cd">
                    <div class="pipeline-flow-track-header">
                        <span class="pipeline-flow-badge pipeline-flow-badge-cd">CD</span>
                        <span class="pipeline-flow-node" title="CD 部署节点">${escPipelineHtml(deployNodeName || '-')}</span>
                    </div>
                    <div class="pipeline-flow-track-steps">${formatPipelineStepsInline(cdSteps, 'cd')}</div>
                </div>
            </div>
        </div>
    `;
}

// 渲染Pipeline列表
function renderPipelineList(pipelines) {
    const pipelineList = document.getElementById('pipelineList');
    if (!pipelineList) return;
    
    if (pipelines.length === 0) {
        pipelineList.innerHTML = '<tr><td colspan="5" style="text-align: center; padding: 40px; color: #999;">暂无 Pipeline 模版</td></tr>';
        return;
    }
    
    pipelineList.innerHTML = pipelines.map(pipeline => {
        const statusText = (pipeline.status === 1 || pipeline.status === null) 
            ? '<span style="color: #059669;">启用</span>' 
            : '<span style="color: #dc2626;">禁用</span>';
        const { ciSteps, cdSteps } = resolvePipelinePhaseSteps(pipeline);
        
        return `
            <tr>
                <td>${escPipelineHtml(pipeline.name || '-')}</td>
                <td>${pipelineTypeBadgeHtml(pipeline.pipelineType)}</td>
                <td>${formatPipelineFlowHtml(pipeline.buildNodeName, pipeline.deployNodeName, ciSteps, cdSteps)}</td>
                <td>${statusText}</td>
                <td>
                    <div class="table-actions">
                        <button class="btn-edit" onclick="window.editPipeline(${pipeline.id})">修改</button>
                        <button class="btn-secondary" onclick="window.clonePipeline(${pipeline.id})" title="基于当前模版快速复制一份">克隆</button>
                        <button class="btn-danger" onclick="window.deletePipeline(${pipeline.id})">删除</button>
                    </div>
                </td>
            </tr>
        `;
    }).join('');
}
window.renderPipelineList = renderPipelineList;

// 显示添加Pipeline模态框
async function showAddPipelineModal() {
    console.log('showAddPipelineModal called');
    
    if (typeof window.showModal !== 'function') {
        console.error('showModal function not available');
        alert('模态框功能未加载，请刷新页面');
        return;
    }
    
    // 刷新节点列表
    if (typeof window.refreshGlobalNodes === 'function') {
    try {
            await window.refreshGlobalNodes();
    } catch (error) {
        console.error('Error refreshing nodes:', error);
        }
    }
    if (typeof window.loadPipelineStepDefs === 'function') {
        await window.loadPipelineStepDefs(true);
    }
    
    const content = `
        <form id="createPipelineForm" style="max-height: 80vh; overflow-y: auto;">
            <div class="form-item">
                <label>模版名称 *</label>
                <input type="text" name="name" placeholder="例如: 标准构建发布流水线" required>
            </div>
            <div class="form-item">
                <label>流水线类型 *</label>
                <select name="pipelineType" required>
                    <option value="cicd" selected>CI/CD（构建 + 部署）</option>
                    <option value="ci">CI（仅构建）</option>
                    <option value="cd">CD（仅部署，可用于上线任务）</option>
                </select>
                <small style="color:#6b7280;">上线任务审批通过后只会使用「CD」类型模版</small>
            </div>
            <div class="form-item">
                <label>状态</label>
                <select name="status">
                    <option value="1" selected>启用</option>
                    <option value="0">禁用</option>
                </select>
            </div>
            ${buildPipelineNodeSectionHtml()}
        </form>
    `;
    
    window.showModal('添加 Pipeline 模版', content, async () => {
        const form = document.getElementById('createPipelineForm');
        if (!form) {
            alert('表单不存在');
            return;
        }
        
        const data = collectPipelineFormData(form);
        if (!data) return;
        
        try {
            const response = await fetch('/api/pipeline/create', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(data)
            });
            
            if (response.ok) {
                alert('创建成功');
                window.loadPipelines();
                window.closeModal();
            } else {
                const errorMessage = await (typeof window.handleApiError === 'function' ? window.handleApiError(response) : response.text());
                alert('创建失败: ' + errorMessage);
            }
        } catch (error) {
            console.error('Create pipeline error:', error);
            alert('创建失败: ' + (error.message || '网络错误'));
        }
    });
    
    setTimeout(async () => {
        await initDefaultPipelineSteps();
    }, 100);
}
// 立即注册到全局作用域
try {
window.showAddPipelineModal = showAddPipelineModal;
    console.log('✓ showAddPipelineModal registered to window:', typeof window.showAddPipelineModal);
} catch (error) {
    console.error('✗ Failed to register showAddPipelineModal:', error);
}

// 编辑Pipeline
async function editPipeline(id) {
    try {
        const response = await fetch(`/api/pipeline/${id}`);
        if (response.ok) {
            const pipeline = await response.json();
            showEditPipelineModal(pipeline);
        } else {
            alert('加载失败');
        }
    } catch (error) {
        alert('加载失败: ' + error.message);
    }
}
window.editPipeline = editPipeline;

/**
 * 克隆流水线模版：复制类型、步骤、节点等，名称可改。
 */
async function clonePipeline(id) {
    try {
        const response = await fetch(`/api/pipeline/${id}`);
        if (!response.ok) {
            alert('加载源模版失败');
            return;
        }
        const source = await response.json();
        const defaultName = (source.name || '未命名模版') + ' 副本';
        const newName = window.prompt('请输入新模版名称：', defaultName);
        if (newName === null) {
            return;
        }
        if (!String(newName).trim()) {
            alert('模版名称不能为空');
            return;
        }

        const cloneResp = await fetch(`/api/pipeline/${id}/clone`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ name: String(newName).trim() })
        });
        if (cloneResp.ok) {
            alert('克隆成功');
            if (typeof window.loadPipelines === 'function') {
                window.loadPipelines();
            }
        } else {
            const errorMessage = await (typeof window.handleApiError === 'function'
                ? window.handleApiError(cloneResp)
                : cloneResp.text());
            alert('克隆失败: ' + errorMessage);
        }
    } catch (error) {
        alert('克隆失败: ' + (error.message || '网络错误'));
    }
}
window.clonePipeline = clonePipeline;

// 显示编辑Pipeline模态框
async function showEditPipelineModal(pipeline) {
    if (typeof window.showModal !== 'function') {
        alert('模态框功能未加载，请刷新页面');
        return;
    }
    
    // 刷新节点列表
    if (typeof window.refreshGlobalNodes === 'function') {
        await window.refreshGlobalNodes();
    }
    if (typeof window.loadPipelineStepDefs === 'function') {
        await window.loadPipelineStepDefs(true);
    }
    
    const currentType = pipeline.pipelineType || 'cicd';
    const content = `
        <form id="editPipelineForm" style="max-height: 80vh; overflow-y: auto;">
            <div class="form-item">
                <label>模版名称 *</label>
                <input type="text" name="name" value="${escPipelineHtml(pipeline.name || '')}" required>
            </div>
            <div class="form-item">
                <label>流水线类型 *</label>
                <select name="pipelineType" required>
                    <option value="cicd" ${currentType === 'cicd' ? 'selected' : ''}>CI/CD（构建 + 部署）</option>
                    <option value="ci" ${currentType === 'ci' ? 'selected' : ''}>CI（仅构建）</option>
                    <option value="cd" ${currentType === 'cd' ? 'selected' : ''}>CD（仅部署，可用于上线任务）</option>
                </select>
                <small style="color:#6b7280;">上线任务审批通过后只会使用「CD」类型模版</small>
            </div>
            <div class="form-item">
                <label>状态</label>
                <select name="status">
                    <option value="1" ${pipeline.status !== 0 ? 'selected' : ''}>启用</option>
                    <option value="0" ${pipeline.status === 0 ? 'selected' : ''}>禁用</option>
                </select>
            </div>
            ${buildPipelineNodeSectionHtml(pipeline)}
        </form>
    `;
    
    window.showModal('编辑 Pipeline 模版', content, async () => {
        const form = document.getElementById('editPipelineForm');
        if (!form) {
            alert('表单不存在');
            return;
        }
        
        const data = collectPipelineFormData(form);
        if (!data) return;
        
        try {
            const response = await fetch(`/api/pipeline/${pipeline.id}`, {
                method: 'PUT',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(data)
            });
            
            if (response.ok) {
                alert('更新成功');
                window.loadPipelines();
                window.closeModal();
            } else {
                const errorMessage = await (typeof window.handleApiError === 'function' ? window.handleApiError(response) : response.text());
                alert('更新失败: ' + errorMessage);
            }
        } catch (error) {
            alert('更新失败: ' + error.message);
        }
    });
    
    setTimeout(async () => {
        if (pipeline.steps && pipeline.steps.length > 0) {
            await loadPipelineStepsFromConfig(pipeline.steps);
        } else {
            await initDefaultPipelineSteps();
        }
    }, 100);
}
window.showEditPipelineModal = showEditPipelineModal;

// Pipeline步骤计数器
// pipelineStepCounter 已在 core.js 中声明，这里不需要重复声明

// 关联 Pipeline 步骤（引用步骤定义）
async function addPipelineStepRef(phase, selectedTemplateId) {
    phase = phase || 'ci';
    const containerId = phase === 'cd' ? 'pipelineCdStepsContainer' : 'pipelineCiStepsContainer';
    const container = document.getElementById(containerId);
    if (!container) return;

    const stepId = 'step_' + (pipelineStepCounter++);
    const phaseLabel = phase === 'cd' ? 'CD' : 'CI';
    const optionsHtml = typeof window.renderStepDefSelectOptions === 'function'
        ? window.renderStepDefSelectOptions(phase, selectedTemplateId)
        : '<option value="">请选择步骤</option>';

    const stepHtml = `
        <div class="pipeline-step-item" data-step-id="${stepId}" data-step-phase="${phase}" style="border:1px solid #ccc;border-radius:4px;padding:12px;margin-bottom:12px;background:white;">
            <div style="display:flex;justify-content:space-between;align-items:center;margin-bottom:8px;">
                <strong>${phaseLabel} 步骤</strong>
                <div style="display:flex;gap:4px;align-items:center;">
                    <button type="button" class="btn-secondary" title="上移"
                        onclick="window.movePipelineStep('${stepId}', -1)" style="padding:2px 8px;font-size:12px;">↑</button>
                    <button type="button" class="btn-secondary" title="下移"
                        onclick="window.movePipelineStep('${stepId}', 1)" style="padding:2px 8px;font-size:12px;">↓</button>
                    <button type="button" class="btn-danger" onclick="window.removePipelineStep('${stepId}')" style="padding:2px 8px;font-size:12px;">删除</button>
                </div>
            </div>
            <div class="form-item" style="margin:0;">
                <label style="font-size:12px;display:block;margin-bottom:4px;">选择步骤定义 *</label>
                <select name="stepTemplateId_${stepId}" required style="width:100%;" onchange="window.updateStepNumbers()">
                    ${optionsHtml}
                </select>
            </div>
            <input type="hidden" name="stepOrder_${stepId}" value="1">
        </div>
    `;
    container.insertAdjacentHTML('beforeend', stepHtml);
    window.updateStepNumbers();
}
window.addPipelineStepRef = addPipelineStepRef;
window.addPipelineStep = addPipelineStepRef;

async function addPipelineCiStep() {
    await window.addPipelineStepRef('ci');
}
window.addPipelineCiStep = addPipelineCiStep;

async function addPipelineCdStep() {
    await window.addPipelineStepRef('cd');
}
window.addPipelineCdStep = addPipelineCdStep;

async function addPipelineStepWrapper() {
    await window.addPipelineCiStep();
}
window.addPipelineStepWrapper = addPipelineStepWrapper;

// 删除Pipeline步骤
function removePipelineStep(stepId) {
    const stepItem = document.querySelector(`[data-step-id="${stepId}"]`);
    if (stepItem) {
        stepItem.remove();
        window.updateStepNumbers();
    }
}
window.removePipelineStep = removePipelineStep;

function movePipelineStep(stepId, direction) {
    const stepItem = document.querySelector(`[data-step-id="${stepId}"]`);
    if (!stepItem || !stepItem.parentElement) return;

    const container = stepItem.parentElement;
    const siblings = Array.from(container.querySelectorAll('.pipeline-step-item'));
    const index = siblings.indexOf(stepItem);
    const targetIndex = index + direction;
    if (targetIndex < 0 || targetIndex >= siblings.length) return;

    const targetItem = siblings[targetIndex];
    if (direction < 0) {
        container.insertBefore(stepItem, targetItem);
    } else {
        container.insertBefore(targetItem, stepItem);
    }
    window.updateStepNumbers();
}
window.movePipelineStep = movePipelineStep;

// 更新步骤编号
function updateStepNumbers() {
    const ciContainer = document.getElementById('pipelineCiStepsContainer');
    const cdContainer = document.getElementById('pipelineCdStepsContainer');
    if (!ciContainer && !cdContainer) return;

    let globalOrder = 1;

    function updateContainer(container, phaseLabel) {
        if (!container) return;
        const items = Array.from(container.querySelectorAll('.pipeline-step-item'));
        items.forEach((step, index) => {
            const stepId = step.getAttribute('data-step-id');
            const orderInput = step.querySelector(`[name="stepOrder_${stepId}"]`);
            if (orderInput) {
                orderInput.value = globalOrder;
            }
            step.setAttribute('data-step-order', globalOrder);
            globalOrder += 1;

            const title = step.querySelector('strong');
            if (title) {
                title.textContent = `${phaseLabel} 步骤 ${index + 1}`;
            }

            const upBtn = step.querySelector('button[title="上移"]');
            const downBtn = step.querySelector('button[title="下移"]');
            if (upBtn) upBtn.disabled = index === 0;
            if (downBtn) downBtn.disabled = index === items.length - 1;
        });
    }

    updateContainer(ciContainer, 'CI');
    updateContainer(cdContainer, 'CD');
}
window.updateStepNumbers = updateStepNumbers;

// 收集Pipeline步骤（步骤定义引用）
function collectPipelineSteps() {
    const steps = [];
    const ciContainer = document.getElementById('pipelineCiStepsContainer');
    const cdContainer = document.getElementById('pipelineCdStepsContainer');
    const containers = [ciContainer, cdContainer].filter(Boolean);

    containers.forEach((container) => {
        container.querySelectorAll('.pipeline-step-item').forEach((item) => {
            const stepId = item.getAttribute('data-step-id');
            const templateSelect = item.querySelector(`[name="stepTemplateId_${stepId}"]`);
            const orderInput = item.querySelector(`[name="stepOrder_${stepId}"]`);

            if (templateSelect && templateSelect.value) {
                steps.push({
                    stepTemplateId: parseInt(templateSelect.value, 10),
                    order: parseInt(orderInput?.value, 10) || 1,
                    enabled: true
                });
            }
        });
    });

    const sorted = steps.sort((a, b) => a.order - b.order);
    for (let i = 0; i < sorted.length; i++) {
        if (!sorted[i].stepTemplateId) {
            alert('请为每个步骤选择步骤定义');
            return [];
        }
    }
    return sorted;
}
window.collectPipelineSteps = collectPipelineSteps;

// 删除Pipeline
async function deletePipeline(id) {
    if (!confirm('确定要删除这个Pipeline模板吗？')) return;
    
    try {
        const response = await fetch(`/api/pipeline/${id}`, { method: 'DELETE' });
        if (response.ok) {
            alert('删除成功');
            window.loadPipelines();
        } else {
            alert('删除失败');
        }
    } catch (error) {
        alert('删除失败: ' + error.message);
    }
}
window.deletePipeline = deletePipeline;

// ============================================
// 系统配置功能
// ============================================

// 加载系统配置
async function loadSystemConfigs() {
    try {
        const response = await fetch('/api/system/config/list');
        if (response.ok) {
            const configs = await response.json();
            if (configs && configs.length > 0) {
                configs.forEach(config => {
                    const input = document.querySelector(`[name="${config.configType}.${config.configKey}"]`);
                    if (input) {
                        if (input.type === 'checkbox') {
                            input.checked = config.configValue === 'true';
                        } else {
                        input.value = config.configValue || '';
                        }
                    }
                });
            }
        }
    } catch (error) {
        console.error('Load system configs error:', error);
    }
}
window.loadSystemConfigs = loadSystemConfigs;

// ============================================
// 模块初始化
// ============================================

// 模块加载完成后触发事件
(function() {
    console.log('=== System module initialization ===');
    console.log('showConfigTab available:', typeof window.showConfigTab);
    console.log('showAddComponentModal available:', typeof window.showAddComponentModal);
        console.log('showAddPipelineModal available:', typeof window.showAddPipelineModal);
    console.log('showSSOTab available:', typeof window.showSSOTab);
    console.log('loadComponents available:', typeof window.loadComponents);
    console.log('loadPipelines available:', typeof window.loadPipelines);
    
    // 验证所有关键函数是否已注册
    const requiredFunctions = [
        'showConfigTab', 'showSSOTab', 'loadSSOConfigs', 'loadNotifyConfigs',
        'loadComponents', 'renderComponentList', 'showAddComponentModal',
        'updateAuthConfigFields', 'editComponent', 'showEditComponentModal',
        'deleteComponent', 'loadPipelines', 'renderPipelineList',
        'showAddPipelineModal', 'editPipeline', 'clonePipeline', 'showEditPipelineModal',
        'deletePipeline', 'addPipelineStep', 'addPipelineStepWrapper',
        'removePipelineStep', 'updateStepNumbers', 'collectPipelineSteps',
        'loadSystemConfigs'
    ];
    
    const missingFunctions = requiredFunctions.filter(fn => typeof window[fn] !== 'function');
    if (missingFunctions.length > 0) {
        console.error('Missing functions:', missingFunctions);
    } else {
        console.log('All required functions are registered');
    }
    
    // 延迟触发事件，确保事件监听器已注册
    setTimeout(function() {
        if (typeof window.dispatchEvent !== 'undefined') {
            window.dispatchEvent(new CustomEvent('systemModuleLoaded'));
            console.log('systemModuleLoaded event dispatched');
        }
    }, 100);
})();
