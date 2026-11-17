// 企业品牌：登录页 / 控制台 Logo（商业功能 branding）

function applyBrandingToDom(branding) {
    const data = branding || {};
    const custom = !!data.custom && !!data.logoUrl;
    const title = (data.title && String(data.title).trim()) || 'OPSFLOW';

    document.querySelectorAll('[data-brand-title]').forEach(el => {
        el.textContent = title;
    });

    document.querySelectorAll('[data-brand-logo]').forEach(img => {
        if (custom) {
            img.src = data.logoUrl;
            img.alt = title;
            img.style.display = '';
            img.removeAttribute('hidden');
        } else {
            img.removeAttribute('src');
            img.style.display = 'none';
            img.setAttribute('hidden', '');
        }
    });

    document.querySelectorAll('[data-brand-text]').forEach(el => {
        // 有自定义 Logo 时仍可显示标题文字（偏小）；无 Logo 时显示主标题
        if (custom) {
            el.classList.add('brand-text-with-logo');
        } else {
            el.classList.remove('brand-text-with-logo');
        }
        el.textContent = title;
    });
}

async function loadPublicBranding() {
    try {
        const response = await fetch('/api/branding/public');
        if (!response.ok) {
            applyBrandingToDom({ custom: false, title: 'OPSFLOW' });
            return null;
        }
        const data = await response.json();
        applyBrandingToDom(data);
        return data;
    } catch (e) {
        console.warn('Load branding failed', e);
        applyBrandingToDom({ custom: false, title: 'OPSFLOW' });
        return null;
    }
}

async function loadBrandingAdminPanel() {
    const panel = document.getElementById('brandingAdminPanel');
    if (!panel) return;
    const licensed = typeof hasFeature !== 'function' || hasFeature('branding');
    const lockedHint = document.getElementById('brandingLockedHint');
    const editor = document.getElementById('brandingEditor');
    if (lockedHint) lockedHint.style.display = licensed ? 'none' : '';
    if (editor) editor.style.display = licensed ? '' : 'none';
    if (!licensed) return;

    try {
        const response = await fetch('/api/branding');
        if (!response.ok) return;
        const data = await response.json();
        const preview = document.getElementById('brandingLogoPreview');
        const titleInput = document.getElementById('brandingTitleInput');
        const statusEl = document.getElementById('brandingStatusText');
        if (titleInput) {
            titleInput.value = data.title || '';
        }
        if (preview) {
            if (data.custom && data.logoUrl) {
                preview.src = data.logoUrl;
                preview.style.display = '';
            } else {
                preview.removeAttribute('src');
                preview.style.display = 'none';
            }
        }
        if (statusEl) {
            statusEl.textContent = data.custom
                ? ((typeof t === 'function' ? t('branding.uploaded') : '已上传自定义 Logo')
                    + (data.updatedAt ? (' · ' + data.updatedAt) : ''))
                : (typeof t === 'function' ? t('branding.empty') : '尚未上传 Logo，将显示默认品牌名');
        }
    } catch (e) {
        console.warn('Load branding admin failed', e);
    }
}

async function uploadBrandingLogo() {
    if (typeof hasFeature === 'function' && !hasFeature('branding')) {
        notify(typeof t === 'function' ? t('branding.needLicense') : '企业品牌为 Pro 功能，请导入含 branding 的 License');
        return;
    }
    const input = document.getElementById('brandingLogoFile');
    if (!input || !input.files || !input.files[0]) {
        notify(typeof t === 'function' ? t('branding.pickFile') : '请选择 Logo 文件');
        return;
    }
    const form = new FormData();
    form.append('file', input.files[0]);
    try {
        const response = await fetch('/api/branding/logo', { method: 'POST', body: form });
        const data = await response.json().catch(() => ({}));
        if (!response.ok) {
            notify(data.message || data.error || '上传失败');
            return;
        }
        await loadBrandingAdminPanel();
        await loadPublicBranding();
        notify(typeof t === 'function' ? t('branding.uploadOk') : 'Logo 已更新');
    } catch (e) {
        notify((typeof t === 'function' ? t('common.failed') : '操作失败') + ': ' + (e.message || e));
    }
}

async function saveBrandingTitle() {
    if (typeof hasFeature === 'function' && !hasFeature('branding')) {
        notify(typeof t === 'function' ? t('branding.needLicense') : '企业品牌为 Pro 功能，请导入含 branding 的 License');
        return;
    }
    const titleInput = document.getElementById('brandingTitleInput');
    const title = titleInput ? titleInput.value.trim() : '';
    try {
        const response = await fetch('/api/branding/title', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ title })
        });
        const data = await response.json().catch(() => ({}));
        if (!response.ok) {
            notify(data.message || data.error || '保存失败');
            return;
        }
        await loadBrandingAdminPanel();
        await loadPublicBranding();
        notify(typeof t === 'function' ? t('branding.titleOk') : '品牌名称已保存');
    } catch (e) {
        notify((typeof t === 'function' ? t('common.failed') : '操作失败') + ': ' + (e.message || e));
    }
}

async function clearBrandingLogo() {
    if (typeof hasFeature === 'function' && !hasFeature('branding')) {
        notify(typeof t === 'function' ? t('branding.needLicense') : '企业品牌为 Pro 功能，请导入含 branding 的 License');
        return;
    }
    if (!await confirmDialog(typeof t === 'function' ? t('branding.clearConfirm') : '确定清除自定义 Logo？')) {
        return;
    }
    try {
        const response = await fetch('/api/branding/logo', { method: 'DELETE' });
        const data = await response.json().catch(() => ({}));
        if (!response.ok) {
            notify(data.message || data.error || '清除失败');
            return;
        }
        const input = document.getElementById('brandingLogoFile');
        if (input) input.value = '';
        await loadBrandingAdminPanel();
        await loadPublicBranding();
        notify(typeof t === 'function' ? t('branding.clearOk') : '已清除自定义 Logo');
    } catch (e) {
        notify((typeof t === 'function' ? t('common.failed') : '操作失败') + ': ' + (e.message || e));
    }
}

window.loadPublicBranding = loadPublicBranding;
window.loadBrandingAdminPanel = loadBrandingAdminPanel;
window.uploadBrandingLogo = uploadBrandingLogo;
window.saveBrandingTitle = saveBrandingTitle;
window.clearBrandingLogo = clearBrandingLogo;
