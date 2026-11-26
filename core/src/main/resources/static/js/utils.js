// ============================================
// 工具函数模块 - API调用、数据处理、下拉框加载等
// ============================================

/**
 * 必填星号，拼在 <label> 文字后面：<label>${escapeHtml(标签文字)}${REQUIRED_MARK}</label>。
 * 写死的 HTML（html-safe）；星号对读屏隐藏，必填由输入框的 required / aria-required 表达。
 * 星号、required 和 JS 校验三者要一致，检查见 scripts/dev/check-required-mark.js。
 */
const REQUIRED_MARK = '<span class="required-mark" aria-hidden="true">*</span>';

// 加载环境列表（用于下拉框）
async function loadEnvs() {
    try {
        const response = await fetch('/api/env/list');
        if (response.ok) {
            mockEnvs = await response.json() || [];
        }
    } catch (error) {
        console.error('Load envs error:', error);
    }
    
    // 填充环境下拉框
    const envSelect = document.getElementById('envId');
    if (envSelect) {
        envSelect.innerHTML = `<option value="">${escapeHtml(t('common.selectEnv'))}</option>`;
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
            mockServices = await response.json() || [];
        }
    } catch (error) {
        console.error('Load services error:', error);
    }
    
    const serviceSelect = document.getElementById('serviceId');
    if (serviceSelect) {
        serviceSelect.innerHTML = `<option value="">${escapeHtml(t('common.selectService'))}</option>`;
        mockServices.forEach(service => {
            const option = document.createElement('option');
            option.value = service.id;
            // 显示服务代码（GitLab项目名称），如果没有则显示服务名称
            option.textContent = service.code || service.name;
            serviceSelect.appendChild(option);
        });
    }
}

// 获取服务列表（用于构建任务等，仅使用后端真实数据）
async function getServicesList() {
    try {
        const response = await fetch('/api/service/list');
        if (response.ok) {
            const services = await response.json();
            return Array.isArray(services) ? services : [];
        }
    } catch (error) {
        console.error('Load services error:', error);
    }
    return [];
}

// 加载构建节点（用于下拉框）
async function loadNodesForSelect() {
    try {
        const response = await fetch('/api/node/list');
        if (response.ok) {
            mockNodes = await response.json() || [];
        }
    } catch (error) {
        console.error('Load nodes error:', error);
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
    let errorMessage = t('common.unknownError');
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
/**
 * HTML 转义：所有拼进 innerHTML / 模板字符串的后端数据都必须经过它。
 * 同时转义单引号，放进属性值或内联事件的字符串参数里也安全。
 * 全站只保留这一个实现，模块里不要再各写一份。
 */
function escapeHtml(text) {
    if (text == null) return '';
    return String(text)
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;')
        .replace(/'/g, '&#39;');
}

function formatDate(dateString) {
    if (!dateString) return '-';
    const date = new Date(dateString);
    return date.toLocaleString(getDateLocale(), {
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
        return `<span class="status-tag is-on">${escapeHtml(t('common.enabled'))}</span>`;
    }
    return `<span class="status-tag is-off">${escapeHtml(t('common.disabled'))}</span>`;
}

// 防抖函数
/**
 * 页面重新可见（切回标签页、窗口恢复）时回调。
 * 轮询的回调里统一用 document.hidden 跳过不可见期间的请求，再用这个函数在切回来时立刻补刷一次，
 * 不用等下一个周期。
 */
function onPageVisible(callback) {
    document.addEventListener('visibilitychange', () => {
        if (!document.hidden) {
            try {
                callback();
            } catch (e) {
                console.warn('onPageVisible callback failed', e);
            }
        }
    });
}

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
window.escapeHtml = escapeHtml;
window.formatDate = formatDate;
window.formatStatus = formatStatus;
window.onPageVisible = onPageVisible;
window.debounce = debounce;
window.throttle = throttle;
