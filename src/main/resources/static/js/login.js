// 登录功能（含 i18n + 企业品牌）
(function initLoginI18n() {
    if (typeof applyI18n === 'function') {
        applyI18n(document);
    }
})();

(async function initLoginBranding() {
    try {
        const response = await fetch('/api/branding/public');
        if (!response.ok) return;
        const data = await response.json();
        const custom = !!data.custom && !!data.logoUrl;
        const title = (data.title && String(data.title).trim()) || 'OPSFLOW';
        document.querySelectorAll('[data-brand-text]').forEach(el => {
            el.textContent = title;
            if (custom) el.classList.add('brand-text-with-logo');
        });
        document.querySelectorAll('[data-brand-logo]').forEach(img => {
            if (custom) {
                img.src = data.logoUrl;
                img.alt = title;
                img.hidden = false;
                img.style.display = '';
            }
        });
    } catch (e) {
        console.warn('Load login branding failed', e);
    }
})();

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
            if (data.token) {
                localStorage.setItem('token', data.token);
            }
            window.location.href = '/statistics';
        } else {
            const fallback = typeof t === 'function' ? t('login.failed') : '登录失败，请检查用户名和密码';
            errorMsg.textContent = data.message || fallback;
        }
    } catch (error) {
        errorMsg.textContent = typeof t === 'function' ? t('login.retry') : '登录失败，请稍后重试';
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
