// ============================================
// 安全设置页：两步验证策略（页签 two-factor）
// 策略存在系统配置 security.twoFactorPolicy（off / optional / required），统计来自 /api/security/two-factor/summary
// ============================================

const SECURITY_POLICIES = ['off', 'optional', 'required'];
let securityLoadedPolicy = null;
let securitySummary = null;

async function loadSecurityTwoFactorTab() {
    const summaryEl = document.getElementById('securityTwoFactorSummary');
    const form = document.getElementById('securityTwoFactorForm');
    if (!form) return;
    try {
        const resp = await fetch('/api/security/two-factor/summary');
        if (!resp.ok) throw new Error('HTTP ' + resp.status);
        securitySummary = await resp.json();
    } catch (e) {
        console.error('Load two-factor summary error:', e);
        securitySummary = null;
        if (summaryEl) summaryEl.textContent = t('security.twoFactor.summaryFailed');
        return;
    }
    securityLoadedPolicy = SECURITY_POLICIES.includes(securitySummary.policy) ? securitySummary.policy : 'off';
    form.querySelectorAll('input[name="twoFactorPolicy"]').forEach(input => {
        input.checked = input.value === securityLoadedPolicy;
    });
    if (summaryEl) {
        summaryEl.textContent = t('security.twoFactor.summary', {
            enrolled: Number(securitySummary.enrolled) || 0,
            total: Number(securitySummary.eligible) || 0
        });
    }
}
window.loadSecurityTwoFactorTab = loadSecurityTwoFactorTab;

async function saveSecurityTwoFactor(event) {
    if (event) event.preventDefault();
    const form = document.getElementById('securityTwoFactorForm');
    const checked = form && form.querySelector('input[name="twoFactorPolicy"]:checked');
    if (!checked) return;
    const policy = checked.value;
    if (policy === securityLoadedPolicy) {
        notify(t('security.policy.unchanged'), 'info');
        return;
    }
    if (policy === 'required') {
        const total = securitySummary ? Number(securitySummary.eligible) || 0 : 0;
        const enrolled = securitySummary ? Number(securitySummary.enrolled) || 0 : 0;
        if (!await confirmDialog(t('security.policy.confirmRequired', { n: Math.max(total - enrolled, 0) }))) return;
    } else if (policy === 'off') {
        if (!await confirmDialog(t('security.policy.confirmOff'))) return;
    }
    const btn = document.getElementById('securityTwoFactorSaveBtn');
    if (btn) btn.disabled = true;
    try {
        const resp = await fetch('/api/system/config/batch', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ configType: 'security', values: { twoFactorPolicy: policy } })
        });
        if (!resp.ok) {
            const data = await resp.json().catch(() => ({}));
            notify(t('common.saveFailed') + ': ' + (data.message || data.error || resp.status), 'error');
            return;
        }
        notify(t('security.policy.saved'), 'success');
        if (currentUser && currentUser.twoFactor) {
            currentUser.twoFactor.policy = policy;
            if (typeof updateTwoFactorMenuItem === 'function') updateTwoFactorMenuItem();
        }
        await loadSecurityTwoFactorTab();
    } catch (e) {
        notify(t('common.saveFailed') + ': ' + (e.message || e), 'error');
    } finally {
        if (btn) btn.disabled = false;
    }
}
window.saveSecurityTwoFactor = saveSecurityTwoFactor;

if (typeof registerConfigTabLoader === 'function') {
    registerConfigTabLoader('two-factor', loadSecurityTwoFactorTab);
}
