// ============================================
// 工具函数模块 - API调用、数据处理、下拉框加载等
// ============================================

// 加载环境列表（用于下拉框）
async function loadEnvs() {
    try {
        const response = await fetch('/api/env/list');
        if (response.ok) {
            const envs = await response.json();
            if (envs && envs.length > 0) {
                mockEnvs = envs;
            }
        }
    } catch (error) {
        console.error('Load envs error:', error);
    }
    
    // 填充环境下拉框
    const envSelect = document.getElementById('envId');
    if (envSelect) {
        envSelect.innerHTML = '<option value="">请选择环境</option>';
        mockEnvs.forEach(env => {
            const option = document.createElement('option');
            option.value = env.id;
            option.textContent = env.name;
            envSelect.appendChild(option);
        });
    }
}

// 加载服务列表（用于下拉框）
async function loadServicesForSelect() {
    try {
        const response = await fetch('/api/service/list');
        if (response.ok) {
            const services = await response.json();
            if (services && services.length > 0) {
                mockServices = services;
            }
        }
    } catch (error) {
        console.error('Load services error:', error);
    }
    
    const serviceSelect = document.getElementById('serviceId');
    if (serviceSelect) {
        serviceSelect.innerHTML = '<option value="">请选择服务</option>';
        mockServices.forEach(service => {
            const option = document.createElement('option');
            option.value = service.id;
            // 显示服务代码（GitLab项目名称），如果没有则显示服务名称
            option.textContent = service.code || service.name;
            serviceSelect.appendChild(option);
        });
    }
}

// 获取服务列表（用于构建任务等）
async function getServicesList() {
    try {
        const response = await fetch('/api/service/list');
        if (response.ok) {
            const services = await response.json();
            if (services && services.length > 0) {
                return services;
            }
        }
    } catch (error) {
        console.error('Load services error:', error);
    }
    return mockServices;
}

// 加载Jenkins节点（用于下拉框）
async function loadNodesForSelect() {
    try {
        const response = await fetch('/api/node/list');
        if (response.ok) {
            const nodes = await response.json();
            if (nodes && nodes.length > 0) {
                mockNodes = nodes;
            }
        }
    } catch (error) {
        console.error('Load nodes error:', error);
    }
    
    const nodeSelect = document.getElementById('jenkinsNode');
    if (nodeSelect) {
        nodeSelect.innerHTML = '<option value="">随机选择</option>';
        mockNodes.filter(n => n.nodeType === 'build').forEach(node => {
            const option = document.createElement('option');
            option.value = node.id;
            option.textContent = `${node.name} (${node.status})`;
            nodeSelect.appendChild(option);
        });
    }
}

// 刷新全局节点列表（用于Pipeline等）
async function refreshGlobalNodes() {
    try {
        const response = await fetch('/api/node/list');
        if (response.ok) {
            const nodes = await response.json();
            mockNodes = nodes || [];
            return mockNodes;
        }
    } catch (error) {
        console.error('Load nodes error:', error);
    }
    return mockNodes || [];
}

// API调用封装 - GET请求
async function apiGet(url) {
    try {
        const response = await fetch(url);
        if (response.ok) {
            return await response.json();
        }
        return null;
    } catch (error) {
        console.error('API GET error:', error);
        return null;
    }
}

// API调用封装 - POST请求
async function apiPost(url, data) {
    try {
        const response = await fetch(url, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(data)
        });
        return response;
    } catch (error) {
        console.error('API POST error:', error);
        throw error;
    }
}

// API调用封装 - PUT请求
async function apiPut(url, data) {
    try {
        const response = await fetch(url, {
            method: 'PUT',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(data)
        });
        return response;
    } catch (error) {
        console.error('API PUT error:', error);
        throw error;
    }
}

// API调用封装 - DELETE请求
async function apiDelete(url) {
    try {
        const response = await fetch(url, { method: 'DELETE' });
        return response;
    } catch (error) {
        console.error('API DELETE error:', error);
        throw error;
    }
}

// 处理API错误响应
async function handleApiError(response) {
    let errorMessage = '未知错误';
    try {
        const errorText = await response.text();
        if (errorText) {
            try {
                const error = JSON.parse(errorText);
                errorMessage = error.message || error.error || errorText;
            } catch (e) {
                errorMessage = errorText;
            }
        }
    } catch (e) {
        errorMessage = 'HTTP ' + response.status + ': ' + response.statusText;
    }
    return errorMessage;
}

// 格式化日期
function formatDate(dateString) {
    if (!dateString) return '-';
    const date = new Date(dateString);
    return date.toLocaleString('zh-CN', {
        year: 'numeric',
        month: '2-digit',
        day: '2-digit',
        hour: '2-digit',
        minute: '2-digit'
    });
}

// 格式化状态显示
function formatStatus(status) {
    if (status === 1 || status === '1' || status === true) {
        return '<span style="color: #059669;">启用</span>';
    }
    return '<span style="color: #dc2626;">禁用</span>';
}

// 防抖函数
function debounce(func, wait) {
    let timeout;
    return function executedFunction(...args) {
        const later = () => {
            clearTimeout(timeout);
            func(...args);
        };
        clearTimeout(timeout);
        timeout = setTimeout(later, wait);
    };
}

// 节流函数
function throttle(func, limit) {
    let inThrottle;
    return function(...args) {
        if (!inThrottle) {
            func.apply(this, args);
            inThrottle = true;
            setTimeout(() => inThrottle = false, limit);
        }
    };
}

// 注册到全局作用域
window.loadEnvs = loadEnvs;
window.loadServicesForSelect = loadServicesForSelect;
window.getServicesList = getServicesList;
window.loadNodesForSelect = loadNodesForSelect;
window.refreshGlobalNodes = refreshGlobalNodes;
window.apiGet = apiGet;
window.apiPost = apiPost;
window.apiPut = apiPut;
window.apiDelete = apiDelete;
window.handleApiError = handleApiError;
window.formatDate = formatDate;
window.formatStatus = formatStatus;
window.debounce = debounce;
window.throttle = throttle;
