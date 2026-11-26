// ============================================
// 个人两步验证：右上角用户菜单的「两步验证」弹窗、全员强制时的绑定引导
// 接口都在 /api/auth/two-factor/ 下（强制绑定期间也能调用）：
//   status、enroll/start、enroll/confirm、recovery-codes/regenerate、replace、disable
// 二维码用 js/vendor/qrcode-generator-1.4.4.js 在浏览器本地画，otpauth 地址不发给任何第三方；
// 画不出来时照样可以手动输入密钥
// ============================================

const tfState = {
    forced: false,
    view: 'loading',   // loading / unavailable / intro / scan / verify / codes / manage / confirm
    status: null,
    secret: '',
    uri: '',
    account: '',
    replacing: false,
    codes: [],
    codesSaved: false,
    action: '',         // confirm 视图要做的操作：regenerate / replace / disable
    error: '',
    busy: false,
    manualOpen: false
};

/** 右上角用户菜单的「两步验证」：策略不是关闭、且账号来源是本地或 LDAP 时显示 */
function updateTwoFactorMenuItem() {
    const item = document.getElementById('twoFactorMenuItem');
    if (!item) return;
    const tf = currentUser && currentUser.twoFactor;
    item.style.display = tf && tf.eligible && tf.policy && tf.policy !== 'off' ? '' : 'none';
}
window.updateTwoFactorMenuItem = updateTwoFactorMenuItem;

/** forced=true：全员强制且未绑定，弹窗不能关闭，只能完成绑定或退出登录 */
async function openTwoFactorModal(forced) {
    const menu = document.getElementById('userMenu');
    if (menu) menu.classList.remove('open');
    Object.assign(tfState, {
        forced: !!forced, view: 'loading', status: null, secret: '', uri: '', account: '',
        replacing: false, codes: [], codesSaved: false, action: '', error: '', busy: false, manualOpen: false
    });
    tfRender();
    try {
        const resp = await fetch('/api/auth/two-factor/status');
        const data = await resp.json();
        if (!resp.ok || !data.success) throw new Error(data.message || resp.status);
        tfState.status = data;
        if (!data.eligible || data.policy === 'off') {
            tfState.view = 'unavailable';
        } else {
            tfState.view = data.enrolled ? 'manage' : 'intro';
        }
    } catch (e) {
        console.error('Load two-factor status error:', e);
        tfState.view = 'unavailable';
        tfState.error = t('twoFactor.loadFailed');
    }
    tfRender();
}
window.openTwoFactorModal = openTwoFactorModal;

function tfHost() {
    let host = document.getElementById('twoFactorModalHost');
    if (!host) {
        host = document.createElement('div');
        host.id = 'twoFactorModalHost';
        document.body.appendChild(host);
    }
    return host;
}

function tfRender() {
    const host = tfHost();
    const closable = !tfState.forced;
    const title = tfState.forced ? t('twoFactor.forcedTitle') : t('twoFactor.title');
    host.innerHTML = `
        <div class="modal-overlay"${closable ? ' onclick="tfRequestClose(event)"' : ''}>
            <div class="modal tf-modal" role="dialog" aria-modal="true" aria-labelledby="tfModalTitle" onclick="event.stopPropagation()">
                <div class="modal-header">
                    <h3 id="tfModalTitle">${escapeHtml(title)}</h3>
                    ${closable ? `<button type="button" class="modal-close" aria-label="${escapeHtml(t('common.close'))}" onclick="tfRequestClose()">&times;</button>` : ''}
                </div>
                <div class="modal-body">${renderTfBody()}</div>
                <div class="modal-footer">${renderTfFooter()}</div>
            </div>
        </div>`;
    if (tfState.view === 'scan') tfDrawQr();
    const focus = host.querySelector('[data-tf-autofocus]');
    if (focus) focus.focus();
}

function renderTfError() {
    return tfState.error ? `<div class="tf-error" role="alert">${escapeHtml(tfState.error)}</div>` : '';
}

function renderTfStepLabel(n) {
    return `<div class="tf-step">${escapeHtml(t('twoFactor.step', { n: n }))}</div>`;
}

function renderTfBody() {
    const s = tfState;
    const forcedHint = s.forced ? `<p class="tf-text">${escapeHtml(t('twoFactor.forcedHint'))}</p>` : '';
    switch (s.view) {
        case 'loading':
            return `<p class="tf-text">${escapeHtml(t('common.loading'))}</p>`;
        case 'unavailable':
            return `<p class="tf-text">${escapeHtml(s.error || t('twoFactor.notAvailable'))}</p>`;
        case 'intro':
            return `${forcedHint}<p class="tf-text">${escapeHtml(t('twoFactor.intro'))}</p>${renderTfError()}`;
        case 'scan':
            return `${renderTfStepLabel(1)}
                <h4 class="tf-subtitle">${escapeHtml(t('twoFactor.scanTitle'))}</h4>
                <p class="tf-text">${escapeHtml(t('twoFactor.scanHint'))}</p>
                <div class="totp-qr" id="tfQr" aria-hidden="true"></div>
                <button type="button" class="tf-link" aria-expanded="${s.manualOpen ? 'true' : 'false'}" onclick="tfToggleManual()">${escapeHtml(t('twoFactor.manualEntry'))}</button>
                <div class="tf-manual"${s.manualOpen ? '' : ' hidden'}>
                    <div class="tf-label">${escapeHtml(t('twoFactor.secretLabel', { account: escapeHtml(s.account) }))}</div>
                    <div class="tf-secret-row">
                        <code class="tf-secret" id="tfSecret">${escapeHtml(tfGroupSecret(s.secret))}</code>
                        <button type="button" class="btn-secondary btn-sm" onclick="tfCopy(tfState.secret)">${escapeHtml(t('twoFactor.copy'))}</button>
                    </div>
                </div>`;
        case 'verify':
            return `${renderTfStepLabel(2)}
                <h4 class="tf-subtitle">${escapeHtml(t('twoFactor.verifyTitle'))}</h4>
                <p class="tf-text">${escapeHtml(t('twoFactor.verifyHint'))}</p>
                <label class="tf-label" for="tfCode">${escapeHtml(t('twoFactor.codeLabel'))}</label>
                <input type="text" id="tfCode" class="totp-code-input" inputmode="numeric" autocomplete="one-time-code"
                       maxlength="6" pattern="[0-9]*" data-tf-autofocus onkeydown="if(event.key==='Enter'){event.preventDefault();tfConfirmEnroll();}">
                ${renderTfError()}`;
        case 'codes':
            return `${s.forced || s.replacing || !s.status || !s.status.enrolled ? renderTfStepLabel(3) : ''}
                <h4 class="tf-subtitle">${escapeHtml(t('twoFactor.recoveryTitle'))}</h4>
                <p class="tf-text">${escapeHtml(t('twoFactor.recoveryHint'))}</p>
                <ul class="recovery-grid">${s.codes.map(code => `<li>${escapeHtml(code)}</li>`).join('')}</ul>
                <div class="tf-actions-row">
                    <button type="button" class="btn-secondary btn-sm" onclick="tfCopy(tfState.codes.join('\\n'))">${escapeHtml(t('twoFactor.copyAll'))}</button>
                    <button type="button" class="btn-secondary btn-sm" onclick="tfDownloadCodes()">${escapeHtml(t('twoFactor.download'))}</button>
                </div>
                <label class="tf-check"><input type="checkbox" id="tfCodesSaved"${s.codesSaved ? ' checked' : ''} onchange="tfOnCodesSaved(this.checked)"> ${escapeHtml(t('twoFactor.savedCheck'))}</label>`;
        case 'manage': {
            const st = s.status || {};
            const time = st.confirmedTime ? formatDate(st.confirmedTime) : '-';
            const forbidden = st.policy === 'required'
                ? `<p class="tf-text tf-muted">${escapeHtml(t('twoFactor.disableForbidden'))}</p>` : '';
            return `<p class="tf-text">${escapeHtml(t('twoFactor.statusOn', { time: time, n: Number(st.recoveryCodesLeft) || 0 }))}</p>
                <div class="tf-manage-actions">
                    <button type="button" class="btn-secondary" onclick="tfBeginAction('regenerate')">${escapeHtml(t('twoFactor.regenerate'))}</button>
                    <button type="button" class="btn-secondary" onclick="tfBeginAction('replace')">${escapeHtml(t('twoFactor.replace'))}</button>
                    ${st.policy === 'required' ? '' : `<button type="button" class="btn-danger" onclick="tfBeginAction('disable')">${escapeHtml(t('twoFactor.disable'))}</button>`}
                </div>
                ${forbidden}${renderTfError()}`;
        }
        case 'confirm': {
            const hintKey = { regenerate: 'twoFactor.regenerateHint', replace: 'twoFactor.replaceHint', disable: 'twoFactor.disableHint' }[s.action];
            return `<h4 class="tf-subtitle">${escapeHtml(tfActionLabel(s.action))}</h4>
                <p class="tf-text">${escapeHtml(t(hintKey))}</p>
                <label class="tf-label" for="tfCurrentCode">${escapeHtml(t('twoFactor.confirmWithCode'))}</label>
                <input type="text" id="tfCurrentCode" class="tf-current-input" autocomplete="one-time-code" maxlength="20"
                       placeholder="${escapeHtml(t('twoFactor.currentCodePlaceholder'))}" data-tf-autofocus
                       onkeydown="if(event.key==='Enter'){event.preventDefault();tfSubmitAction();}">
                ${renderTfError()}`;
        }
        default:
            return '';
    }
}

function renderTfFooter() {
    const s = tfState;
    const busy = s.busy ? ' disabled' : '';
    const leave = s.forced
        ? `<button type="button" class="btn-secondary" onclick="logout()">${escapeHtml(t('header.logout'))}</button>`
        : `<button type="button" class="btn-secondary" onclick="tfRequestClose()">${escapeHtml(t('common.cancel'))}</button>`;
    switch (s.view) {
        case 'intro':
            return `${leave}<button type="button" class="btn-primary" onclick="tfStartEnroll()"${busy}>${escapeHtml(t('twoFactor.start'))}</button>`;
        case 'scan':
            return `${leave}<button type="button" class="btn-primary" onclick="tfGoVerify()">${escapeHtml(t('twoFactor.next'))}</button>`;
        case 'verify':
            return `<button type="button" class="btn-secondary" onclick="tfGoScan()">${escapeHtml(t('twoFactor.back'))}</button>
                <button type="button" class="btn-primary" onclick="tfConfirmEnroll()"${busy}>${escapeHtml(t('twoFactor.verifyEnable'))}</button>`;
        case 'codes':
            return `<button type="button" class="btn-primary" id="tfFinishBtn" onclick="tfFinish()"${s.codesSaved ? '' : ' disabled'}>${escapeHtml(t('twoFactor.finish'))}</button>`;
        case 'confirm':
            return `<button type="button" class="btn-secondary" onclick="tfBackToManage()">${escapeHtml(t('twoFactor.back'))}</button>
                <button type="button" class="${s.action === 'disable' ? 'btn-danger' : 'btn-primary'}" onclick="tfSubmitAction()"${busy}>${escapeHtml(s.action === 'disable' ? t('twoFactor.disable') : t('twoFactor.continue'))}</button>`;
        default:
            return s.forced ? leave : `<button type="button" class="btn-secondary" onclick="tfRequestClose()">${escapeHtml(t('common.close'))}</button>`;
    }
}

function tfActionLabel(action) {
    return t({ regenerate: 'twoFactor.regenerate', replace: 'twoFactor.replace', disable: 'twoFactor.disable' }[action] || 'twoFactor.title');
}

/** 密钥按 4 位一组显示，方便手抄 */
function tfGroupSecret(secret) {
    return String(secret || '').replace(/(.{4})/g, '$1 ').trim();
}

function tfDrawQr() {
    const box = document.getElementById('tfQr');
    if (!box) return;
    box.textContent = '';
    try {
        if (typeof qrcode !== 'function') throw new Error('qrcode library missing');
        const qr = qrcode(0, 'M');
        qr.addData(tfState.uri);
        qr.make();
        const count = qr.getModuleCount();
        const quiet = 2;
        const size = count + quiet * 2;
        let d = '';
        for (let r = 0; r < count; r++) {
            for (let c = 0; c < count; c++) {
                if (qr.isDark(r, c)) d += 'M' + (c + quiet) + ' ' + (r + quiet) + 'h1v1h-1z';
            }
        }
        const ns = 'http://www.w3.org/2000/svg';
        const svg = document.createElementNS(ns, 'svg');
        svg.setAttribute('viewBox', '0 0 ' + size + ' ' + size);
        svg.setAttribute('width', '180');
        svg.setAttribute('height', '180');
        svg.setAttribute('shape-rendering', 'crispEdges');
        const bg = document.createElementNS(ns, 'rect');
        bg.setAttribute('width', String(size));
        bg.setAttribute('height', String(size));
        bg.setAttribute('fill', '#fff');
        const path = document.createElementNS(ns, 'path');
        path.setAttribute('d', d);
        path.setAttribute('fill', '#000');
        svg.appendChild(bg);
        svg.appendChild(path);
        box.appendChild(svg);
    } catch (e) {
        console.error('Draw QR code error:', e);
        box.classList.add('is-failed');
        box.textContent = t('twoFactor.qrFailed');
        tfState.manualOpen = true;
        const manual = document.querySelector('#twoFactorModalHost .tf-manual');
        if (manual) manual.hidden = false;
    }
}

function tfToggleManual() {
    tfState.manualOpen = !tfState.manualOpen;
    const manual = document.querySelector('#twoFactorModalHost .tf-manual');
    const link = document.querySelector('#twoFactorModalHost .tf-link');
    if (manual) manual.hidden = !tfState.manualOpen;
    if (link) link.setAttribute('aria-expanded', tfState.manualOpen ? 'true' : 'false');
}

async function tfPost(url, body) {
    const resp = await fetch(url, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(body || {})
    });
    const data = await resp.json().catch(() => ({}));
    if (data && data.code === 'two_factor.too_many') {
        // 连续输错 5 次，后端已作废会话
        notifyAfterReload(t('twoFactor.sessionEnded'), 'error');
        window.location.href = '/index.html';
        return null;
    }
    if (data && data.code === 'two_factor.not_logged_in') {
        window.location.href = '/index.html';
        return null;
    }
    if (!resp.ok && !data.code) {
        data.success = false;
        data.message = data.message || data.error || ('HTTP ' + resp.status);
    }
    return data;
}

/** 失败返回 → 提示文字（后端 message 是中文，按 code 换成当前语言） */
function tfErrorText(data, setup) {
    const n = data && data.remainingAttempts;
    switch (data && data.code) {
        case 'two_factor.code_invalid':
            return setup || n == null ? t('twoFactor.codeWrongSetup') : t('twoFactor.err.codeInvalid', { n: n });
        case 'two_factor.recovery_invalid':
            return t('twoFactor.err.recoveryInvalid', { n: n == null ? '-' : n });
        case 'two_factor.enroll_expired':
            return t('twoFactor.enrollExpired');
        case 'two_factor.disable_forbidden':
            return t('twoFactor.disableForbidden');
        case 'two_factor.not_eligible':
        case 'two_factor.policy_off':
            return t('twoFactor.notAvailable');
        default:
            return (data && data.message) || t('common.failed');
    }
}

async function tfStartEnroll() {
    tfState.busy = true;
    tfState.error = '';
    tfRender();
    try {
        const data = await tfPost('/api/auth/two-factor/enroll/start');
        if (!data) return;
        if (!data.success) {
            tfState.error = tfErrorText(data);
            return;
        }
        tfApplyEnrollStart(data, false);
    } catch (e) {
        tfState.error = t('common.failed') + ': ' + (e.message || e);
    } finally {
        tfState.busy = false;
        tfRender();
    }
}
window.tfStartEnroll = tfStartEnroll;

function tfApplyEnrollStart(data, replacing) {
    tfState.secret = data.secret || '';
    tfState.uri = data.otpauthUri || '';
    tfState.account = data.account || (currentUser && currentUser.username) || '';
    tfState.replacing = replacing;
    tfState.manualOpen = false;
    tfState.view = 'scan';
}

function tfGoVerify() {
    tfState.error = '';
    tfState.view = 'verify';
    tfRender();
}

function tfGoScan() {
    tfState.error = '';
    tfState.view = 'scan';
    tfRender();
}

async function tfConfirmEnroll() {
    const input = document.getElementById('tfCode');
    const code = input ? input.value.replace(/\s+/g, '') : '';
    if (!/^\d{6}$/.test(code)) {
        tfState.error = t('twoFactor.codeFormat');
        tfRender();
        return;
    }
    tfState.busy = true;
    tfState.error = '';
    tfRender();
    try {
        const data = await tfPost('/api/auth/two-factor/enroll/confirm', { code });
        if (!data) return;
        if (!data.success) {
            tfState.error = tfErrorText(data, true);
            if (data.code === 'two_factor.enroll_expired') tfState.view = tfState.replacing ? 'manage' : 'intro';
            return;
        }
        tfShowCodes(data.recoveryCodes);
    } catch (e) {
        tfState.error = t('common.failed') + ': ' + (e.message || e);
    } finally {
        tfState.busy = false;
        tfRender();
    }
}
window.tfConfirmEnroll = tfConfirmEnroll;

function tfShowCodes(codes) {
    tfState.codes = Array.isArray(codes) ? codes : [];
    tfState.codesSaved = false;
    tfState.view = 'codes';
}

function tfOnCodesSaved(checked) {
    tfState.codesSaved = !!checked;
    const btn = document.getElementById('tfFinishBtn');
    if (btn) btn.disabled = !checked;
}

function tfFinish() {
    if (!tfState.codesSaved) return;
    if (tfState.forced) {
        // 强制绑定完成：后端已清除标记，重新加载进入系统
        notifyAfterReload(t('twoFactor.enabledOk'), 'success');
        window.location.reload();
        return;
    }
    tfHost().innerHTML = '';
    notify(t('twoFactor.enabledOk'), 'success');
    tfRefreshCurrentUser();
}

async function tfRefreshCurrentUser() {
    try {
        const resp = await fetch('/api/auth/user');
        if (!resp.ok) return;
        const user = await resp.json();
        if (currentUser && user && user.twoFactor) {
            currentUser.twoFactor = user.twoFactor;
            updateTwoFactorMenuItem();
        }
    } catch (e) {
        console.warn('Refresh user info failed', e);
    }
}

function tfBeginAction(action) {
    tfState.action = action;
    tfState.error = '';
    tfState.view = 'confirm';
    tfRender();
}

function tfBackToManage() {
    tfState.error = '';
    tfState.view = 'manage';
    tfRender();
}

async function tfSubmitAction() {
    const input = document.getElementById('tfCurrentCode');
    const value = input ? input.value.trim() : '';
    if (!value) {
        tfState.error = t('twoFactor.confirmWithCode');
        tfRender();
        return;
    }
    // 6 位数字按动态码提交，其余按恢复码提交
    const compact = value.replace(/\s+/g, '');
    const body = /^\d{6}$/.test(compact) ? { code: compact } : { recoveryCode: value };
    const url = {
        regenerate: '/api/auth/two-factor/recovery-codes/regenerate',
        replace: '/api/auth/two-factor/replace',
        disable: '/api/auth/two-factor/disable'
    }[tfState.action];
    if (!url) return;
    tfState.busy = true;
    tfState.error = '';
    tfRender();
    try {
        const data = await tfPost(url, body);
        if (!data) return;
        if (!data.success) {
            tfState.error = tfErrorText(data);
            return;
        }
        if (tfState.action === 'regenerate') {
            tfShowCodes(data.recoveryCodes);
        } else if (tfState.action === 'replace') {
            tfApplyEnrollStart(data, true);
        } else {
            tfHost().innerHTML = '';
            notify(t('twoFactor.disabledOk'), 'success');
            tfRefreshCurrentUser();
        }
    } catch (e) {
        tfState.error = t('common.failed') + ': ' + (e.message || e);
    } finally {
        tfState.busy = false;
        if (document.querySelector('#twoFactorModalHost .modal')) tfRender();
    }
}
window.tfSubmitAction = tfSubmitAction;

async function tfRequestClose(event) {
    if (event && event.target !== event.currentTarget) return;
    if (tfState.forced) return;
    if (tfState.view === 'codes' && !tfState.codesSaved) {
        if (!await confirmDialog(t('twoFactor.closeConfirm'), { danger: true })) return;
    }
    tfHost().innerHTML = '';
    if (tfState.view === 'codes') tfRefreshCurrentUser();
}

async function tfCopy(text) {
    try {
        if (navigator.clipboard && window.isSecureContext) {
            await navigator.clipboard.writeText(text);
        } else {
            const ta = document.createElement('textarea');
            ta.value = text;
            ta.setAttribute('readonly', '');
            ta.style.position = 'fixed';
            ta.style.opacity = '0';
            document.body.appendChild(ta);
            ta.select();
            const ok = document.execCommand('copy');
            document.body.removeChild(ta);
            if (!ok) throw new Error('execCommand copy failed');
        }
        notify(t('twoFactor.copied'), 'success');
    } catch (e) {
        notify(t('twoFactor.copyFailed'), 'error');
    }
}

/** 恢复码在浏览器本地生成文本文件下载，不经过服务器 */
function tfDownloadCodes() {
    const account = tfState.account || (currentUser && currentUser.username) || '';
    const header = t('twoFactor.downloadHeader', { account: account, time: formatDate(new Date().toISOString()) });
    const blob = new Blob([header + '\n\n' + tfState.codes.join('\n') + '\n'], { type: 'text/plain;charset=utf-8' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = 'opsflow-recovery-codes' + (account ? '-' + account.replace(/[^A-Za-z0-9._-]/g, '_') : '') + '.txt';
    document.body.appendChild(a);
    a.click();
    document.body.removeChild(a);
    setTimeout(() => URL.revokeObjectURL(url), 1000);
}

Object.assign(window, {
    tfRequestClose, tfToggleManual, tfGoVerify, tfGoScan, tfOnCodesSaved, tfFinish,
    tfBeginAction, tfBackToManage, tfCopy, tfDownloadCodes
});
