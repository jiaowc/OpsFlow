// LDAP 登录配置（社区版）

const LDAP_TEXT_FIELDS = ['url', 'bindDn', 'baseDn', 'userFilter', 'usernameAttribute', 'nameAttribute',
    'emailAttribute', 'connectTimeoutMs', 'readTimeoutMs'];
const LDAP_BOOL_FIELDS = ['enabled', 'startTls', 'skipCertVerify'];

function ldapForm() {
    return document.getElementById('ldapConfigForm');
}

async function loadLdapRoles(selected) {
    const select = document.getElementById('ldapDefaultRole');
    if (!select) return;
    let roles = [];
    try {
        const resp = await fetch('/api/role/list');
        if (resp.ok) {
            const data = await resp.json();
            roles = Array.isArray(data) ? data : (data && Array.isArray(data.records) ? data.records : []);
        }
    } catch (e) {
        roles = [];
    }
    const options = [('<option value="">' + t('ldap.form.noRole') + '</option>')];
    const codes = new Set();
    roles.forEach(r => {
        if (!r || !r.code) return;
        codes.add(r.code);
        options.push(`<option value="${escapeHtml(r.code)}">${escapeHtml(r.name || r.code)}（${escapeHtml(r.code)}）</option>`);
    });
    // 没有读角色列表的权限时，至少保留当前值
    if (selected && !codes.has(selected)) {
        options.push(`<option value="${escapeHtml(selected)}">${escapeHtml(selected)}</option>`);
    }
    select.innerHTML = options.join('');
    select.value = selected || '';
}

function fillLdapForm(cfg) {
    const form = ldapForm();
    if (!form || !cfg) return;
    LDAP_TEXT_FIELDS.forEach(name => {
        if (form.elements[name]) form.elements[name].value = cfg[name] != null ? cfg[name] : '';
    });
    LDAP_BOOL_FIELDS.forEach(name => {
        if (form.elements[name]) form.elements[name].checked = cfg[name] === true || cfg[name] === 'true';
    });
    form.elements.bindPassword.value = '';
    form.elements.bindPassword.placeholder = cfg.hasBindPassword ? t('ldap.form.passwordSet') : t('ldap.form.passwordUnset');
    form.elements.clearBindPassword.checked = false;
}

async function loadLdapPage() {
    const form = ldapForm();
    if (!form) return;
    showLdapResult(null);
    try {
        const resp = await fetch('/api/ldap/config');
        const cfg = await resp.json().catch(() => ({}));
        if (!resp.ok) {
            notify(cfg.message || (t('ldap.msg.loadFailed') + ' HTTP ' + resp.status));
            return;
        }
        fillLdapForm(cfg);
        await loadLdapRoles(cfg.defaultRole || '');
    } catch (e) {
        notify(t('ldap.msg.loadFailed') + ': ' + (e.message || e));
    }
}

function collectLdapForm() {
    const form = ldapForm();
    const body = {};
    LDAP_TEXT_FIELDS.forEach(name => {
        if (form.elements[name]) body[name] = form.elements[name].value.trim();
    });
    LDAP_BOOL_FIELDS.forEach(name => {
        if (form.elements[name]) body[name] = form.elements[name].checked;
    });
    body.defaultRole = form.elements.defaultRole.value;
    // 只在填写了新密码时提交，留空表示沿用已保存的
    if (form.elements.bindPassword.value) {
        body.bindPassword = form.elements.bindPassword.value;
    }
    body.clearBindPassword = form.elements.clearBindPassword.checked;
    return body;
}

function showLdapResult(result) {
    const box = document.getElementById('ldapTestResult');
    if (!box) return;
    if (!result) {
        box.style.display = 'none';
        box.textContent = '';
        return;
    }
    const ok = !!result.success;
    box.style.display = 'block';
    box.style.background = ok ? '#ecfdf5' : '#fef2f2';
    box.style.color = ok ? '#047857' : '#b91c1c';
    let text = (ok ? '✓ ' : '✗ ') + (result.message || (ok ? t('ldap.result.ok') : t('ldap.result.failed')));
    if (result.user) {
        text += `\nDN: ${result.user.dn || '-'}\n${t('ldap.result.username')}: ${result.user.username || '-'}`
            + `\n${t('ldap.result.realName')}: ${result.user.realName || '-'}\n${t('ldap.result.email')}: ${result.user.email || '-'}`;
    }
    box.textContent = text;
}

async function testLdapConnection() {
    const body = collectLdapForm();
    body.testUsername = (document.getElementById('ldapTestUsername').value || '').trim();
    body.testPassword = document.getElementById('ldapTestPassword').value || '';
    showLdapResult({ success: true, message: t('ldap.msg.testing') });
    try {
        const resp = await fetch('/api/ldap/test', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(body)
        });
        const data = await resp.json().catch(() => ({}));
        if (!resp.ok) {
            showLdapResult({ success: false, message: data.message || (t('ldap.msg.testFailed') + ' HTTP ' + resp.status) });
            return;
        }
        showLdapResult(data);
    } catch (e) {
        showLdapResult({ success: false, message: t('ldap.msg.testFailed') + ': ' + (e.message || e) });
    } finally {
        document.getElementById('ldapTestPassword').value = '';
    }
}

async function saveLdapConfig(event) {
    if (event) event.preventDefault();
    const form = ldapForm();
    if (form.elements.skipCertVerify.checked && form.elements.enabled.checked
        && !await confirmDialog(t('ldap.msg.insecureConfirm'))) {
        return;
    }
    try {
        const resp = await fetch('/api/ldap/config', {
            method: 'PUT',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(collectLdapForm())
        });
        const data = await resp.json().catch(() => ({}));
        if (!resp.ok) {
            notify(data.message || (t('common.saveFailed') + ' HTTP ' + resp.status));
            return;
        }
        fillLdapForm(data);
        await loadLdapRoles(data.defaultRole || '');
        notify(t('ldap.msg.saved'));
    } catch (e) {
        notify((t('common.saveFailed') + ': ') + (e.message || e));
    }
}

window.loadLdapPage = loadLdapPage;
// 登录方式页的 LDAP 页签：切到该页签（含进入页面时默认激活）时加载配置
if (typeof registerConfigTabLoader === 'function') {
    registerConfigTabLoader('ldap', loadLdapPage);
}
window.testLdapConnection = testLdapConnection;
window.saveLdapConfig = saveLdapConfig;
