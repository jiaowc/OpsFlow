// ============================================
// 核心模块 - 全局变量、认证、菜单切换、工具函数
// ============================================

// 全局变量
let currentUser = null;
let mockTasks = [];
let mockEnvs = [];
let mockNodes = [];
let mockServices = [];
let mockApprovals = [];
let pipelineStepCounter = 0;
let pipelineParamCounter = 0;

// 初始化模拟数据
function initMockData() {
    // 模拟环境数据
    mockEnvs = [
        { id: 1, name: 'dev', k8sCluster: 'cluster-dev', k8sNamespace: 'dev', envType: 'nonprod', status: 1 },
        { id: 2, name: 'uat', k8sCluster: 'cluster-uat', k8sNamespace: 'uat', envType: 'nonprod', status: 1 },
        { id: 3, name: 'pre', k8sCluster: 'cluster-pre', k8sNamespace: 'pre', envType: 'nonprod', status: 1 },
        { id: 4, name: 'prod', k8sCluster: 'cluster-prod', k8sNamespace: 'prod', envType: 'prod', status: 1 }
    ];
    
    // 模拟服务数据
    mockServices = [
        { id: 1, name: '用户服务', code: 'user-service', gitRepo: 'git@github.com:example/user-service.git', defaultBranch: 'develop', k8sNamespace: 'default', status: 1 },
        { id: 2, name: '订单服务', code: 'order-service', gitRepo: 'git@github.com:example/order-service.git', defaultBranch: 'develop', k8sNamespace: 'default', status: 1 },
        { id: 3, name: '支付服务', code: 'payment-service', gitRepo: 'git@github.com:example/payment-service.git', defaultBranch: 'develop', k8sNamespace: 'default', status: 1 }
    ];
    
    // 模拟节点数据
    mockNodes = [
        { id: 1, name: 'build-01', nodeType: 'build', label: 'build', status: '在线' },
        { id: 2, name: 'build-02', nodeType: 'build', label: 'build', status: '在线' },
        { id: 3, name: 'deploy-01', nodeType: 'deploy', label: 'deploy', status: '在线' },
        { id: 4, name: 'deploy-02', nodeType: 'deploy', label: 'deploy', status: '在线' }
    ];
    
    // 模拟审批流数据
    mockApprovals = [
        { id: 1, name: '生产环境审批流', description: '生产环境上线需要三级审批', steps: [
            { step: 1, approver: 'tech-lead', approverName: '技术负责人', required: true, status: 'pending' },
            { step: 2, approver: 'qa-lead', approverName: 'QA负责人', required: true, status: 'pending' },
            { step: 3, approver: 'pm', approverName: '产品经理', required: true, status: 'pending' }
        ], status: 1 }
    ];
}

// 检查登录状态
async function checkAuth() {
    try {
        const response = await fetch('/api/auth/user');
        if (!response.ok) {
            window.location.href = '/index.html';
            return;
        }
        const user = await response.json();
        if (!user.authenticated) {
            window.location.href = '/index.html';
            return;
        }
        await loadUserInfo();
    } catch (error) {
        window.location.href = '/index.html';
    }
}

window.checkAuth = checkAuth;

// 是否拥有权限（* 或任一码）
function hasPermission(...codes) {
    if (!currentUser || !currentUser.authenticated) {
        return false;
    }
    const perms = currentUser.permissions || [];
    if (perms.indexOf('*') >= 0) {
        return true;
    }
    if (!codes || codes.length === 0) {
        return true;
    }
    for (let i = 0; i < codes.length; i++) {
        const code = codes[i];
        if (!code) continue;
        if (String(code).indexOf(',') >= 0) {
            const parts = String(code).split(',').map(s => s.trim()).filter(Boolean);
            if (parts.some(p => perms.indexOf(p) >= 0)) {
                return true;
            }
        } else if (perms.indexOf(code) >= 0) {
            return true;
        }
    }
    return false;
}

window.hasPermission = hasPermission;

/** License 功能状态（由 /api/license/status 填充） */
let licenseStatus = {
    valid: false,
    present: false,
    features: [],
    deployApprovalEnabled: false,
    // false = 后端已关闭强校验，前端按功能全开处理
    enforcementEnabled: false,
    message: ''
};

function hasFeature(...codes) {
    // License 强校验关闭时：全部功能可见可用
    if (licenseStatus && licenseStatus.enforcementEnabled === false) {
        return true;
    }
    const features = (licenseStatus && licenseStatus.features) || [];
    if (!codes || codes.length === 0) {
        return true;
    }
    for (let i = 0; i < codes.length; i++) {
        const code = codes[i];
        if (!code) continue;
        if (String(code).indexOf(',') >= 0) {
            const parts = String(code).split(',').map(s => s.trim()).filter(Boolean);
            if (parts.some(p => features.indexOf(p) >= 0)) {
                return true;
            }
        } else if (features.indexOf(code) >= 0) {
            return true;
        }
    }
    // 兼容布尔字段
    if (codes.indexOf('deploy_approval') >= 0 && licenseStatus && licenseStatus.deployApprovalEnabled) {
        return true;
    }
    return false;
}

window.hasFeature = hasFeature;
window.getLicenseStatus = function () { return licenseStatus; };

function applyFeatureUI() {
    document.querySelectorAll('[data-feature]').forEach(el => {
        const required = el.getAttribute('data-feature');
        if (!hasFeature(required)) {
            el.style.display = 'none';
            return;
        }
        const perm = el.getAttribute('data-permission');
        if (perm) {
            el.style.display = hasPermission(perm) ? '' : 'none';
        } else {
            el.style.display = '';
        }
    });
    // 无上线审批 License 时，待审批 KPI 一并隐藏（即使无 data-feature 也能兜底）
    const pendingKpi = document.getElementById('kpiPendingApprovals');
    if (pendingKpi && !hasFeature('deploy_approval')) {
        pendingKpi.style.display = 'none';
    }
    // License 强校验关闭时，隐藏 License 配置菜单（后期开启 enforcement 后自动恢复）
    const licenseMenu = document.querySelector('[data-section="license-config"]');
    if (licenseMenu) {
        if (licenseStatus && licenseStatus.enforcementEnabled === false) {
            licenseMenu.style.display = 'none';
        } else {
            const perm = licenseMenu.getAttribute('data-permission');
            licenseMenu.style.display = (!perm || hasPermission(perm)) ? '' : 'none';
        }
    }
}

window.applyFeatureUI = applyFeatureUI;

async function loadLicenseStatus() {
    try {
        const response = await fetch('/api/license/status');
        if (response.ok) {
            licenseStatus = await response.json() || licenseStatus;
            if (!Array.isArray(licenseStatus.features)) {
                licenseStatus.features = [];
            }
            if (licenseStatus.deployApprovalEnabled && licenseStatus.features.indexOf('deploy_approval') < 0) {
                licenseStatus.features.push('deploy_approval');
            }
        }
    } catch (e) {
        console.warn('Load license status failed', e);
    }
    applyFeatureUI();
    return licenseStatus;
}

window.loadLicenseStatus = loadLicenseStatus;

function applyPermissionUI() {
    document.querySelectorAll('[data-permission]').forEach(el => {
        const required = el.getAttribute('data-permission');
        const allowed = hasPermission(required);
        el.style.display = allowed ? '' : 'none';
        if (el.classList && el.classList.contains('menu-item')) {
            // keep layout consistent
        }
    });
    const configGroup = document.querySelector('.menu-group');
    if (configGroup) {
        const visibleSubs = configGroup.querySelectorAll('.menu-item-sub');
        let anyVisible = false;
        visibleSubs.forEach(item => {
            if (item.style.display !== 'none') {
                anyVisible = true;
            }
        });
        configGroup.style.display = anyVisible ? '' : 'none';
    }
    applyFeatureUI();
}

window.applyPermissionUI = applyPermissionUI;

// 加载用户信息
async function loadUserInfo() {
    try {
        const response = await fetch('/api/auth/user');
        if (response.ok) {
            const user = await response.json();
            currentUser = user;
            document.getElementById('username').textContent = user.username || '用户';
            await loadLicenseStatus();
            applyPermissionUI();
        }
    } catch (error) {
        console.error('Load user info error:', error);
    }
}

// 退出登录
async function logout() {
    try {
        await fetch('/api/auth/logout', { method: 'POST' });
    } catch (error) {
        console.error('Logout error:', error);
    }
    localStorage.removeItem('token');
    window.location.href = '/index.html';
}

// 模块路径映射
const SECTION_TITLES = {
    statistics: '工作台',
    pipeline: '流水线视图',
    tasks: '上线任务',
    services: '服务管理',
    environments: '环境配置',
    clusters: '集群管理',
    approval: '审批流程',
    nodes: '节点管理',
    users: '用户管理',
    'pipeline-config': '流水线配置',
    'credentials-config': '凭据与组件',
    'integration-config': '集成与通知',
    'license-config': 'License'
};

const SECTION_ROUTES = {
    statistics: '/statistics',
    pipeline: '/pipeline',
    tasks: '/tasks',
    services: '/services',
    environments: '/environments',
    clusters: '/clusters',
    approval: '/approval',
    nodes: '/nodes',
    users: '/users',
    'pipeline-config': '/pipeline-config',
    'credentials-config': '/credentials',
    'integration-config': '/integrations',
    'license-config': '/license'
};

const ROUTE_SECTIONS = Object.fromEntries(
    Object.entries(SECTION_ROUTES).map(([section, path]) => [path, section])
);

let suppressHistoryUpdate = false;

function getPathForSection(sectionId) {
    return SECTION_ROUTES[sectionId] || '/statistics';
}

function getSectionFromPath() {
    const path = window.location.pathname.replace(/\/$/, '') || '/';
    return ROUTE_SECTIONS[path] || 'statistics';
}

function initSectionFromUrl() {
    const sectionId = getSectionFromPath();
    const menuItem = document.querySelector(`.menu-item[data-section="${sectionId}"]`);
    suppressHistoryUpdate = true;
    showSection(sectionId, menuItem, { updateHistory: false });
    suppressHistoryUpdate = false;

    const expectedPath = getPathForSection(sectionId);
    if (window.location.pathname !== expectedPath) {
        history.replaceState({ section: sectionId }, '', expectedPath);
    }
    document.documentElement.removeAttribute('data-initial-section');
}

function bindMenuNavigation() {
    document.querySelectorAll('.menu-item[data-section]').forEach(item => {
        item.addEventListener('click', function(e) {
            if (e.metaKey || e.ctrlKey || e.shiftKey || e.button === 1) {
                return;
            }
            e.preventDefault();
            showSection(this.dataset.section, this);
        });
    });

    document.addEventListener('click', function(e) {
        const link = e.target.closest('a[href]');
        if (!link || link.classList.contains('menu-item')) {
            return;
        }
        const path = link.getAttribute('href');
        const sectionId = ROUTE_SECTIONS[path];
        if (!sectionId) {
            return;
        }
        if (e.metaKey || e.ctrlKey || e.shiftKey || e.button === 1) {
            return;
        }
        e.preventDefault();
        const menuItem = document.querySelector(`.menu-item[data-section="${sectionId}"]`);
        showSection(sectionId, menuItem);
    });

    window.addEventListener('popstate', function(e) {
        const sectionId = (e.state && e.state.section) || getSectionFromPath();
        const menuItem = document.querySelector(`.menu-item[data-section="${sectionId}"]`);
        suppressHistoryUpdate = true;
        showSection(sectionId, menuItem, { updateHistory: false });
        suppressHistoryUpdate = false;
    });
}

// 菜单切换
function showSection(sectionId, element, options) {
    const opts = options || {};
    const updateHistory = opts.updateHistory !== false;

    // License 未开通时，拦截上线任务 / 审批流程深链
    if ((sectionId === 'tasks' || sectionId === 'approval') && !hasFeature('deploy_approval')) {
        sectionId = 'statistics';
        element = document.querySelector('.menu-item[data-section="statistics"]');
    }

    // 隐藏所有内容区域
    document.querySelectorAll('.content-section').forEach(section => {
        section.classList.remove('active');
    });
    
    // 加载对应区域的数据
    if (sectionId === 'pipeline' && typeof loadPipelineJobsView === 'function') {
        loadPipelineJobsView();
    }
    
    // 显示选中的内容区域
    const targetSection = document.getElementById(sectionId + '-section');
    if (targetSection) {
        targetSection.classList.add('active');
    }
    
    // 更新菜单项状态
    document.querySelectorAll('.menu-item').forEach(item => {
        item.classList.remove('active');
    });
    if (element) {
        element.classList.add('active');
    } else {
        const menuItem = document.querySelector(`.menu-item[data-section="${sectionId}"]`);
        if (menuItem) {
            menuItem.classList.add('active');
        }
    }

    if (updateHistory && !suppressHistoryUpdate) {
        const path = getPathForSection(sectionId);
        const state = { section: sectionId };
        if (window.location.pathname !== path) {
            history.pushState(state, '', path);
        } else {
            history.replaceState(state, '', path);
        }
        document.title = (SECTION_TITLES[sectionId] || '控制台') + ' - OPSFLOW';
    }
    
    // 根据选中的区域加载数据
    switch(sectionId) {
        case 'pipeline':
            if (typeof loadPipelineJobsView === 'function') loadPipelineJobsView();
            break;
        case 'tasks':
            if (typeof loadTasks === 'function') loadTasks();
            break;
        case 'services':
            if (typeof loadServices === 'function') loadServices();
            break;
        case 'environments':
            if (typeof loadEnvironments === 'function') loadEnvironments();
            break;
        case 'clusters':
            if (typeof loadClusters === 'function') loadClusters();
            break;
        case 'approval':
            if (typeof loadApprovalFlows === 'function') loadApprovalFlows();
            break;
        case 'nodes':
            if (typeof loadNodes === 'function') loadNodes();
            break;
        case 'users':
            if (typeof showUserTab === 'function') {
                const firstTabBtn = document.querySelector('#users-section .tab-btn');
                if (firstTabBtn) {
                    showUserTab('user-list', firstTabBtn);
                } else {
                    if (typeof loadUsers === 'function') loadUsers();
                }
            }
            break;
        case 'pipeline-config':
            activateConfigSectionDefault('pipeline-config', 'step-def');
            break;
        case 'credentials-config':
            activateConfigSectionDefault('credentials-config', 'keychain');
            break;
        case 'integration-config':
            activateConfigSectionDefault('integration-config', 'notify');
            break;
        case 'license-config':
            if (typeof loadLicensePage === 'function') loadLicensePage();
            break;
        case 'statistics':
            if (typeof loadStatistics === 'function') loadStatistics();
            break;
    }
}

function activateConfigSectionDefault(sectionId, defaultTab) {
    const section = document.getElementById(sectionId + '-section');
    if (!section) return;
    const activeBtn = section.querySelector('.tab-buttons > .tab-btn.active')
        || section.querySelector(`.tab-buttons > .tab-btn[data-tab="${defaultTab}"]`)
        || section.querySelector('.tab-buttons > .tab-btn');
    if (activeBtn && typeof showConfigTab === 'function') {
        showConfigTab(activeBtn.getAttribute('data-tab'), activeBtn);
    }
}
window.activateConfigSectionDefault = activateConfigSectionDefault;
window.showSection = showSection;

// 模态框工具函数
function showModal(title, content, onConfirm) {
    const modalContainer = document.getElementById('modalContainer');
    modalContainer.innerHTML = `
        <div class="modal-overlay" onclick="closeModal(event)">
            <div class="modal" onclick="event.stopPropagation()">
                <div class="modal-header">
                    <h3>${title}</h3>
                    <button class="modal-close" onclick="closeModal()">&times;</button>
                </div>
                <div class="modal-body">
                    ${content}
                </div>
                <div class="modal-footer">
                    <button class="btn-secondary" onclick="closeModal()">取消</button>
                    ${onConfirm ? `<button class="btn-primary" onclick="confirmModal()">确认</button>` : ''}
                </div>
            </div>
        </div>
    `;
    
    window.currentModalConfirm = onConfirm;
}

function closeModal(event) {
    if (event && event.target !== event.currentTarget) return;
    if (typeof window.stopPipelineStageLogPolling === 'function') {
        window.stopPipelineStageLogPolling();
    }
    window.__pipelineStageLogContext = null;
    document.getElementById('modalContainer').innerHTML = '';
    window.currentModalConfirm = null;
}
window.closeModal = closeModal;

async function confirmModal() {
    const fn = window.currentModalConfirm;
    if (!fn) {
        closeModal();
        return;
    }
    try {
        const result = fn();
        // 异步回调自行决定何时关闭（成功 closeModal）；失败时保留弹窗便于改选
        if (result && typeof result.then === 'function') {
            await result;
            return;
        }
        closeModal();
    } catch (error) {
        console.error('Modal confirm error:', error);
        alert((error && error.message) || '操作失败');
    }
}
window.confirmModal = confirmModal;

// 确保showModal也在全局作用域中可用
window.showModal = showModal;

// ============================================
// Dashboard初始化 - 页面加载时执行
// ============================================

// 页面加载时初始化
document.addEventListener('DOMContentLoaded', async function() {
    await checkAuth();
    initMockData();
    loadEnvs();
    loadServicesForSelect();
    loadNodesForSelect();
    bindMenuNavigation();
    initSectionFromUrl();
    
    // 绑定Pipeline相关按钮事件（等待模块加载完成）
    function bindPipelineButton() {
        const addPipelineBtn = document.getElementById('addPipelineBtn');
        console.log('Binding pipeline button, button found:', !!addPipelineBtn);
        console.log('showAddPipelineModal available:', typeof window.showAddPipelineModal);
        console.log('showModal available:', typeof window.showModal);
        
        if (addPipelineBtn && typeof window.showAddPipelineModal === 'function') {
            // 移除可能存在的旧事件监听器
            const newBtn = addPipelineBtn.cloneNode(true);
            addPipelineBtn.parentNode.replaceChild(newBtn, addPipelineBtn);
            
            // 绑定新的事件监听器
            newBtn.addEventListener('click', function(e) {
                e.preventDefault();
                e.stopPropagation();
                console.log('Add Pipeline button clicked');
                
                if (typeof window.showAddPipelineModal === 'function') {
                    console.log('Calling showAddPipelineModal...');
                    try {
                        window.showAddPipelineModal();
                        console.log('showAddPipelineModal called successfully');
                    } catch (error) {
                        console.error('Error calling showAddPipelineModal:', error);
                        alert('打开Pipeline模态框时出错: ' + error.message);
                    }
                } else {
                    console.error('showAddPipelineModal not available');
                    alert('Pipeline功能未加载，请刷新页面');
                }
            });
            console.log('Pipeline button bound successfully');
            return true;
        } else {
            console.warn('Pipeline button binding failed:', {
                buttonExists: !!addPipelineBtn,
                functionExists: typeof window.showAddPipelineModal === 'function'
            });
        }
        return false;
    }
    
    // 绑定系统管理相关按钮的函数（使用事件委托）
    function bindSystemButtons() {
        if (window.__opsflowSystemButtonsBound) {
            return true;
        }
        console.log('bindSystemButtons called');
        console.log('showConfigTab available:', typeof window.showConfigTab);
        
        const configRoot = document.querySelector('.main-content');
        console.log('config root found:', !!configRoot);
        
        if (!configRoot) {
            console.error('main-content not found');
            return false;
        }
        window.__opsflowSystemButtonsBound = true;
        
        // 使用事件委托绑定配置区标签页与按钮
        configRoot.addEventListener('click', function(e) {
            // 仅处理三个配置页内的点击
            const inConfig = e.target.closest('#pipeline-config-section, #credentials-config-section, #integration-config-section');
            if (!inConfig) {
                return;
            }
            // 检查是否点击了标签页按钮
            const tabBtn = e.target.closest('.tab-btn[data-tab]');
            if (tabBtn) {
                e.preventDefault();
                e.stopPropagation();
                const tabName = tabBtn.getAttribute('data-tab');
                console.log('Tab button clicked:', tabName);
                console.log('showConfigTab type:', typeof window.showConfigTab);
                
                if (typeof window.showConfigTab === 'function') {
                    try {
                        window.showConfigTab(tabName, tabBtn);
                    } catch (error) {
                        console.error('Error calling showConfigTab:', error);
                        alert('调用系统功能时出错: ' + error.message);
                    }
                } else {
                    console.error('showConfigTab function not available');
                    console.log('Available functions:', Object.keys(window).filter(k => k.includes('show') || k.includes('Config')));
                    alert('系统功能未加载完成，请刷新页面');
                }
                return;
            }
            
            // 检查是否点击了添加步骤按钮
            const addStepDefBtn = e.target.closest('#addStepDefBtn');
            if (addStepDefBtn) {
                e.preventDefault();
                e.stopPropagation();
                if (typeof window.showCreateStepDefModal === 'function') {
                    window.showCreateStepDefModal();
                } else {
                    alert('步骤管理功能未加载，请刷新页面');
                }
                return;
            }

            // 检查是否点击了添加视图按钮
            const addPipelineViewBtn = e.target.closest('#addPipelineViewBtn');
            if (addPipelineViewBtn) {
                e.preventDefault();
                e.stopPropagation();
                if (typeof window.showCreatePipelineViewModal === 'function') {
                    window.showCreatePipelineViewModal();
                } else {
                    alert('视图管理功能未加载，请刷新页面');
                }
                return;
            }

            // 检查是否点击了添加组件按钮
            const addComponentBtn = e.target.closest('#addComponentBtn');
            if (addComponentBtn) {
                e.preventDefault();
                e.stopPropagation();
                console.log('Add component button clicked');
                
                if (typeof window.showAddComponentModal === 'function') {
                    try {
                        window.showAddComponentModal();
                    } catch (error) {
                        console.error('Error calling showAddComponentModal:', error);
                        alert('打开组件添加对话框时出错: ' + error.message);
                    }
                } else {
                    console.error('showAddComponentModal function not available');
                    alert('组件管理功能未加载完成，请刷新页面');
                }
                return;
            }
            
            // 检查是否点击了模版管理子标签
            const templateTabBtn = e.target.closest('.template-tab-btn[data-template-type]');
            if (templateTabBtn) {
                e.preventDefault();
                e.stopPropagation();
                const templateType = templateTabBtn.getAttribute('data-template-type');
                if (typeof window.showTemplateTab === 'function') {
                    window.showTemplateTab(templateType, templateTabBtn);
                }
                return;
            }

            // 模版「添加」按钮：不在此处处理，避免 stopPropagation 抢事件；由 template.js / 按钮 onclick 负责
            if (e.target.closest('#addDockerfileTemplateBtn')
                || e.target.closest('#addDeploymentTemplateBtn')
                || e.target.closest('#addServiceTemplateBtn')) {
                return;
            }

            // 检查是否点击了SSO标签页按钮
            const ssoTabBtn = e.target.closest('.sso-tab-btn[data-sso-type]');
            if (ssoTabBtn) {
                e.preventDefault();
                e.stopPropagation();
                const ssoType = ssoTabBtn.getAttribute('data-sso-type');
                console.log('SSO tab button clicked:', ssoType);

                if (typeof window.showSSOTab === 'function') {
                    try {
                        window.showSSOTab(ssoType, ssoTabBtn);
                    } catch (error) {
                        console.error('Error calling showSSOTab:', error);
                        alert('切换SSO标签页时出错: ' + error.message);
                    }
                } else {
                    console.error('showSSOTab function not available');
                    alert('SSO功能未加载完成，请刷新页面');
                }
                return;
            }
        });

        console.log('System buttons bound using event delegation');
        return true;
    }

    // 监听模块加载完成事件
    window.addEventListener('systemModuleLoaded', function() {
        console.log('System module loaded event received');
        bindPipelineButton();
        bindSystemButtons();
    });
    
    // 立即检查函数是否已可用（如果system.js已经加载完成）
    if (typeof window.showConfigTab === 'function') {
        console.log('System functions already available, binding immediately');
        bindSystemButtons();
    } else {
        console.log('System functions not yet available, will wait for event or retry');
    }
    
    // 延迟绑定（作为备用方案）
    setTimeout(function() {
        if (!bindPipelineButton()) {
            // 如果第一次绑定失败，继续重试
            let retries = 0;
            const retryInterval = setInterval(function() {
                if (bindPipelineButton() || retries >= 10) {
                    clearInterval(retryInterval);
                    if (retries >= 10) {
                        console.warn('Failed to bind pipeline button after 10 retries');
                    }
                }
                retries++;
            }, 200);
        }
    }, 500);
    
    // 延迟加载数据，等待认证完成
    setTimeout(() => {
        if (typeof loadTasks === 'function') loadTasks();
        if (typeof loadStatistics === 'function') loadStatistics();
        if (typeof loadSystemConfigs === 'function') loadSystemConfigs();
        if (typeof loadBuildJobs === 'function') loadBuildJobs();
        
        // 检查当前激活的标签页，如果是流水线管理，则加载 pipeline 数据
        const activePipelineMgmtPanel = document.getElementById('pipeline-mgmt-config');
        if (activePipelineMgmtPanel && activePipelineMgmtPanel.classList.contains('active')) {
            if (typeof loadPipelines === 'function') {
                loadPipelines();
            }
        }
        
    }, 100);
    
    // 立即尝试绑定系统管理按钮（不等待函数加载，使用事件委托）
    setTimeout(function() {
        console.log('Attempting to bind system buttons immediately');
        bindSystemButtons();
    }, 200);
    
    // 延迟绑定系统管理按钮（作为备用方案，如果systemModuleLoaded事件没有触发）
    setTimeout(function() {
        console.log('Delayed binding check, showConfigTab available:', typeof window.showConfigTab);
        // 即使函数还没加载，也先绑定事件委托（事件委托会在点击时检查函数）
        bindSystemButtons();
        
        if (typeof window.showConfigTab !== 'function') {
            console.log('Functions not available, starting retry mechanism');
            // 如果函数还没加载，继续重试
            let retries = 0;
            const retryInterval = setInterval(function() {
                console.log(`Retry ${retries}: showConfigTab available:`, typeof window.showConfigTab);
                if (typeof window.showConfigTab === 'function' || retries >= 20) {
                    clearInterval(retryInterval);
                    if (typeof window.showConfigTab === 'function') {
                        console.log('Functions available after retry');
                    } else {
                        console.warn('Failed to load system functions after 20 retries');
                        console.log('Available window properties:', Object.keys(window).filter(k => k.toLowerCase().includes('show') || k.toLowerCase().includes('config')));
                    }
                }
                retries++;
            }, 200);
        }
    }, 500);
});
