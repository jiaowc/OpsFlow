// 登录功能（含 i18n + 企业品牌）
(function initLoginI18n() {
    if (typeof applyI18n === 'function') {
        applyI18n(document);
    }
})();

// 登录页扩展（如企业版的品牌、第三方登录按钮）：按 /api/ui/login-extensions 合并文案、按顺序加载脚本；
// 单个扩展失败只影响它自己，登录表单不受影响
(async function loadLoginExtensions() {
    let manifest;
    try {
        const resp = await fetch('/api/ui/login-extensions');
        if (!resp.ok) return;
        manifest = await resp.json();
    } catch (e) {
        console.warn('Load login extensions failed', e);
        return;
    }
    const i18n = window.OpsFlowI18n;
    for (const ext of (manifest && manifest.extensions) || []) {
        try {
            for (const url of ext.messageBundles || []) {
                const resp = await fetch(url);
                if (!resp.ok) throw new Error(url + ' HTTP ' + resp.status);
                const bundle = await resp.json();
                Object.keys(bundle || {}).forEach(locale => {
                    if (!i18n || !i18n.messages) return;
                    const target = i18n.messages[locale] || (i18n.messages[locale] = {});
                    Object.keys(bundle[locale] || {}).forEach(key => {
                        if (!Object.prototype.hasOwnProperty.call(target, key)) target[key] = bundle[locale][key];
                    });
                });
            }
            for (const url of ext.scripts || []) {
                await new Promise((resolve, reject) => {
                    const el = document.createElement('script');
                    el.src = url;
                    el.onload = resolve;
                    el.onerror = () => reject(new Error(url + ' 加载失败')); // i18n-ignore
                    document.body.appendChild(el);
                });
            }
        } catch (e) {
            console.error('[login-ext] 扩展 ' + ext.id + ' 加载失败', e); // i18n-ignore
        }
    }
    if (typeof applyI18n === 'function') {
        applyI18n(document);
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
            finishLogin(data);
        } else if (data.step === 'two_factor') {
            showTwoFactorStep(data.username || username);
        } else {
            errorMsg.textContent = data.message || t('login.failed');
        }
    } catch (error) {
        errorMsg.textContent = t('login.retry');
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

/** 登录后回到哪里：next 参数只接受站内相对路径（以单个 / 开头），否则去首页 */
function loginRedirectTarget() {
    try {
        const next = new URLSearchParams(window.location.search).get('next');
        // 浏览器解析地址时会去掉制表符、换行，「/<Tab>/evil.com」会变成「//evil.com」，所以带空白或控制字符的一律不认
        if (next && /^\/(?![\/\\])/.test(next) && !/[\s\u0000-\u001f\u007f]/.test(next) && !/^\/index\.html/.test(next)) {
            return next;
        }
    } catch (e) {
        /* 忽略非法参数 */
    }
    return '/statistics';
}

function finishLogin(data) {
    if (data.token) {
        localStorage.setItem('token', data.token);
    }
    window.location.href = loginRedirectTarget();
}

// ---- 登录第二步（两步验证）：在同一个登录框里原位切换，提交到 /api/auth/two-factor/verify
const twoFactorStep = { username: '', useRecovery: false };

function showTwoFactorStep(username) {
    twoFactorStep.username = username;
    twoFactorStep.useRecovery = false;
    document.getElementById('loginForm').hidden = true;
    document.getElementById('twoFactorForm').hidden = false;
    document.getElementById('twoFactorError').textContent = '';
    renderTwoFactorMode();
}

function renderTwoFactorMode() {
    const recovery = twoFactorStep.useRecovery;
    const input = document.getElementById('twoFactorCode');
    const label = document.getElementById('twoFactorCodeLabel');
    const toggle = document.getElementById('twoFactorToggle');
    updateTwoFactorHint();
    label.setAttribute('data-i18n', recovery ? 'login.twoFactor.recoveryLabel' : 'login.twoFactor.codeLabel');
    label.textContent = t(recovery ? 'login.twoFactor.recoveryLabel' : 'login.twoFactor.codeLabel');
    toggle.setAttribute('data-i18n', recovery ? 'login.twoFactor.useCode' : 'login.twoFactor.useRecovery');
    toggle.textContent = t(recovery ? 'login.twoFactor.useCode' : 'login.twoFactor.useRecovery');
    input.value = '';
    input.classList.toggle('totp-code-input', !recovery);
    input.classList.toggle('recovery-code-input', recovery);
    if (recovery) {
        input.removeAttribute('inputmode');
        input.removeAttribute('pattern');
        input.setAttribute('maxlength', '20');
        input.setAttribute('autocomplete', 'off');
        input.setAttribute('placeholder', 'xxxx-xxxx');
    } else {
        input.setAttribute('inputmode', 'numeric');
        input.setAttribute('pattern', '[0-9]*');
        input.setAttribute('maxlength', '6');
        input.setAttribute('autocomplete', 'one-time-code');
        input.removeAttribute('placeholder');
    }
    input.focus();
}

async function backToPasswordStep(message) {
    // 作废服务端的待验证状态（会话里只有它），再回到账号密码表单
    try {
        await fetch('/api/auth/logout', { method: 'POST' });
    } catch (e) {
        // 忽略：待验证状态 5 分钟后自然失效
    }
    document.getElementById('twoFactorForm').hidden = true;
    document.getElementById('loginForm').hidden = false;
    const password = document.getElementById('password');
    password.value = '';
    document.getElementById('errorMsg').textContent = message || '';
    password.focus();
}

function twoFactorErrorText(data) {
    const n = data.remainingAttempts;
    switch (data.code) {
        case 'two_factor.code_invalid':
            return n == null ? t('login.twoFactor.empty') : t('twoFactor.err.codeInvalid', { n: n });
        case 'two_factor.recovery_invalid':
            return t('twoFactor.err.recoveryInvalid', { n: n == null ? '-' : n });
        default:
            return data.message || t('login.failed');
    }
}

document.getElementById('twoFactorForm').addEventListener('submit', async function(e) {
    e.preventDefault();
    const input = document.getElementById('twoFactorCode');
    const errorEl = document.getElementById('twoFactorError');
    const submit = document.getElementById('twoFactorSubmit');
    const value = input.value.trim();
    errorEl.textContent = '';
    if (!value) {
        errorEl.textContent = t(twoFactorStep.useRecovery ? 'login.twoFactor.emptyRecovery' : 'login.twoFactor.empty');
        input.focus();
        return;
    }
    const body = twoFactorStep.useRecovery ? { recoveryCode: value } : { code: value.replace(/\s+/g, '') };
    submit.disabled = true;
    try {
        const response = await fetch('/api/auth/two-factor/verify', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(body)
        });
        const data = await response.json();
        if (data.success) {
            finishLogin(data);
            return;
        }
        if (data.code === 'two_factor.too_many') {
            backToPasswordStep(t('twoFactor.err.tooMany'));
        } else if (data.code === 'two_factor.expired') {
            backToPasswordStep(t('twoFactor.err.expired'));
        } else if (!data.code) {
            // 账号已被锁定等：待验证状态已作废，回到账号密码表单显示后端提示
            backToPasswordStep(data.message || t('login.failed'));
        } else {
            errorEl.textContent = twoFactorErrorText(data);
            input.select();
        }
    } catch (error) {
        errorEl.textContent = t('login.retry');
        console.error('Two-factor verify error:', error);
    } finally {
        submit.disabled = false;
    }
});

document.getElementById('twoFactorToggle').addEventListener('click', function() {
    twoFactorStep.useRecovery = !twoFactorStep.useRecovery;
    document.getElementById('twoFactorError').textContent = '';
    renderTwoFactorMode();
});

document.getElementById('twoFactorBack').addEventListener('click', function() {
    backToPasswordStep('');
});

function updateTwoFactorHint() {
    document.getElementById('twoFactorHint').textContent = twoFactorStep.useRecovery
        ? t('login.twoFactor.recoveryHint')
        : t('login.twoFactor.hint', { username: twoFactorStep.username });
}

// 社区版源码入口：只在版本接口明确返回社区版时显示
let loginVersionInfo = null;
(async function loadLoginSourceNotice() {
    if (!window.OpsFlowSourceNotice) {
        return;
    }
    loginVersionInfo = await OpsFlowSourceNotice.fetchVersionInfo();
    OpsFlowSourceNotice.render(document.getElementById('loginSourceNotice'), loginVersionInfo);
})();

// 切换语言时 applyI18n 只刷新 data-i18n 元素；带账号名的提示、源码入口单独重写
window.onLocaleChange = function() {
    if (!document.getElementById('twoFactorForm').hidden) {
        updateTwoFactorHint();
    }
    if (window.OpsFlowSourceNotice) {
        OpsFlowSourceNotice.render(document.getElementById('loginSourceNotice'), loginVersionInfo);
    }
    // 和控制台一致：扩展（如企业品牌）监听此事件，在 applyI18n 重置文案后重新应用
    try {
        window.dispatchEvent(new CustomEvent('opsflow:localechange'));
    } catch (e) {
        console.warn('localechange event failed', e);
    }
};
