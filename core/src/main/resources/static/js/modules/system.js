// ============================================
// 系统设置模块（组件、Pipeline）
// ============================================

// ============================================
// 标签页切换功能
// ============================================

/** 扩展页面标签页的加载函数：tabName -> fn(sectionElement) */
const configTabLoaders = {};

function registerConfigTabLoader(tabName, loader) {
    if (tabName && typeof loader === 'function') {
        configTabLoaders[tabName] = loader;
    }
}
window.registerConfigTabLoader = registerConfigTabLoader;

/** 撤销登记（扩展加载失败回滚时用）；只在仍是同一个函数时删除 */
function unregisterConfigTabLoader(tabName, loader) {
    if (tabName && (!loader || configTabLoaders[tabName] === loader)) {
        delete configTabLoaders[tabName];
    }
}
window.unregisterConfigTabLoader = unregisterConfigTabLoader;

// 系统/配置区标签页切换（流水线配置、凭据，以及扩展提供的配置页）
function showConfigTab(tabName, element) {
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
        default:
                // 扩展追加到配置页的页签通过 registerConfigTabLoader 注册加载函数
                if (configTabLoaders[tabName]) {
                    configTabLoaders[tabName](scope);
                }
                break;
        }
    } catch (error) {
        console.error('Error in showConfigTab:', error);
        notify((t('system.msg.tabFailed') + ': ') + error.message);
    }
}
window.showConfigTab = showConfigTab;

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
    
    componentList.innerHTML = ('<tr><td colspan="6" style="text-align: center; padding: 40px; color: var(--text-4);">' + t('system.component.empty') + '</td></tr>');
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
            throw new Error(t('system.component.batchCheckFailed'));
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
        statusCell.innerHTML = ('<span style="color: #059669; font-size: 14px;">' + t('system.component.available') + '</span>');
    } else {
        statusCell.innerHTML = ('<span style="color: #dc2626; font-size: 14px;">' + t('system.component.unavailable') + '</span>');
    }
}

// 渲染组件列表
function renderComponentList(components) {
    const componentList = document.getElementById('componentList');
    if (!componentList) return;
    
    if (components.length === 0) {
        componentList.innerHTML = ('<tr><td colspan="6" style="text-align: center; padding: 40px; color: var(--text-4);">' + t('system.component.empty') + '</td></tr>');
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
            'other': t('system.component.typeOther')
        }[component.type] || component.type;
        const authTypeText = {
            'api_key': t('system.component.authApiKey'),
            'username_password': t('system.component.authPassword'),
            'token': 'Token',
            'oauth': 'OAuth',
            'kubeconfig': t('system.component.authKubeconfig'),
            'ssh_key': t('system.component.authSshKey')
        }[component.authType] || component.authType;
        const authDisplay = component.credentialName
            ? `${t('system.component.keychain')}: ${component.credentialName}`
            : authTypeText;
        
        return `
            <tr>
                <td>${escapeHtml(component.name)}</td>
                <td>${escapeHtml(componentTypeText)}</td>
                <td title="${escapeHtml(component.url || '')}">${escapeHtml(component.url || '-')}</td>
                <td>${escapeHtml(authDisplay)}</td>
                <td data-component-id="${component.id}">
                    <span style="color: #6b7280; font-size: 14px;">${t('system.component.checking')}</span>
                </td>
                <td>
                    <button class="btn-secondary" onclick="window.testComponent(${component.id})" style="margin-right: 5px;">${t('system.component.check')}</button>
                    <button class="btn-edit" onclick="window.editComponent(${component.id})">${t('common.modify')}</button>
                    <button class="btn-danger" onclick="window.deleteComponent(${component.id})">${t('common.delete')}</button>
                </td>
            </tr>
        `;
    }).join('');
}
window.renderComponentList = renderComponentList;

// 显示添加组件模态框
function showAddComponentModal() {
    if (typeof window.showModal !== 'function') {
        console.error('showModal function not available');
        notify(t('system.msg.modalNotLoaded'));
        return;
    }
    
    const content = `
        <form id="createComponentForm">
            <div class="form-item">
                <label>${t('system.component.name')}${REQUIRED_MARK}</label>
                <input type="text" name="name" placeholder="${t('system.component.namePlaceholder')}" required>
            </div>
            <div class="form-item">
                <label>${t('system.component.type')}${REQUIRED_MARK}</label>
                <select name="type" required>
                    <option value="">${t('system.component.selectType')}</option>
                    <option value="gitlab">GitLab</option>
                    <option value="github">GitHub</option>
                    <option value="harbor">Harbor</option>
                    <option value="nexus">Nexus</option>
                    <option value="sonarqube">SonarQube</option>
                    <option value="k8s">K8s</option>
                    <option value="other">${t('system.component.typeOther')}</option>
                </select>
            </div>
            <div class="form-item">
                <label>${t('system.component.url')}${REQUIRED_MARK}</label>
                <input type="text" name="url" placeholder="${t('system.component.urlPlaceholder')}" required>
            </div>
            ${typeof window.buildComponentCredentialField === 'function' ? window.buildComponentCredentialField() : ''}
            <div class="form-item">
                <label>${t('common.description')}</label>
                <textarea name="description" rows="3" placeholder="${t('system.component.descriptionPlaceholder')}"></textarea>
            </div>
        </form>
    `;
    
    window.showModal(t('system.component.createTitle'), content, async () => {
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
                notify(t('common.createOk'));
                window.loadComponents();
                window.closeModal();
            } else {
                const errorMessage = await (typeof window.handleApiError === 'function' ? window.handleApiError(response) : response.text());
                notify((t('common.createFailed') + ': ') + errorMessage);
            }
        } catch (error) {
            console.error('Create component error:', error);
            notify((t('common.createFailed') + ': ') + (error.message || t('common.networkError')));
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
window.showAddComponentModal = showAddComponentModal;

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
                <label>${t('system.component.authApiKey')}${REQUIRED_MARK}</label>
                <input type="text" name="apiKey" placeholder="${t('system.component.apiKeyPlaceholder')}" required>
            </div>
        `;
    } else if (authType === 'username_password') {
        fieldsHtml = `
            <div class="form-item">
                <label>${t('system.component.username')}${REQUIRED_MARK}</label>
                <input type="text" name="username" placeholder="${t('system.component.usernamePlaceholder')}" required>
            </div>
            <div class="form-item">
                <label>${t('system.component.password')}${REQUIRED_MARK}</label>
                <input type="password" name="password" placeholder="${t('system.component.passwordPlaceholder')}" required>
            </div>
        `;
    } else if (authType === 'token') {
        fieldsHtml = `
            <div class="form-item">
                <label>Token${REQUIRED_MARK}</label>
                <input type="text" name="token" placeholder="${t('system.component.tokenPlaceholder')}" required>
            </div>
        `;
    } else if (authType === 'oauth') {
        fieldsHtml = `
            <div class="form-item">
                <label>Client ID${REQUIRED_MARK}</label>
                <input type="text" name="clientId" placeholder="${t('system.component.clientIdPlaceholder')}" required>
            </div>
            <div class="form-item">
                <label>Client Secret${REQUIRED_MARK}</label>
                <input type="password" name="clientSecret" placeholder="${t('system.component.clientSecretPlaceholder')}" required>
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
            notify(t('common.loadFailed'));
        }
    } catch (error) {
        notify((t('common.loadFailed') + ': ') + error.message);
    }
}
window.editComponent = editComponent;

// 显示编辑组件模态框
function showEditComponentModal(component) {
    const content = `
        <form id="editComponentForm">
            <div class="form-item">
                <label>${t('system.component.name')}${REQUIRED_MARK}</label>
                <input type="text" name="name" value="${escapeHtml(component.name || '')}" required>
            </div>
            <div class="form-item">
                <label>${t('system.component.type')}${REQUIRED_MARK}</label>
                <select name="type" id="editComponentTypeSelect" required>
                    <option value="gitlab" ${component.type === 'gitlab' ? 'selected' : ''}>GitLab</option>
                    <option value="github" ${component.type === 'github' ? 'selected' : ''}>GitHub</option>
                    <option value="harbor" ${component.type === 'harbor' ? 'selected' : ''}>Harbor</option>
                    <option value="nexus" ${component.type === 'nexus' ? 'selected' : ''}>Nexus</option>
                    <option value="sonarqube" ${component.type === 'sonarqube' ? 'selected' : ''}>SonarQube</option>
                    <option value="k8s" ${component.type === 'k8s' ? 'selected' : ''}>K8s</option>
                    <option value="other" ${component.type === 'other' ? 'selected' : ''}>${t('system.component.typeOther')}</option>
                </select>
            </div>
            <div class="form-item">
                <label>${t('system.component.url')}${REQUIRED_MARK}</label>
                <input type="text" name="url" value="${escapeHtml(component.url || '')}" required>
            </div>
            ${typeof window.buildComponentCredentialField === 'function' ? window.buildComponentCredentialField(component) : ''}
            <div class="form-item">
                <label>${t('common.description')}</label>
                <textarea name="description" rows="3">${escapeHtml(component.description || '')}</textarea>
            </div>
        </form>
    `;
    
    if (typeof window.showModal !== 'function') {
        notify(t('system.msg.modalNotLoaded'));
        return;
    }
    
    window.showModal(t('system.component.editTitle'), content, async () => {
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
                notify(t('common.updatedOk'));
                window.loadComponents();
                window.closeModal();
            } else {
                const error = await response.json().catch(() => ({ message: t('common.unknownError') }));
                notify((t('common.updatedFailed') + ': ') + (error.message || t('common.unknownError')));
            }
        } catch (error) {
            notify((t('common.updatedFailed') + ': ') + error.message);
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
    if (!await confirmDialog(t('system.component.deleteConfirm'))) return;
    
    try {
        const response = await fetch(`/api/component/${id}`, { method: 'DELETE' });
        if (response.ok) {
            notify(t('common.deleteOk'));
            window.loadComponents();
        } else {
            notify(t('common.deleteFailed'));
        }
    } catch (error) {
        notify((t('common.deleteFailed') + ': ') + error.message);
    }
}
window.deleteComponent = deleteComponent;

// 测试组件连接（手动检测）
async function testComponent(id) {
    // 显示加载状态 - 通过查找按钮元素
    const testBtn = document.querySelector(`button[onclick*="testComponent(${id})"]`);
    const originalText = testBtn ? testBtn.textContent : t('system.component.check');
    if (testBtn) {
        testBtn.disabled = true;
        testBtn.textContent = t('system.component.checking');
    }
    
    // 更新状态显示为"检测中..."
    const statusCell = document.querySelector(`td[data-component-id="${id}"]`);
    if (statusCell) {
        statusCell.innerHTML = ('<span style="color: #6b7280; font-size: 14px;">' + t('system.component.checking') + '</span>');
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
            notify('✓ ' + t('system.component.testOk') + '\n\n' + result.message, 'success');
        } else {
            notify('✗ ' + t('system.component.testFailed') + '\n\n' + result.message, 'error');
        }
    } catch (error) {
        console.error('Test component error:', error);
        updateComponentStatus(id, false);
        notify((t('system.component.testError') + ': ') + (error.message || t('common.networkError')));
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

// 值是步骤类型名称的文案 key（文案在 js/i18n/messages/step-def.js），显示时再翻译
const PIPELINE_CI_STEP_TYPES = {
    'checkout': 'stepdef.type.checkout',
    'build': 'stepdef.type.build',
    'docker_build': 'stepdef.type.dockerBuild',
    'push_image': 'stepdef.type.pushImage',
    'clean': 'stepdef.type.clean',
    'clean_workspace': 'stepdef.type.cleanWorkspace'
};
const PIPELINE_CD_STEP_TYPES = {
    'deploy': 'stepdef.type.deploy',
    'check_deploy': 'stepdef.type.checkDeploy',
    'render_template': 'stepdef.type.renderTemplate',
    'rollback': 'stepdef.type.rollback',
    'notify': 'stepdef.type.notify'
};
const PIPELINE_CI_STEP_TYPE_KEYS = Object.keys(PIPELINE_CI_STEP_TYPES);

function renderPipelineNodeSelectOptions(nodeType, selectedValue) {
    const nodes = (mockNodes || []).filter(n => n.nodeType === nodeType);
    const selectedIds = Array.isArray(selectedValue)
        ? selectedValue.map(v => String(v))
        : (selectedValue != null ? [String(selectedValue)] : []);
    let html = ('<option value="">' + t('system.pipeline.nodeNone') + '</option>');
    if (nodes.length === 0) {
        html += ('<option value="" disabled>' + t('system.pipeline.noNodes') + '</option>');
    }
    nodes.forEach(n => {
        const selected = selectedIds.includes(String(n.id)) ? ' selected' : '';
        const host = n.host ? ` (${n.host})` : '';
        html += `<option value="${n.id}"${selected}>${escapeHtml(n.name)}${escapeHtml(host)}</option>`;
    });
    return html;
}

function getPipelineNodeMultiSelectState(pipeline) {
    const selectedIds = Array.isArray(pipeline.buildNodeIds) ? pipeline.buildNodeIds.map(v => String(v)) : [];
    const nodes = (mockNodes || []).filter(n => n.nodeType === 'build');
    return { selectedIds, nodes };
}

function renderPipelineBuildNodeMultiSelect(pipeline) {
    const { selectedIds, nodes } = getPipelineNodeMultiSelectState(pipeline || {});
    const selectedNodes = nodes.filter(n => selectedIds.includes(String(n.id)));
    const displayHtml = selectedNodes.length
        ? selectedNodes.map(n => {
            const host = n.host ? ` (${n.host})` : '';
            return `<span class="tag-item" data-node-id="${n.id}">
                ${escapeHtml(n.name)}${escapeHtml(host)}
                <span class="tag-item-remove" onclick="removePipelineBuildNode(event, '${n.id}')">×</span>
            </span>`;
        }).join('')
        : ('<span class="tag-placeholder">' + t('system.pipeline.buildNodesPlaceholder') + '</span>');
    const optionsHtml = nodes.length
        ? nodes.map(n => {
            const host = n.host ? ` (${n.host})` : '';
            const checked = selectedIds.includes(String(n.id)) ? ' checked' : '';
            return `<label class="tag-option">
                <input type="checkbox" value="${n.id}" data-name="${escapeHtml(n.name)}" data-host="${escapeHtml(n.host || '')}" onchange="togglePipelineBuildNode(this)"${checked}>
                <span>${escapeHtml(n.name)}${escapeHtml(host)}</span>
            </label>`;
        }).join('')
        : ('<div class="tag-option"><span>' + t('system.pipeline.noBuildNodes') + '</span></div>');

    return `
        <div class="tag-input-container" onclick="togglePipelineBuildNodeDropdown()">
            <div class="tag-input-display" id="pipelineBuildNodeDisplay">${displayHtml}</div>
            <span class="tag-arrow">▼</span>
        </div>
        <input type="hidden" name="buildNodeIds" id="pipelineBuildNodeIdsInput" value="${escapeHtml(selectedIds.join(','))}">
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
        display.innerHTML = ('<span class="tag-placeholder">' + t('system.pipeline.buildNodesPlaceholder') + '</span>');
        return;
    }

    display.innerHTML = checked.map(cb => {
        const name = cb.dataset.name || cb.value;
        const host = cb.dataset.host ? ` (${cb.dataset.host})` : '';
        return `<span class="tag-item" data-node-id="${escapeHtml(cb.value)}">
            ${escapeHtml(name)}${escapeHtml(host)}
            <span class="tag-item-remove" onclick="removePipelineBuildNode(event, '${escapeHtml(cb.value)}')">×</span>
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
            <h4 style="margin: 0 0 12px 0; color: #1d4ed8;">${t('system.pipeline.ciPhase')}</h4>
            <div class="form-item">
                <label>${t('system.pipeline.buildNodes')}</label>
                <div id="pipelineBuildNodeMultiSelect" style="position:relative;">
                    ${renderPipelineBuildNodeMultiSelect(pipeline)}
                </div>
                <small style="color:var(--text-3);display:block;margin-top:4px;">${t('system.pipeline.buildNodesHint')}</small>
            </div>
            <div class="form-item">
                <label style="display:flex;justify-content:space-between;align-items:center;">
                    <span>${t('system.pipeline.ciSteps')}</span>
                    <button type="button" class="btn-secondary" onclick="window.addPipelineCiStep()" style="padding:4px 12px;font-size:12px;">${t('system.pipeline.addCiStep')}</button>
                </label>
                <div id="pipelineCiStepsContainer" style="border:1px solid var(--border-strong);border-radius:4px;padding:12px;background:#f9f9f9;min-height:40px;"></div>
            </div>
        </div>
        <div style="border: 1px solid #bbf7d0; border-radius: 6px; padding: 16px; background: #f0fdf4;">
            <h4 style="margin: 0 0 12px 0; color: #15803d;">${t('system.pipeline.cdPhase')}</h4>
            <div class="form-item">
                <label>${t('system.pipeline.deployNode')}</label>
                <select name="deployNodeId">${renderPipelineNodeSelectOptions('deploy', pipeline.deployNodeId)}</select>
                <small style="color:var(--text-3);display:block;margin-top:4px;">${t('system.pipeline.deployNodeHint')}</small>
            </div>
            <div class="form-item">
                <label style="display:flex;justify-content:space-between;align-items:center;">
                    <span>${t('system.pipeline.cdSteps')}</span>
                    <button type="button" class="btn-secondary" onclick="window.addPipelineCdStep()" style="padding:4px 12px;font-size:12px;">${t('system.pipeline.addCdStep')}</button>
                </label>
                <div id="pipelineCdStepsContainer" style="border:1px solid var(--border-strong);border-radius:4px;padding:12px;background:#f9f9f9;min-height:40px;"></div>
            </div>
        </div>
        <p style="margin:12px 0 0;font-size:13px;color:#6b7280;">${t('system.pipeline.phaseHint')}</p>
        <p style="margin:6px 0 0;font-size:12px;color:#64748b;">${t('system.pipeline.overrideHint')}</p>
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
        notify(t('system.pipeline.msg.noSteps'));
        return null;
    }
    const { hasCi, hasCd } = resolvePipelineFormPhases(steps);
    if (!hasCi && !hasCd) {
        notify(t('system.pipeline.msg.noSteps'));
        return null;
    }

    const pipelineType = (form.querySelector('[name="pipelineType"]')?.value || '').trim();
    if (!['ci', 'cd', 'cicd'].includes(pipelineType)) {
        notify(t('system.pipeline.msg.typeRequired'));
        return null;
    }
    if (pipelineType === 'ci' && !hasCi) {
        notify(t('system.pipeline.msg.ciNeedsCi'));
        return null;
    }
    if (pipelineType === 'cd' && !hasCd) {
        notify(t('system.pipeline.msg.cdNeedsCd'));
        return null;
    }
    if (pipelineType === 'cicd' && (!hasCi || !hasCd)) {
        notify(t('system.pipeline.msg.cicdNeedsBoth'));
        return null;
    }
    if (pipelineType === 'ci' && hasCd) {
        notify(t('system.pipeline.msg.ciHasCd'));
        return null;
    }
    if (pipelineType === 'cd' && hasCi) {
        notify(t('system.pipeline.msg.cdHasCi'));
        return null;
    }

    const buildNodeInput = form.querySelector('#pipelineBuildNodeIdsInput');
    const buildNodeIds = buildNodeInput && buildNodeInput.value
        ? buildNodeInput.value.split(',').map(v => parseInt(v, 10)).filter(v => !isNaN(v))
        : [];
    const deployNodeRaw = form.querySelector('[name="deployNodeId"]')?.value;
    const deployNodeId = deployNodeRaw ? parseInt(deployNodeRaw, 10) : null;

    if (hasCi && !buildNodeIds.length) {
        notify(t('system.pipeline.msg.buildNodeRequired'));
        return null;
    }
    if (hasCd && !deployNodeId) {
        notify(t('system.pipeline.msg.deployNodeRequired'));
        return null;
    }

    const nameInput = form.querySelector('[name="name"]');
    if (!nameInput || !nameInput.value.trim()) {
        notify(t('system.pipeline.msg.nameRequired'));
        return null;
    }
    return {
        name: nameInput.value.trim(),
        pipelineType: pipelineType,
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
            await window.addPipelineStepRef(stepPhase, step.stepTemplateId, {
                parameters: step.parameters || {},
                timeoutSeconds: step.timeoutSeconds != null ? step.timeoutSeconds : null
            });
        } else if (step.stepType) {
            const phase = PIPELINE_CI_STEP_TYPE_KEYS.includes(step.stepType) ? 'ci' : 'cd';
            const def = (window.pipelineStepDefCache || []).find(d => d.stepType === step.stepType && d.phase === phase);
            if (def) {
                await window.addPipelineStepRef(phase, def.id, {
                    parameters: step.parameters || {},
                    timeoutSeconds: step.timeoutSeconds != null ? step.timeoutSeconds : null
                });
            }
        }
    }
}

// 加载Pipeline列表
async function loadPipelines() {
    const pipelineList = document.getElementById('pipelineList');
    if (!pipelineList) {
        console.warn('Pipeline list container not found');
        return;
    }
    
    pipelineList.innerHTML = ('<tr><td colspan="5" style="text-align: center; padding: 40px; color: var(--text-4);">' + t('common.loading') + '</td></tr>');
    
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
                pipelineList.innerHTML = ('<tr><td colspan="5" style="text-align: center; padding: 40px; color: var(--text-4);">' + t('system.pipeline.empty') + '</td></tr>');
            }
        } else {
            const errorText = await response.text();
            pipelineList.innerHTML = ('<tr><td colspan="5" style="text-align: center; padding: 40px; color: #dc2626;">' + t('common.loadFailed') + ': ') + escapeHtml(errorText || t('common.unknownError')) + '</td></tr>';
        }
    } catch (error) {
        console.error('Load pipelines error:', error);
        pipelineList.innerHTML = ('<tr><td colspan="5" style="text-align: center; padding: 40px; color: #dc2626;">' + t('common.loadFailed') + ': ') + escapeHtml(error.message) + '</td></tr>';
    }
}
window.loadPipelines = loadPipelines;

function pipelineTypeLabel(type) {
    if (type === 'ci') return 'CI';
    if (type === 'cd') return 'CD';
    if (type === 'cicd') return 'CI/CD';
    return type || 'CI/CD';
}

function pipelineTypeBadgeHtml(type) {
    const typeKey = type || 'cicd';
    const colors = {
        ci: { bg: '#eff6ff', fg: '#1d4ed8', border: '#bfdbfe' },
        cd: { bg: '#f0fdf4', fg: '#15803d', border: '#bbf7d0' },
        cicd: { bg: '#f5f3ff', fg: '#6d28d9', border: '#ddd6fe' }
    };
    const c = colors[typeKey] || colors.cicd;
    return `<span style="display:inline-block;padding:2px 8px;border-radius:999px;font-size:12px;font-weight:600;background:${/* html-safe: 上面写死的色值 */ c.bg};color:${/* html-safe: 上面写死的色值 */ c.fg};border:1px solid ${/* html-safe: 上面写死的色值 */ c.border};">${pipelineTypeLabel(typeKey)}</span>`;
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
            const typeKey = PIPELINE_CI_STEP_TYPES[step.stepType] || PIPELINE_CD_STEP_TYPES[step.stepType];
            name = typeKey ? t(typeKey) : step.stepType;
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
        return ('<span class="pipeline-flow-empty">' + t('system.pipeline.noStepsShort') + '</span>');
    }
    return stepNames.map(stepName =>
        `<span class="${tagClass}">${escapeHtml(stepName)}</span>`
    ).join('<span class="pipeline-step-arrow">→</span>');
}

function formatPipelineFlowHtml(buildNodeName, deployNodeName, ciSteps, cdSteps) {
    return `
        <div class="pipeline-flow">
            <div class="pipeline-flow-tracks">
                <div class="pipeline-flow-track pipeline-flow-ci">
                    <div class="pipeline-flow-track-header">
                        <span class="pipeline-flow-badge pipeline-flow-badge-ci">CI</span>
                        <span class="pipeline-flow-node" title="${t('system.pipeline.flowBuildNode')}">${escapeHtml(buildNodeName || '-')}</span>
                    </div>
                    <div class="pipeline-flow-track-steps">${/* html-safe: 函数内已转义 */ formatPipelineStepsInline(ciSteps, 'ci')}</div>
                </div>
                <span class="pipeline-flow-divider" title="${t('system.pipeline.flowDivider')}">▸</span>
                <div class="pipeline-flow-track pipeline-flow-cd">
                    <div class="pipeline-flow-track-header">
                        <span class="pipeline-flow-badge pipeline-flow-badge-cd">CD</span>
                        <span class="pipeline-flow-node" title="${t('system.pipeline.flowDeployNode')}">${escapeHtml(deployNodeName || '-')}</span>
                    </div>
                    <div class="pipeline-flow-track-steps">${/* html-safe: 函数内已转义 */ formatPipelineStepsInline(cdSteps, 'cd')}</div>
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
        pipelineList.innerHTML = ('<tr><td colspan="5" style="text-align: center; padding: 40px; color: var(--text-4);">' + t('system.pipeline.empty') + '</td></tr>');
        return;
    }
    
    pipelineList.innerHTML = pipelines.map(pipeline => {
        const statusText = (pipeline.status === 1 || pipeline.status === null) 
            ? ('<span class="status-tag is-on">' + t('common.enabled') + '</span>') 
            : ('<span class="status-tag is-off">' + t('common.disabled') + '</span>');
        const { ciSteps, cdSteps } = resolvePipelinePhaseSteps(pipeline);
        
        return `
            <tr>
                <td>${escapeHtml(pipeline.name || '-')}</td>
                <td>${pipelineTypeBadgeHtml(pipeline.pipelineType)}</td>
                <td class="pipeline-flow-cell">${formatPipelineFlowHtml(pipeline.buildNodeName, pipeline.deployNodeName, ciSteps, cdSteps)}</td>
                <td>${statusText}</td>
                <td>
                    <div class="table-actions">
                        <button class="btn-edit" onclick="window.editPipeline(${pipeline.id})">${t('common.modify')}</button>
                        <button class="btn-secondary" onclick="window.clonePipeline(${pipeline.id})" title="${t('system.pipeline.cloneHint')}">${t('system.pipeline.clone')}</button>
                        <button class="btn-danger" onclick="window.deletePipeline(${pipeline.id})">${t('common.delete')}</button>
                    </div>
                </td>
            </tr>
        `;
    }).join('');
}
window.renderPipelineList = renderPipelineList;

// 显示添加Pipeline模态框
async function showAddPipelineModal() {
    if (typeof window.showModal !== 'function') {
        console.error('showModal function not available');
        notify(t('system.msg.modalNotLoaded'));
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
                <label>${t('system.pipeline.name')}${REQUIRED_MARK}</label>
                <input type="text" name="name" placeholder="${t('system.pipeline.namePlaceholder')}" required>
            </div>
            <div class="form-item">
                <label>${t('system.pipeline.type')}${REQUIRED_MARK}</label>
                <select name="pipelineType" required>
                    <option value="cicd" selected>${t('system.pipeline.typeCicd')}</option>
                    <option value="ci">${t('system.pipeline.typeCi')}</option>
                    <option value="cd">${t('system.pipeline.typeCd')}</option>
                </select>
                <small style="color:#6b7280;">${t('system.pipeline.typeHint')}</small>
            </div>
            <div class="form-item">
                <label>${t('common.status')}</label>
                <select name="status">
                    <option value="1" selected>${t('common.enabled')}</option>
                    <option value="0">${t('common.disabled')}</option>
                </select>
            </div>
            ${buildPipelineNodeSectionHtml()}
        </form>
    `;
    
    window.showModal(t('system.pipeline.createTitle'), content, async () => {
        const form = document.getElementById('createPipelineForm');
        if (!form) {
            notify(t('system.pipeline.msg.formMissing'));
            return;
        }
        
        const data = collectPipelineFormData(form);
        if (!data) return;
        if (typeof window.passTemplateVarChecks === 'function'
                && !await window.passTemplateVarChecks(form)) {
            return;
        }
        
        try {
            const response = await fetch('/api/pipeline/create', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(data)
            });
            
            if (response.ok) {
                notify(t('common.createOk'));
                window.loadPipelines();
                window.closeModal();
            } else {
                const errorMessage = await (typeof window.handleApiError === 'function' ? window.handleApiError(response) : response.text());
                notify((t('common.createFailed') + ': ') + errorMessage);
            }
        } catch (error) {
            console.error('Create pipeline error:', error);
            notify((t('common.createFailed') + ': ') + (error.message || t('common.networkError')));
        }
    });
    
    setTimeout(async () => {
        await initDefaultPipelineSteps();
    }, 100);
}
window.showAddPipelineModal = showAddPipelineModal;

// 编辑Pipeline
async function editPipeline(id) {
    try {
        const response = await fetch(`/api/pipeline/${id}`);
        if (response.ok) {
            const pipeline = await response.json();
            showEditPipelineModal(pipeline);
        } else {
            notify(t('common.loadFailed'));
        }
    } catch (error) {
        notify((t('common.loadFailed') + ': ') + error.message);
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
            notify(t('system.pipeline.msg.sourceLoadFailed'));
            return;
        }
        const source = await response.json();
        const defaultName = (source.name || t('system.pipeline.unnamed')) + t('system.pipeline.copySuffix');
        const newName = await promptDialog(t('system.pipeline.cloneNameLabel'), defaultName, { title: t('system.pipeline.cloneTitle'), required: true });
        if (newName === null) {
            return;
        }
        if (!String(newName).trim()) {
            notify(t('system.pipeline.msg.templateNameRequired'));
            return;
        }

        const cloneResp = await fetch(`/api/pipeline/${id}/clone`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ name: String(newName).trim() })
        });
        if (cloneResp.ok) {
            notify(t('system.pipeline.msg.cloneOk'));
            if (typeof window.loadPipelines === 'function') {
                window.loadPipelines();
            }
        } else {
            const errorMessage = await (typeof window.handleApiError === 'function'
                ? window.handleApiError(cloneResp)
                : cloneResp.text());
            notify((t('system.pipeline.msg.cloneFailed') + ': ') + errorMessage);
        }
    } catch (error) {
        notify((t('system.pipeline.msg.cloneFailed') + ': ') + (error.message || t('common.networkError')));
    }
}
window.clonePipeline = clonePipeline;

// 显示编辑Pipeline模态框
async function showEditPipelineModal(pipeline) {
    if (typeof window.showModal !== 'function') {
        notify(t('system.msg.modalNotLoaded'));
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
                <label>${t('system.pipeline.name')}${REQUIRED_MARK}</label>
                <input type="text" name="name" value="${escapeHtml(pipeline.name || '')}" required>
            </div>
            <div class="form-item">
                <label>${t('system.pipeline.type')}${REQUIRED_MARK}</label>
                <select name="pipelineType" required>
                    <option value="cicd" ${currentType === 'cicd' ? 'selected' : ''}>${t('system.pipeline.typeCicd')}</option>
                    <option value="ci" ${currentType === 'ci' ? 'selected' : ''}>${t('system.pipeline.typeCi')}</option>
                    <option value="cd" ${currentType === 'cd' ? 'selected' : ''}>${t('system.pipeline.typeCd')}</option>
                </select>
                <small style="color:#6b7280;">${t('system.pipeline.typeHint')}</small>
            </div>
            <div class="form-item">
                <label>${t('common.status')}</label>
                <select name="status">
                    <option value="1" ${pipeline.status !== 0 ? 'selected' : ''}>${t('common.enabled')}</option>
                    <option value="0" ${pipeline.status === 0 ? 'selected' : ''}>${t('common.disabled')}</option>
                </select>
            </div>
            ${buildPipelineNodeSectionHtml(pipeline)}
        </form>
    `;
    
    window.showModal(t('system.pipeline.editTitle'), content, async () => {
        const form = document.getElementById('editPipelineForm');
        if (!form) {
            notify(t('system.pipeline.msg.formMissing'));
            return;
        }
        
        const data = collectPipelineFormData(form);
        if (!data) return;
        if (typeof window.passTemplateVarChecks === 'function'
                && !await window.passTemplateVarChecks(form)) {
            return;
        }
        
        try {
            const response = await fetch(`/api/pipeline/${pipeline.id}`, {
                method: 'PUT',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(data)
            });
            
            if (response.ok) {
                notify(t('common.updatedOk'));
                window.loadPipelines();
                window.closeModal();
            } else {
                const errorMessage = await (typeof window.handleApiError === 'function' ? window.handleApiError(response) : response.text());
                notify((t('common.updatedFailed') + ': ') + errorMessage);
            }
        } catch (error) {
            notify((t('common.updatedFailed') + ': ') + error.message);
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

// 关联 Pipeline 步骤（引用步骤定义；可选差异覆盖）
async function addPipelineStepRef(phase, selectedTemplateId, preservedOverrides) {
    phase = phase || 'ci';
    const containerId = phase === 'cd' ? 'pipelineCdStepsContainer' : 'pipelineCiStepsContainer';
    const container = document.getElementById(containerId);
    if (!container) return;

    const stepId = 'step_' + (pipelineStepCounter++);
    const phaseLabel = phase === 'cd' ? 'CD' : 'CI';
    const optionsHtml = typeof window.renderStepDefSelectOptions === 'function'
        ? window.renderStepDefSelectOptions(phase, selectedTemplateId)
        : ('<option value="">' + t('stepdef.select') + '</option>');

    const stepHtml = `
        <div class="pipeline-step-item" data-step-id="${escapeHtml(stepId)}" data-step-phase="${escapeHtml(phase)}" style="border:1px solid #ccc;border-radius:4px;padding:12px;margin-bottom:12px;background:white;">
            <div style="display:flex;justify-content:space-between;align-items:center;margin-bottom:8px;">
                <strong>${t('system.pipeline.stepTitle', { phase: phaseLabel })}</strong>
                <div style="display:flex;gap:4px;align-items:center;">
                    <button type="button" class="btn-secondary" data-step-move="up" title="${t('system.pipeline.moveUp')}"
                        onclick="window.movePipelineStep('${escapeHtml(stepId)}', -1)" style="padding:2px 8px;font-size:12px;">↑</button>
                    <button type="button" class="btn-secondary" data-step-move="down" title="${t('system.pipeline.moveDown')}"
                        onclick="window.movePipelineStep('${escapeHtml(stepId)}', 1)" style="padding:2px 8px;font-size:12px;">↓</button>
                    <button type="button" class="btn-danger" onclick="window.removePipelineStep('${escapeHtml(stepId)}')" style="padding:2px 8px;font-size:12px;">${t('common.delete')}</button>
                </div>
            </div>
            <div class="form-item" style="margin:0;">
                <label style="font-size:12px;display:block;margin-bottom:4px;">${t('system.pipeline.selectStepDef')}${REQUIRED_MARK}</label>
                <select name="stepTemplateId_${escapeHtml(stepId)}" required style="width:100%;"
                        onchange="window.onPipelineStepDefChange('${escapeHtml(stepId)}')">
                    ${optionsHtml}
                </select>
            </div>
            <div class="pipeline-step-override-host"></div>
            <input type="hidden" name="stepOrder_${escapeHtml(stepId)}" value="1">
        </div>
    `;
    container.insertAdjacentHTML('beforeend', stepHtml);
    window.updateStepNumbers();
    if (typeof window.refreshPipelineStepOverridePanel === 'function') {
        await window.refreshPipelineStepOverridePanel(stepId, preservedOverrides || null);
    }
}
window.addPipelineStepRef = addPipelineStepRef;
window.addPipelineStep = addPipelineStepRef;

async function onPipelineStepDefChange(stepId) {
    window.updateStepNumbers();
    if (typeof window.refreshPipelineStepOverridePanel === 'function') {
        // 切换步骤定义时清空旧覆盖，避免串类型配置
        await window.refreshPipelineStepOverridePanel(stepId, null);
    }
}
window.onPipelineStepDefChange = onPipelineStepDefChange;

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
                title.textContent = t('system.pipeline.stepTitle', { phase: phaseLabel }) + ' ' + (index + 1);
            }

            const upBtn = step.querySelector('button[data-step-move="up"]');
            const downBtn = step.querySelector('button[data-step-move="down"]');
            if (upBtn) upBtn.disabled = index === 0;
            if (downBtn) downBtn.disabled = index === items.length - 1;
        });
    }

    updateContainer(ciContainer, 'CI');
    updateContainer(cdContainer, 'CD');
}
window.updateStepNumbers = updateStepNumbers;

// 收集Pipeline步骤（步骤定义引用 + 可选差异覆盖）
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
                const step = {
                    stepTemplateId: parseInt(templateSelect.value, 10),
                    order: parseInt(orderInput?.value, 10) || 1,
                    enabled: true
                };
                if (typeof window.collectPipelineStepOverrides === 'function') {
                    const ov = window.collectPipelineStepOverrides(item, stepId);
                    if (ov.parameters && Object.keys(ov.parameters).length > 0) {
                        step.parameters = ov.parameters;
                    }
                    if (ov.timeoutSeconds != null) {
                        step.timeoutSeconds = ov.timeoutSeconds;
                    }
                }
                steps.push(step);
            }
        });
    });

    const sorted = steps.sort((a, b) => a.order - b.order);
    for (let i = 0; i < sorted.length; i++) {
        if (!sorted[i].stepTemplateId) {
            notify(t('system.pipeline.msg.stepDefRequired'));
            return [];
        }
    }
    return sorted;
}
window.collectPipelineSteps = collectPipelineSteps;

// 删除Pipeline
async function deletePipeline(id) {
    if (!await confirmDialog(t('system.pipeline.deleteConfirm'))) return;
    
    try {
        const response = await fetch(`/api/pipeline/${id}`, { method: 'DELETE' });
        if (response.ok) {
            notify(t('common.deleteOk'));
            window.loadPipelines();
        } else {
            notify(t('common.deleteFailed'));
        }
    } catch (error) {
        notify((t('common.deleteFailed') + ': ') + error.message);
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
