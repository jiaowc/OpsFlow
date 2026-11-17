// License 导入与状态展示（订阅 / 功能码）

function featureFlagLabel(code, enabled) {
    return `${escapeHtml(code)}：${enabled ? '已开通' : '未开通'}`;
}

function renderLicenseStatusCard(status) {
    const card = document.getElementById('licenseStatusCard');
    if (!card) return;
    const s = status || {};
    const features = Array.isArray(s.features) ? s.features : [];
    const valid = !!s.valid;
    const present = !!s.present;
    const inGrace = !!s.inGracePeriod;
    let badge;
    if (s.enforcementEnabled === false) {
        badge = '<span style="color:#059669;font-weight:600;">强校验已关闭（开发模式）</span>';
    } else if (valid && inGrace) {
        badge = '<span style="color:#d97706;font-weight:600;">宽限期内（请续费）</span>';
    } else if (valid) {
        badge = '<span style="color:#059669;font-weight:600;">订阅有效</span>';
    } else if (present) {
        badge = '<span style="color:#d97706;font-weight:600;">无效/已过期</span>';
    } else {
        badge = '<span style="color:#6b7280;font-weight:600;">未导入（Community）</span>';
    }
    const featureText = features.length
        ? features.map(f => `<code>${escapeHtml(f)}</code>`).join('、')
        : '-';
    const daysText = s.daysRemaining == null
        ? (s.expiresAt ? '-' : '永久/未设置')
        : (s.daysRemaining >= 0 ? `剩余 ${s.daysRemaining} 天` : `已过期 ${Math.abs(s.daysRemaining)} 天`);
    card.innerHTML = `
        <div style="display:flex;justify-content:space-between;gap:12px;flex-wrap:wrap;align-items:flex-start;">
            <div>
                <div style="margin-bottom:6px;">状态：${badge}</div>
                <div style="color:#374151;font-size:13px;line-height:1.7;">
                    <div>客户：${escapeHtml(s.customer || '-')}</div>
                    <div>计划：${escapeHtml(s.plan || '-')}</div>
                    <div>License ID：${escapeHtml(s.licenseId || '-')}</div>
                    <div>签发时间：${escapeHtml(s.issuedAt || '-')}</div>
                    <div>到期时间：${escapeHtml(s.expiresAt || '未设置')}</div>
                    <div>订阅余量：${escapeHtml(daysText)}</div>
                    <div>功能码：${featureText}</div>
                    <div>${featureFlagLabel('deploy_approval', s.deployApprovalEnabled)}</div>
                    <div>${featureFlagLabel('audit', s.auditEnabled)}</div>
                    <div>${featureFlagLabel('notify_integration', s.notifyIntegrationEnabled)}</div>
                    <div>${featureFlagLabel('prod_rollback', s.prodRollbackEnabled)}</div>
                    <div>${featureFlagLabel('branding', s.brandingEnabled)}</div>
                </div>
            </div>
            <div style="color:#6b7280;font-size:12px;max-width:320px;">${escapeHtml(s.message || '')}</div>
        </div>
    `;
}

async function loadLicensePage() {
    const status = typeof loadLicenseStatus === 'function'
        ? await loadLicenseStatus()
        : null;
    renderLicenseStatusCard(status || (typeof getLicenseStatus === 'function' ? getLicenseStatus() : {}));
}

async function importLicenseFromForm() {
    if (typeof hasPermission === 'function' && !hasPermission('system:config')) {
        notify('无权限导入 License');
        return;
    }
    const textarea = document.getElementById('licenseImportText');
    const raw = (textarea && textarea.value || '').trim();
    if (!raw) {
        notify('请粘贴 License 内容或选择文件');
        return;
    }
    try {
        const response = await fetch('/api/license/import', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ license: raw })
        });
        const data = await response.json().catch(() => ({}));
        if (!response.ok) {
            notify(data.message || data.error || ('导入失败 HTTP ' + response.status));
            return;
        }
        if (typeof loadLicenseStatus === 'function') {
            await loadLicenseStatus();
        }
        if (typeof applyPermissionUI === 'function') {
            applyPermissionUI();
        }
        renderLicenseStatusCard(data);
        if (typeof loadBrandingAdminPanel === 'function') {
            await loadBrandingAdminPanel();
        }
        if (typeof loadPublicBranding === 'function') {
            await loadPublicBranding();
        }
        notify('License 导入成功');
    } catch (e) {
        notify('导入失败: ' + (e.message || e));
    }
}

async function clearLicenseFromForm() {
    if (typeof hasPermission === 'function' && !hasPermission('system:config')) {
        notify('无权限清除 License');
        return;
    }
    if (!await confirmDialog('确定清除已导入的 License？商业功能（审批/审计/集成通知/生产回滚）将立即不可用。')) {
        return;
    }
    try {
        const response = await fetch('/api/license', { method: 'DELETE' });
        const data = await response.json().catch(() => ({}));
        if (!response.ok) {
            notify(data.message || data.error || ('清除失败 HTTP ' + response.status));
            return;
        }
        const textarea = document.getElementById('licenseImportText');
        if (textarea) textarea.value = '';
        if (typeof loadLicenseStatus === 'function') {
            await loadLicenseStatus();
        }
        if (typeof applyPermissionUI === 'function') {
            applyPermissionUI();
        }
        renderLicenseStatusCard(typeof getLicenseStatus === 'function' ? getLicenseStatus() : {});
        notify('已清除 License');
    } catch (e) {
        notify('清除失败: ' + (e.message || e));
    }
}

document.addEventListener('DOMContentLoaded', function () {
    const fileInput = document.getElementById('licenseFileInput');
    if (!fileInput) return;
    fileInput.addEventListener('change', function () {
        const file = fileInput.files && fileInput.files[0];
        if (!file) return;
        const reader = new FileReader();
        reader.onload = function () {
            const textarea = document.getElementById('licenseImportText');
            if (textarea) {
                textarea.value = String(reader.result || '');
            }
        };
        reader.readAsText(file);
    });
});

window.loadLicensePage = loadLicensePage;
window.importLicenseFromForm = importLicenseFromForm;
window.clearLicenseFromForm = clearLicenseFromForm;
