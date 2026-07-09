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
        { id: 1, name: 'dev', k8sCluster: 'cluster-dev', k8sNamespace: 'dev', status: 1 },
        { id: 2, name: 'uat', k8sCluster: 'cluster-uat', k8sNamespace: 'uat', status: 1 },
        { id: 3, name: 'pre', k8sCluster: 'cluster-pre', k8sNamespace: 'pre', status: 1 },
        { id: 4, name: 'prod', k8sCluster: 'cluster-prod', k8sNamespace: 'prod', status: 1 }
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
        loadUserInfo();
    } catch (error) {
        window.location.href = '/index.html';
    }
}

// 加载用户信息
async function loadUserInfo() {
    try {
        const response = await fetch('/api/auth/user');
        if (response.ok) {
            const user = await response.json();
            currentUser = user;
            document.getElementById('username').textContent = user.username || '用户';
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

// 菜单切换
function showSection(sectionId, element) {
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
        case 'system':
            if (typeof loadSystemConfigs === 'function') loadSystemConfigs();
            // 如果Pipeline标签页是激活的，自动加载Pipeline数据
            setTimeout(() => {
                const activeStepDefPanel = document.getElementById('step-def-config');
                if (activeStepDefPanel && activeStepDefPanel.classList.contains('active')) {
                    if (typeof loadStepDefs === 'function') {
                        loadStepDefs();
                    }
                }
                const activePipelineMgmtPanel = document.getElementById('pipeline-mgmt-config');
                if (activePipelineMgmtPanel && activePipelineMgmtPanel.classList.contains('active')) {
                    if (typeof loadPipelines === 'function') {
                        loadPipelines();
                    }
                }
            }, 100);
            break;
        case 'statistics':
            if (typeof loadStatistics === 'function') loadStatistics();
            break;
    }
}

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
    document.getElementById('modalContainer').innerHTML = '';
    window.currentModalConfirm = null;
}
window.closeModal = closeModal;

function confirmModal() {
    if (window.currentModalConfirm) {
        window.currentModalConfirm();
    }
    closeModal();
}
window.confirmModal = confirmModal;

// 确保showModal也在全局作用域中可用
window.showModal = showModal;

// ============================================
// Dashboard初始化 - 页面加载时执行
// ============================================

// 页面加载时初始化
document.addEventListener('DOMContentLoaded', function() {
    checkAuth();
    initMockData();
    loadEnvs();
    loadServicesForSelect();
    loadNodesForSelect();
    
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
        console.log('bindSystemButtons called');
        console.log('showConfigTab available:', typeof window.showConfigTab);
        
        const systemSection = document.getElementById('system-section');
        console.log('systemSection found:', !!systemSection);
        
        if (!systemSection) {
            console.error('system-section not found');
            return false;
        }
        
        // 使用事件委托绑定系统管理标签页按钮
        systemSection.addEventListener('click', function(e) {
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
        
        // 默认显示统计信息
        const statisticsMenuItem = document.querySelector('.menu-item[onclick*="statistics"]');
        if (statisticsMenuItem) {
            statisticsMenuItem.click();
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
