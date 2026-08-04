// ============================================
// Dashboard业务逻辑模块
// 包含页面初始化、事件绑定、业务逻辑
// ============================================

// 加载Pipeline参数输入框
// 用于在构建任务中选择Pipeline模板后，动态加载参数输入框
async function loadPipelineParameters(pipelineId) {
    const container = document.getElementById('pipelineParametersContainer');
    if (!container) return;
    
    if (!pipelineId) {
        container.style.display = 'none';
        container.innerHTML = '';
        return;
    }
    
    try {
        const response = await fetch(`/api/pipeline/${pipelineId}`);
        if (response.ok) {
            const pipeline = await response.json();
            if (pipeline.parameterDefinitions && Object.keys(pipeline.parameterDefinitions).length > 0) {
                container.style.display = 'block';
                container.innerHTML = `
                    <div class="form-item">
                        <label>Pipeline参数</label>
                        ${Object.entries(pipeline.parameterDefinitions).map(([paramName, paramDesc]) => `
                            <div style="margin-bottom: 8px;">
                                <label style="font-size: 12px;">${paramName}${paramDesc ? ` (${paramDesc})` : ''}</label>
                                <input type="text" name="pipelineParam_${paramName}" placeholder="请输入${paramName}" style="width: 100%;">
                            </div>
                        `).join('')}
                    </div>
                `;
            } else {
                container.style.display = 'none';
                container.innerHTML = '';
            }
        }
    } catch (error) {
        console.error('Load pipeline parameters error:', error);
        container.style.display = 'none';
        container.innerHTML = '';
    }
}
window.loadPipelineParameters = loadPipelineParameters;

async function loadAppVersion() {
    const textEl = document.getElementById('appVersionText');
    const footerEl = document.getElementById('appVersionFooter');
    if (!textEl) {
        return;
    }
    try {
        const response = await fetch('/api/system/version');
        if (!response.ok) {
            throw new Error('version api failed');
        }
        const info = await response.json();
        const version = info.version || 'unknown';
        const name = info.name || 'OpsFlow';
        textEl.textContent = name + ' v' + version;
        if (footerEl) {
            const tips = [];
            if (info.buildTime) {
                tips.push('构建时间: ' + info.buildTime);
            }
            if (info.artifact) {
                tips.push('产物: ' + info.artifact);
            }
            footerEl.title = tips.length ? tips.join('\n') : ('当前版本 ' + version);
        }
    } catch (e) {
        textEl.textContent = 'OpsFlow';
    }
}
window.loadAppVersion = loadAppVersion;

// ============================================
// Dashboard页面初始化
// ============================================

// 页面加载时初始化
document.addEventListener('DOMContentLoaded', function() {
    console.log('=== Dashboard DOMContentLoaded fired ===');
    
    // 基础初始化（这些函数应该在 core.js 和 utils.js 中，已经加载）
    if (typeof window.checkAuth === 'function') window.checkAuth();
    if (typeof window.initMockData === 'function') window.initMockData();
    if (typeof window.loadEnvs === 'function') window.loadEnvs();
    if (typeof window.loadServicesForSelect === 'function') window.loadServicesForSelect();
    if (typeof window.loadNodesForSelect === 'function') window.loadNodesForSelect();
    loadAppVersion();
    
    // 延迟检查 system.js 函数，确保所有脚本都已执行
    // 使用多个时机检查，确保捕获到 system.js 的加载状态
    function checkSystemJsLoaded() {
        console.log('=== Checking system.js functions ===');
        console.log('Script loading check - showConfigTab:', typeof window.showConfigTab);
        console.log('Script loading check - showAddPipelineModal:', typeof window.showAddPipelineModal);
        console.log('Script loading check - showAddComponentModal:', typeof window.showAddComponentModal);
        
        // 检查是否有 system.js 的初始化日志（通过检查 window 对象是否有特殊标记）
        // 检查 system.js 是否已加载（通过检查是否有 system.js 中定义的函数）
        const systemFunctions = ['showConfigTab', 'showAddComponentModal', 'showAddPipelineModal', 'loadComponents', 'showSSOTab'];
        const loadedFunctions = systemFunctions.filter(fn => typeof window[fn] === 'function');
        console.log(`System.js functions loaded: ${loadedFunctions.length}/${systemFunctions.length}`, loadedFunctions);
        
        if (loadedFunctions.length === 0) {
            console.error('❌ ERROR: system.js functions not loaded!');
            console.error('Diagnostics:');
            console.error('1. Check Network tab - is /js/modules/system.js loading successfully? (Status 200)');
            console.error('2. Check Console tab - are there any JavaScript errors? (Red errors)');
            console.error('3. Expected log: "=== system.js script started loading ===" (if missing, script not executing)');
            console.error('4. Check if system.js file exists and is accessible');
            
            // 尝试检查脚本标签
            const systemScript = document.querySelector('script[src*="system.js"]');
            if (systemScript) {
                console.error('Script tag found:', systemScript.src);
            } else {
                console.error('Script tag for system.js not found in DOM!');
            }
            
            // 检查是否有其他脚本错误
            console.error('5. Check if any script before system.js has errors (they can prevent system.js from loading)');
        } else {
            console.log('✓ System.js functions are available');
        }
        
        return loadedFunctions.length > 0;
    }
    
    // 立即检查（在 setTimeout 之前）
    setTimeout(function() {
        checkSystemJsLoaded();
    }, 0);
    
    // 延迟检查（给更多时间）
    setTimeout(function() {
        if (!checkSystemJsLoaded()) {
            console.warn('⚠️ System.js still not loaded after 100ms, will retry...');
        }
    }, 100);
    
    // 更长的延迟检查
    setTimeout(function() {
        if (!checkSystemJsLoaded()) {
            console.error('❌ System.js still not loaded after 500ms!');
        }
    }, 500);
    
    // 绑定Pipeline按钮事件
    function bindPipelineButton() {
        const addPipelineBtn = document.getElementById('addPipelineBtn');
        if (!addPipelineBtn) return false;
        
        // 移除可能存在的旧事件监听器
        const newBtn = addPipelineBtn.cloneNode(true);
        addPipelineBtn.parentNode.replaceChild(newBtn, addPipelineBtn);
        
        // 绑定新的事件监听器
        newBtn.addEventListener('click', function(e) {
            e.preventDefault();
            e.stopPropagation();
            
            if (typeof window.showAddPipelineModal === 'function') {
                try {
                    window.showAddPipelineModal();
                } catch (error) {
                    console.error('Error calling showAddPipelineModal:', error);
                    alert('打开Pipeline模态框时出错: ' + error.message);
                }
            } else {
                alert('Pipeline功能未加载，请稍后再试');
            }
        });
        
        return true;
    }
    
    // 绑定系统管理相关按钮（使用事件委托，只需绑定一次）
    function bindSystemButtons() {
        if (window.__opsflowSystemButtonsBound) {
            return true;
        }
        const configRoot = document.querySelector('.main-content');
        if (!configRoot) {
            console.warn('main-content not found');
            return false;
        }
        window.__opsflowSystemButtonsBound = true;
        
        // 使用事件委托绑定配置区域的所有点击事件
        configRoot.addEventListener('click', function(e) {
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
                
                // 如果函数未加载，等待并重试
                if (typeof window.showConfigTab !== 'function') {
                    console.warn('⚠️ showConfigTab not available, waiting for system.js to load...');
                    console.log('Current window.showConfigTab:', typeof window.showConfigTab);
                    
                    // 诊断信息
                    const systemScript = document.querySelector('script[src*="system.js"]');
                    if (systemScript) {
                        console.log('✓ system.js script tag found:', systemScript.src);
                    } else {
                        console.error('✗ system.js script tag NOT found in DOM!');
                    }
                    
                    // 检查是否有其他脚本错误
                    console.log('Checking for script errors...');
                    
                    let retries = 0;
                    const maxRetries = 50; // 50次，总共5秒
                    const retryInterval = setInterval(function() {
                        retries++;
                        
                        if (typeof window.showConfigTab === 'function') {
                            clearInterval(retryInterval);
                            console.log(`✓ showConfigTab loaded after ${retries} retries, calling function`);
                            try {
                                window.showConfigTab(tabName, tabBtn);
                            } catch (error) {
                                console.error('Error calling showConfigTab:', error);
                                alert('调用系统功能时出错: ' + error.message);
                            }
                        } else if (retries >= maxRetries) {
                            clearInterval(retryInterval);
                            console.error('❌ showConfigTab function not available after', maxRetries, 'retries');
                            console.error('Diagnostics:');
                            console.error('1. Check Network tab - is /js/modules/system.js loading? (Status 200)');
                            console.error('2. Check Console tab - look for "=== system.js script started loading ==="');
                            console.error('3. Check for JavaScript errors (red errors in console)');
                            console.error('4. Try hard refresh: Ctrl+Shift+R (Windows) or Cmd+Shift+R (Mac)');
                            
                            const systemFunctions = Object.keys(window).filter(k => 
                                k.includes('show') || k.includes('Config') || k.includes('Component') || k.includes('Pipeline')
                            );
                            console.log('Available system functions:', systemFunctions);
                            console.log('All window properties with "show":', Object.keys(window).filter(k => k.toLowerCase().includes('show')));
                            
                            alert('系统功能未加载完成。\n\n请检查：\n1. 浏览器控制台是否有错误\n2. Network标签中system.js是否成功加载\n3. 尝试强制刷新页面（Ctrl+Shift+R）');
                        } else if (retries % 10 === 0) {
                            // 每10次重试输出一次日志
                            console.log(`Still waiting... (${retries}/${maxRetries})`);
                        }
                    }, 100);
                    return;
                }
                
                // 函数已加载，直接调用
                try {
                    window.showConfigTab(tabName, tabBtn);
                } catch (error) {
                    console.error('Error calling showConfigTab:', error);
                    alert('调用系统功能时出错: ' + error.message);
                }
                return;
            }
            
            // 检查是否点击了添加组件按钮
            const addComponentBtn = e.target.closest('#addComponentBtn');
            if (addComponentBtn) {
                e.preventDefault();
                e.stopPropagation();
                
                if (typeof window.showAddComponentModal !== 'function') {
                    console.log('showAddComponentModal not available, waiting...');
                    let retries = 0;
                    const maxRetries = 30;
                    const retryInterval = setInterval(function() {
                        retries++;
                        if (typeof window.showAddComponentModal === 'function') {
                            clearInterval(retryInterval);
                            try {
                                window.showAddComponentModal();
                            } catch (error) {
                                console.error('Error calling showAddComponentModal:', error);
                                alert('打开组件添加对话框时出错: ' + error.message);
                            }
                        } else if (retries >= maxRetries) {
                            clearInterval(retryInterval);
                            alert('组件管理功能未加载完成，请刷新页面重试');
                        }
                    }, 100);
                    return;
                }
                
                try {
                    window.showAddComponentModal();
                } catch (error) {
                    console.error('Error calling showAddComponentModal:', error);
                    alert('打开组件添加对话框时出错: ' + error.message);
                }
                return;
            }

            // 检查是否点击了添加步骤按钮
            const addStepDefBtn = e.target.closest('#addStepDefBtn');
            if (addStepDefBtn) {
                e.preventDefault();
                e.stopPropagation();
                if (typeof window.showCreateStepDefModal === 'function') {
                    try {
                        window.showCreateStepDefModal();
                    } catch (error) {
                        console.error('Error calling showCreateStepDefModal:', error);
                        alert('打开添加步骤对话框时出错: ' + error.message);
                    }
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
                    try {
                        window.showCreatePipelineViewModal();
                    } catch (error) {
                        console.error('Error calling showCreatePipelineViewModal:', error);
                        alert('打开新建视图对话框时出错: ' + error.message);
                    }
                } else {
                    alert('视图管理功能未加载，请刷新页面');
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

            // 模版「添加」按钮：交给 template.js / 按钮 onclick，避免抢事件
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
                
                if (typeof window.showSSOTab !== 'function') {
                    console.log('showSSOTab not available, waiting...');
                    let retries = 0;
                    const maxRetries = 30;
                    const retryInterval = setInterval(function() {
                        retries++;
                        if (typeof window.showSSOTab === 'function') {
                            clearInterval(retryInterval);
                            try {
                                window.showSSOTab(ssoType, ssoTabBtn);
                            } catch (error) {
                                console.error('Error calling showSSOTab:', error);
                                alert('切换SSO标签页时出错: ' + error.message);
                            }
                        } else if (retries >= maxRetries) {
                            clearInterval(retryInterval);
                            alert('SSO功能未加载完成，请刷新页面重试');
                        }
                    }, 100);
                    return;
                }
                
                try {
                    window.showSSOTab(ssoType, ssoTabBtn);
                } catch (error) {
                    console.error('Error calling showSSOTab:', error);
                    alert('切换SSO标签页时出错: ' + error.message);
                }
                return;
            }
        });
        
        return true;
    }
    
    // 延迟绑定事件，确保所有脚本都已执行
    // 使用 setTimeout 确保在同步脚本执行完成后绑定
    setTimeout(function() {
        console.log('=== Binding event handlers ===');
        
        // 绑定系统管理按钮（事件委托）
        if (bindSystemButtons()) {
            console.log('✓ System buttons bound');
        } else {
            console.warn('✗ Failed to bind system buttons');
        }
        
        // 绑定Pipeline按钮
        if (bindPipelineButton()) {
            console.log('✓ Pipeline button bound');
        } else {
            console.warn('✗ Pipeline button not found or binding failed');
        }
    }, 0); // 使用 0ms 延迟，确保在当前执行栈完成后执行
    
    // 监听模块加载完成事件（system.js 会触发此事件）
    window.addEventListener('systemModuleLoaded', function() {
        console.log('=== systemModuleLoaded event received ===');
        console.log('showConfigTab now available:', typeof window.showConfigTab);
        
        // 重新绑定Pipeline按钮（确保在 system.js 加载后绑定）
        if (bindPipelineButton()) {
            console.log('✓ Pipeline button bound after system.js loaded');
        }
    });
    
    // 等待 window.onload（确保所有资源都加载完成）
    if (document.readyState !== 'complete') {
        window.addEventListener('load', function() {
            console.log('=== Window load event fired ===');
            console.log('Final check - showConfigTab:', typeof window.showConfigTab);
            console.log('Final check - showAddPipelineModal:', typeof window.showAddPipelineModal);
            
            // 最终检查并绑定
            if (!bindPipelineButton()) {
                console.warn('Pipeline button still not bound after window load');
            }
        });
    } else {
        // 如果页面已经加载完成，立即检查
        console.log('Page already loaded, checking system functions...');
        setTimeout(function() {
            if (bindSystemButtons()) {
                console.log('✓ System buttons bound (page already loaded)');
            }
            if (bindPipelineButton()) {
                console.log('✓ Pipeline button bound (page already loaded)');
            }
        }, 0);
    }
    
    // 延迟加载数据，等待认证完成
    setTimeout(() => {
        if (typeof window.loadPipelineJobsView === 'function') {
            window.loadPipelineJobsView();
        }
        if (typeof window.loadStatistics === 'function') window.loadStatistics();
        if (typeof window.loadSystemConfigs === 'function') window.loadSystemConfigs();
    }, 200);
});
