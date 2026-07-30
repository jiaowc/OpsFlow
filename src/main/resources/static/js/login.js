// 登录功能
document.getElementById('loginForm').addEventListener('submit', async function(e) {
    e.preventDefault();
    
    const username = document.getElementById('username').value;
    const password = document.getElementById('password').value;
    const errorMsg = document.getElementById('errorMsg');
    
    errorMsg.textContent = '';
    
    try {
        const response = await fetch('/api/auth/login', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json'
            },
            body: JSON.stringify({ username, password })
        });
        
        const data = await response.json();
        
        if (data.success) {
            // 登录成功，跳转到控制台
            if (data.token) {
                localStorage.setItem('token', data.token);
            }
            window.location.href = '/statistics';
        } else {
            errorMsg.textContent = data.message || '登录失败，请检查用户名和密码';
        }
    } catch (error) {
        errorMsg.textContent = '登录失败，请稍后重试';
        console.error('Login error:', error);
    }
});

(function showSsoErrorFromQuery() {
    const params = new URLSearchParams(window.location.search);
    const err = params.get('sso_error');
    if (!err) {
        return;
    }
    const errorMsg = document.getElementById('errorMsg');
    if (errorMsg) {
        errorMsg.textContent = err;
    }
    // 清理地址栏参数，避免刷新重复提示
    const url = new URL(window.location.href);
    url.searchParams.delete('sso_error');
    window.history.replaceState({}, '', url.pathname + (url.search || '') + url.hash);
})();

(async function initFeishuLoginButton() {
    const box = document.getElementById('feishuLoginBox');
    if (!box) {
        return;
    }
    try {
        const response = await fetch('/api/auth/feishu/status');
        if (!response.ok) {
            return;
        }
        const data = await response.json();
        if (data && data.enabled && data.ready) {
            box.style.display = 'block';
        }
    } catch (e) {
        console.warn('Load feishu SSO status failed', e);
    }
})();
