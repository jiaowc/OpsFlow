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

// 系统设置标签页切换
function showConfigTab(tabName, element) {
    console.log('showConfigTab called with:', tabName);
    
    try {
        // 更新标签页状态
    document.querySelectorAll('.tab-btn').forEach(btn => btn.classList.remove('active'));
    document.querySelectorAll('.config-panel').forEach(panel => panel.classList.remove('active'));
    
    if (element) {
        element.classList.add('active');
    }
        
        // 显示对应的面板
    const panel = document.getElementById(tabName + '-config');
    if (panel) {
        panel.classList.add('active');
            console.log('Panel activated:', tabName + '-config');
        } else {
            console.error('Panel not found:', tabName + '-config');
    }
    
    // 根据选中的标签页加载数据
    switch(tabName) {
        case 'component':
                if (typeof window.loadComponents === 'function') {
                    window.loadComponents();
            }
            break;
        case 'pipeline':
                if (typeof window.loadPipelines === 'function') {
                    window.loadPipelines();
            }
            break;
        case 'sso':
                if (typeof window.loadSSOConfigs === 'function') {
                    window.loadSSOConfigs();
            }
            break;
            case 'ldap':
                console.log('LDAP tab clicked');
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

// 加载SSO配置
async function loadSSOConfigs() {
    try {
        const response = await fetch('/api/system/config/list');
        if (response.ok) {
            const configs = await response.json();
            if (configs && configs.length > 0) {
                // 加载飞书配置
                configs.filter(c => c.configType === 'feishu').forEach(config => {
                    const input = document.querySelector(`[name="${config.configType}.${config.configKey}"]`);
                    if (input) {
                        if (input.type === 'checkbox') {
                            input.checked = config.configValue === 'true';
                        } else {
                            input.value = config.configValue || '';
                        }
                    }
                });
                
                // 加载钉钉配置
                configs.filter(c => c.configType === 'dingtalk').forEach(config => {
                    const input = document.querySelector(`[name="${config.configType}.${config.configKey}"]`);
                    if (input) {
                        if (input.type === 'checkbox') {
                            input.checked = config.configValue === 'true';
                        } else {
                            input.value = config.configValue || '';
                        }
                    }
                });
                
                // 加载企业微信配置
                configs.filter(c => c.configType === 'wechatwork').forEach(config => {
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
        console.error('Load SSO configs error:', error);
    }
}
window.loadSSOConfigs = loadSSOConfigs;

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

// 检测所有组件的连接状态
async function checkAllComponents(components) {
    if (!components || components.length === 0) return;
    
    // 为每个组件创建检测任务
    const checkPromises = components.map(component => checkComponentStatus(component.id));
    
    // 并行执行所有检测（但限制并发数，避免过多请求）
    const batchSize = 3; // 每次最多3个并发检测
    for (let i = 0; i < checkPromises.length; i += batchSize) {
        const batch = checkPromises.slice(i, i + batchSize);
        await Promise.all(batch);
        // 批次之间稍作延迟，避免服务器压力过大
        if (i + batchSize < checkPromises.length) {
            await new Promise(resolve => setTimeout(resolve, 200));
        }
    }
}

// 检测单个组件的连接状态
async function checkComponentStatus(componentId) {
    try {
        const response = await fetch(`/api/component/${componentId}/test`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' }
        });
        
        const result = await response.json();
        
        // 更新对应行的状态显示
        updateComponentStatus(componentId, result.success);
    } catch (error) {
        console.error(`Check component ${componentId} status error:`, error);
        // 检测失败，显示为不可用
        updateComponentStatus(componentId, false);
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
        const authTypeText = {
            'api_key': 'API密钥',
            'username_password': '账户密码',
            'token': 'Token',
            'oauth': 'OAuth'
        }[component.authType] || component.authType;
        
        return `
            <tr>
                <td>${component.name}</td>
                <td>${component.type || '-'}</td>
                <td>${component.url || '-'}</td>
                <td>${authTypeText}</td>
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
                    <option value="harbor">Harbor</option>
                    <option value="jenkins">Jenkins</option>
                    <option value="nexus">Nexus</option>
                    <option value="sonarqube">SonarQube</option>
                    <option value="other">其他</option>
                </select>
            </div>
            <div class="form-item">
                <label>访问地址 *</label>
                <input type="text" name="url" placeholder="例如: https://gitlab.example.com" required>
            </div>
            <div class="form-item">
                <label>认证类型 *</label>
                <select name="authType" id="authTypeSelect" onchange="window.updateAuthConfigFields()" required>
                    <option value="">请选择认证类型</option>
                    <option value="api_key">API密钥</option>
                    <option value="username_password">账户密码</option>
                    <option value="token">Token</option>
                    <option value="oauth">OAuth</option>
                </select>
            </div>
            <div id="authConfigFields">
                <!-- 认证配置字段将根据认证类型动态生成 -->
            </div>
            <div class="form-item">
                <label>描述</label>
                <textarea name="description" rows="3" placeholder="组件描述"></textarea>
            </div>
        </form>
    `;
    
    window.showModal('添加组件', content, async () => {
        const form = document.getElementById('createComponentForm');
        const formData = new FormData(form);
        
        // 构建认证配置
        const authType = formData.get('authType');
        const authConfig = {};
        
        if (authType === 'api_key') {
            authConfig.apiKey = formData.get('apiKey') || '';
        } else if (authType === 'username_password') {
            authConfig.username = formData.get('username') || '';
            authConfig.password = formData.get('password') || '';
        } else if (authType === 'token') {
            authConfig.token = formData.get('token') || '';
        } else if (authType === 'oauth') {
            authConfig.clientId = formData.get('clientId') || '';
            authConfig.clientSecret = formData.get('clientSecret') || '';
        }
        
        const data = {
            name: formData.get('name'),
            type: formData.get('type'),
            url: formData.get('url'),
            authType: authType,
            authConfig: authConfig,
            description: formData.get('description') || ''
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
    const authConfig = component.authConfig || {};
    let authFieldsHtml = '';
    
    if (component.authType === 'api_key') {
        authFieldsHtml = `
            <div class="form-item">
                <label>API密钥 *</label>
                <input type="text" name="apiKey" value="${authConfig.apiKey || ''}" required>
            </div>
        `;
    } else if (component.authType === 'username_password') {
        authFieldsHtml = `
            <div class="form-item">
                <label>用户名 *</label>
                <input type="text" name="username" value="${authConfig.username || ''}" required>
            </div>
            <div class="form-item">
                <label>密码 *</label>
                <input type="password" name="password" placeholder="留空则不修改">
            </div>
        `;
    } else if (component.authType === 'token') {
        authFieldsHtml = `
            <div class="form-item">
                <label>Token *</label>
                <input type="text" name="token" value="${authConfig.token || ''}" required>
            </div>
        `;
    } else if (component.authType === 'oauth') {
        authFieldsHtml = `
            <div class="form-item">
                <label>Client ID *</label>
                <input type="text" name="clientId" value="${authConfig.clientId || ''}" required>
            </div>
            <div class="form-item">
                <label>Client Secret *</label>
                <input type="password" name="clientSecret" placeholder="留空则不修改">
            </div>
        `;
    }
    
    const content = `
        <form id="editComponentForm">
            <div class="form-item">
                <label>组件名称 *</label>
                <input type="text" name="name" value="${component.name || ''}" required>
            </div>
            <div class="form-item">
                <label>组件类型 *</label>
                <select name="type" required>
                    <option value="gitlab" ${component.type === 'gitlab' ? 'selected' : ''}>GitLab</option>
                    <option value="harbor" ${component.type === 'harbor' ? 'selected' : ''}>Harbor</option>
                    <option value="jenkins" ${component.type === 'jenkins' ? 'selected' : ''}>Jenkins</option>
                    <option value="nexus" ${component.type === 'nexus' ? 'selected' : ''}>Nexus</option>
                    <option value="sonarqube" ${component.type === 'sonarqube' ? 'selected' : ''}>SonarQube</option>
                    <option value="other" ${component.type === 'other' ? 'selected' : ''}>其他</option>
                </select>
            </div>
            <div class="form-item">
                <label>访问地址 *</label>
                <input type="text" name="url" value="${component.url || ''}" required>
            </div>
            <div class="form-item">
                <label>认证类型 *</label>
                <select name="authType" required>
                    <option value="api_key" ${component.authType === 'api_key' ? 'selected' : ''}>API密钥</option>
                    <option value="username_password" ${component.authType === 'username_password' ? 'selected' : ''}>账户密码</option>
                    <option value="token" ${component.authType === 'token' ? 'selected' : ''}>Token</option>
                    <option value="oauth" ${component.authType === 'oauth' ? 'selected' : ''}>OAuth</option>
                </select>
            </div>
            <div id="authConfigFields">
                ${authFieldsHtml}
            </div>
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
        
        const authType = formData.get('authType');
        const authConfig = {};
        
        if (authType === 'api_key') {
            authConfig.apiKey = formData.get('apiKey') || '';
        } else if (authType === 'username_password') {
            authConfig.username = formData.get('username') || '';
            const password = formData.get('password');
            if (password) {
                authConfig.password = password;
            }
        } else if (authType === 'token') {
            authConfig.token = formData.get('token') || '';
        } else if (authType === 'oauth') {
            authConfig.clientId = formData.get('clientId') || '';
            const clientSecret = formData.get('clientSecret');
            if (clientSecret) {
                authConfig.clientSecret = clientSecret;
            }
        }
        
        const data = {
            name: formData.get('name'),
            type: formData.get('type'),
            url: formData.get('url'),
            authType: authType,
            authConfig: authConfig,
            description: formData.get('description') || ''
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
        const response = await fetch(`/api/component/${id}/test`, {
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
// Pipeline管理功能
// ============================================

// 加载Pipeline列表
async function loadPipelines() {
    const pipelineList = document.getElementById('pipelineList');
    if (!pipelineList) {
        console.warn('Pipeline列表容器不存在');
        return;
    }
    
    pipelineList.innerHTML = '<tr><td colspan="4" style="text-align: center; padding: 40px; color: #999;">加载中...</td></tr>';
    
    try {
        const response = await fetch('/api/pipeline/list');
        if (response.ok) {
            const pipelines = await response.json();
            if (pipelines && pipelines.length > 0) {
                renderPipelineList(pipelines);
            } else {
                pipelineList.innerHTML = '<tr><td colspan="4" style="text-align: center; padding: 40px; color: #999;">暂无Pipeline模板</td></tr>';
            }
        } else {
            const errorText = await response.text();
            pipelineList.innerHTML = '<tr><td colspan="4" style="text-align: center; padding: 40px; color: #dc2626;">加载失败: ' + (errorText || '未知错误') + '</td></tr>';
        }
    } catch (error) {
        console.error('Load pipelines error:', error);
        pipelineList.innerHTML = '<tr><td colspan="4" style="text-align: center; padding: 40px; color: #dc2626;">加载失败: ' + error.message + '</td></tr>';
    }
}
window.loadPipelines = loadPipelines;

// 渲染Pipeline列表
function renderPipelineList(pipelines) {
    const pipelineList = document.getElementById('pipelineList');
    if (!pipelineList) return;
    
    if (pipelines.length === 0) {
        pipelineList.innerHTML = '<tr><td colspan="4" style="text-align: center; padding: 40px; color: #999;">暂无Pipeline模板</td></tr>';
        return;
    }
    
    pipelineList.innerHTML = pipelines.map(pipeline => {
        const statusText = (pipeline.status === 1 || pipeline.status === null) 
            ? '<span style="color: #059669;">启用</span>' 
            : '<span style="color: #dc2626;">禁用</span>';
        
        return `
            <tr>
                <td>${pipeline.name || '-'}</td>
                <td>${pipeline.description || '-'}</td>
                <td>${statusText}</td>
                <td>
                    <button class="btn-edit" onclick="window.editPipeline(${pipeline.id})">修改</button>
                    <button class="btn-danger" onclick="window.deletePipeline(${pipeline.id})">删除</button>
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
    
    const content = `
        <form id="createPipelineForm" style="max-height: 80vh; overflow-y: auto;">
            <div class="form-item">
                <label>Pipeline名称 *</label>
                <input type="text" name="name" placeholder="例如: 标准构建部署Pipeline" required>
            </div>
            <div class="form-item">
                <label>描述</label>
                <textarea name="description" rows="2" placeholder="Pipeline描述"></textarea>
            </div>
            <div class="form-item">
                <label style="display: flex; justify-content: space-between; align-items: center;">
                    <span>Pipeline步骤配置 *</span>
                    <button type="button" class="btn-secondary" onclick="window.addPipelineStepWrapper()" style="padding: 4px 12px; font-size: 12px;">添加步骤</button>
                </label>
                <div id="pipelineStepsContainer" style="border: 1px solid #ddd; border-radius: 4px; padding: 12px; background: #f9f9f9;">
                    <!-- 步骤列表将动态添加 -->
                </div>
            </div>
        </form>
    `;
    
    window.showModal('添加Pipeline模板', content, async () => {
        const form = document.getElementById('createPipelineForm');
        if (!form) {
            alert('表单不存在');
            return;
        }
        
        const steps = window.collectPipelineSteps();
        if (steps.length === 0) {
            alert('请至少添加一个Pipeline步骤');
            return;
        }
        
        const nameInput = form.querySelector('[name="name"]');
        if (!nameInput || !nameInput.value.trim()) {
            alert('Pipeline名称不能为空');
            return;
        }
        
        const data = {
            name: nameInput.value.trim(),
            description: (form.querySelector('[name="description"]')?.value || '').trim(),
            steps: steps
        };
        
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
    
    // 初始化默认步骤
    setTimeout(async () => {
        await window.addPipelineStep('checkout', '拉取代码', 1);
        await window.addPipelineStep('build', '构建打包', 2);
        await window.addPipelineStep('deploy', '部署', 3);
        await window.addPipelineStep('notify', '通知', 4);
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
    
    const content = `
        <form id="editPipelineForm" style="max-height: 80vh; overflow-y: auto;">
            <div class="form-item">
                <label>Pipeline名称 *</label>
                <input type="text" name="name" value="${pipeline.name || ''}" required>
            </div>
            <div class="form-item">
                <label>描述</label>
                <textarea name="description" rows="2">${pipeline.description || ''}</textarea>
            </div>
            <div class="form-item">
                <label style="display: flex; justify-content: space-between; align-items: center;">
                    <span>Pipeline步骤配置 *</span>
                    <button type="button" class="btn-secondary" onclick="window.addPipelineStepWrapper()" style="padding: 4px 12px; font-size: 12px;">添加步骤</button>
                </label>
                <div id="pipelineStepsContainer" style="border: 1px solid #ddd; border-radius: 4px; padding: 12px; background: #f9f9f9;">
                    <!-- 步骤列表将动态添加 -->
                </div>
            </div>
        </form>
    `;
    
    window.showModal('编辑Pipeline模板', content, async () => {
        const form = document.getElementById('editPipelineForm');
        if (!form) {
            alert('表单不存在');
            return;
        }
        
        const steps = window.collectPipelineSteps();
        if (steps.length === 0) {
            alert('请至少添加一个Pipeline步骤');
            return;
        }
        
        const nameInput = form.querySelector('[name="name"]');
        if (!nameInput || !nameInput.value.trim()) {
            alert('Pipeline名称不能为空');
            return;
        }
        
        const data = {
            name: nameInput.value.trim(),
            description: (form.querySelector('[name="description"]')?.value || '').trim(),
            steps: steps
        };
        
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
    
    // 加载已有步骤
    setTimeout(async () => {
        if (pipeline.steps && pipeline.steps.length > 0) {
            for (const step of pipeline.steps) {
                await window.addPipelineStep(step.stepType, step.stepName, step.order, step);
            }
        } else {
            await window.addPipelineStep('checkout', '拉取代码', 1);
            await window.addPipelineStep('build', '构建打包', 2);
            await window.addPipelineStep('deploy', '部署', 3);
            await window.addPipelineStep('notify', '通知', 4);
        }
    }, 100);
}
window.showEditPipelineModal = showEditPipelineModal;

// Pipeline步骤计数器
// pipelineStepCounter 已在 core.js 中声明，这里不需要重复声明

// 添加Pipeline步骤
async function addPipelineStep(stepType, stepName, order, existingStep) {
    const container = document.getElementById('pipelineStepsContainer');
    if (!container) return;
    
    const stepId = 'step_' + (pipelineStepCounter++);
    const step = existingStep || {
        stepType: stepType || 'checkout',
        stepName: stepName || '步骤',
        parameters: {},
        order: order || 1
    };
    
    const stepTypeOptions = {
        'checkout': '拉取代码',
        'build': '构建打包',
        'deploy': '部署',
        'clean': '清理缓存',
        'notify': '通知'
    };
    
    const stepHtml = `
        <div class="pipeline-step-item" data-step-id="${stepId}" data-step-order="${step.order || 1}" style="border: 1px solid #ccc; border-radius: 4px; padding: 12px; margin-bottom: 12px; background: white;">
            <div style="display: flex; justify-content: space-between; align-items: center; margin-bottom: 8px;">
                <strong>步骤 ${step.order || 1}</strong>
                <button type="button" class="btn-danger" onclick="window.removePipelineStep('${stepId}')" style="padding: 2px 8px; font-size: 12px;">删除</button>
            </div>
            <div style="display: grid; grid-template-columns: 1fr 1fr; gap: 12px; align-items: end;">
                <div>
                    <label style="font-size: 12px; display: block; margin-bottom: 4px;">步骤名称 *</label>
                    <input type="text" name="stepName_${stepId}" value="${step.stepName || ''}" required style="width: 100%;">
                </div>
                <div>
                    <label style="font-size: 12px; display: block; margin-bottom: 4px;">步骤类型 *</label>
                    <select name="stepType_${stepId}" required style="width: 100%;">
                        ${Object.entries(stepTypeOptions).map(([key, label]) => 
                            `<option value="${key}" ${step.stepType === key ? 'selected' : ''}>${label}</option>`
                        ).join('')}
                    </select>
                </div>
            </div>
            <input type="hidden" name="stepOrder_${stepId}" value="${step.order || 1}">
        </div>
    `;
    
    container.insertAdjacentHTML('beforeend', stepHtml);
    window.updateStepNumbers();
}
window.addPipelineStep = addPipelineStep;

// 添加Pipeline步骤的包装函数
async function addPipelineStepWrapper() {
    await window.addPipelineStep();
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

// 更新步骤编号
function updateStepNumbers() {
    const container = document.getElementById('pipelineStepsContainer');
    if (!container) return;
    
    const steps = Array.from(container.querySelectorAll('.pipeline-step-item'));
    steps.sort((a, b) => {
        const orderA = parseInt(a.getAttribute('data-step-order')) || 0;
        const orderB = parseInt(b.getAttribute('data-step-order')) || 0;
        return orderA - orderB;
    });
    
    steps.forEach(step => {
        container.appendChild(step);
    });
    
    steps.forEach((step, index) => {
        const newOrder = index + 1;
        const orderInput = step.querySelector('input[type="hidden"][name^="stepOrder_"]');
        if (orderInput) {
            orderInput.value = newOrder;
        }
        step.setAttribute('data-step-order', newOrder);
        const title = step.querySelector('strong');
        if (title) {
            title.textContent = `步骤 ${newOrder}`;
        }
    });
}
window.updateStepNumbers = updateStepNumbers;

// 收集Pipeline步骤
function collectPipelineSteps() {
    const steps = [];
    const stepItems = document.querySelectorAll('.pipeline-step-item');
    
    stepItems.forEach((item) => {
        const stepId = item.getAttribute('data-step-id');
        const stepTypeInput = item.querySelector(`[name="stepType_${stepId}"]`);
        const stepNameInput = item.querySelector(`[name="stepName_${stepId}"]`);
        const orderInput = item.querySelector(`[name="stepOrder_${stepId}"]`);
        
        if (stepTypeInput && stepNameInput && orderInput) {
        steps.push({
                stepType: stepTypeInput.value,
                stepName: stepNameInput.value,
                parameters: {},
                order: parseInt(orderInput.value) || 1
        });
        }
    });
    
    return steps.sort((a, b) => a.order - b.order);
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
        'showConfigTab', 'showSSOTab', 'loadSSOConfigs',
        'loadComponents', 'renderComponentList', 'showAddComponentModal',
        'updateAuthConfigFields', 'editComponent', 'showEditComponentModal',
        'deleteComponent', 'loadPipelines', 'renderPipelineList',
        'showAddPipelineModal', 'editPipeline', 'showEditPipelineModal',
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
