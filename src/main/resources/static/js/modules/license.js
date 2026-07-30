// License 导入与状态展示

function escLicenseHtml(text) {
    return String(text == null ? '' : text)
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;');
}

function renderLicenseStatusCard(status) {
    const card = document.getElementById('licenseStatusCard');
    if (!card) return;
    const s = status || {};
    const features = Array.isArray(s.features) ? s.features : [];
    const valid = !!s.valid;
    const present = !!s.present;
    const badge = valid
        ? '<span style="color:#059669;font-weight:600;">有效</span>'
        : (present
            ? '<span style="color:#d97706;font-weight:600;">无效/已过期</span>'
            : '<span style="color:#6b7280;font-weight:600;">未导入</span>');
    const featureText = features.length
        ? features.map(f => `<code>${escLicenseHtml(f)}</code>`).join('、')
        : '-';
    card.innerHTML = `
        <div style="display:flex;justify-content:space-between;gap:12px;flex-wrap:wrap;align-items:flex-start;">
            <div>
                <div style="margin-bottom:6px;">状态：${badge}</div>
                <div style="color:#374151;font-size:13px;line-height:1.7;">
                    <div>客户：${escLicenseHtml(s.customer || '-')}</div>
                    <div>License ID：${escLicenseHtml(s.licenseId || '-')}</div>
                    <div>签发时间：${escLicenseHtml(s.issuedAt || '-')}</div>
                    <div>过期时间：${escLicenseHtml(s.expiresAt || '永久')}</div>
                    <div>功能：${featureText}</div>
                    <div>上线审批：${s.deployApprovalEnabled ? '已开通' : '未开通'}</div>
                </div>
            </div>
            <div style="color:#6b7280;font-size:12px;max-width:320px;">${escLicenseHtml(s.message || '')}</div>
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
        alert('无权限导入 License');
        return;
    }
    const textarea = document.getElementById('licenseImportText');
    const raw = (textarea && textarea.value || '').trim();
    if (!raw) {
        alert('请粘贴 License 内容或选择文件');
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
            alert(data.message || data.error || ('导入失败 HTTP ' + response.status));
            return;
        }
        if (typeof loadLicenseStatus === 'function') {
            await loadLicenseStatus();
        }
        if (typeof applyPermissionUI === 'function') {
            applyPermissionUI();
        }
        renderLicenseStatusCard(data);
        alert('License 导入成功');
    } catch (e) {
        alert('导入失败: ' + (e.message || e));
    }
}

async function clearLicenseFromForm() {
    if (typeof hasPermission === 'function' && !hasPermission('system:config')) {
        alert('无权限清除 License');
        return;
    }
    if (!confirm('确定清除已导入的 License？上线审批相关能力将立即不可用。')) {
        return;
    }
    try {
        const response = await fetch('/api/license', { method: 'DELETE' });
        const data = await response.json().catch(() => ({}));
        if (!response.ok) {
            alert(data.message || data.error || ('清除失败 HTTP ' + response.status));
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
        alert('已清除 License');
    } catch (e) {
        alert('清除失败: ' + (e.message || e));
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
