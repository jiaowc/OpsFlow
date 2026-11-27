// ============================================
// 核心模块 - 全局变量、认证、菜单切换、工具函数
// ============================================

// 全局变量
let currentUser = null;
let mockEnvs = [];
let mockNodes = [];
let mockServices = [];
let pipelineStepCounter = 0;
let pipelineParamCounter = 0;

// 未登录时跳到登录页，带上当前地址（next），登录后回到原页面（如通知里的 Job 详情深链）
function redirectToLogin() {
    const here = window.location.pathname + window.location.search + window.location.hash;
    const next = here && here !== '/' && !here.startsWith('/index.html') ? '?next=' + encodeURIComponent(here) : '';
    window.location.href = '/index.html' + next;
}

// 检查登录状态；返回 true 表示已登录且可以继续初始化页面
async function checkAuth() {
    try {
        const response = await fetch('/api/auth/user');
        if (!response.ok) {
            redirectToLogin();
            return false;
        }
        const user = await response.json();
        if (!user.authenticated) {
            redirectToLogin();
            return false;
        }
        await loadUserInfo(user);
        // 需要强制改密或强制绑定两步验证时其他 API 均被后端 403，不再继续初始化
        return !user.mustChangePassword && !(user.twoFactor && user.twoFactor.mustEnroll);
    } catch (error) {
        redirectToLogin();
        return false;
    }
}

window.checkAuth = checkAuth;

// 是否拥有权限（* 或任一码）
function hasPermission(...codes) {
    if (!currentUser || !currentUser.authenticated) {
        return false;
    }
    const perms = currentUser.permissions || [];
    if (perms.indexOf('*') >= 0) {
        return true;
    }
    if (!codes || codes.length === 0) {
        return true;
    }
    for (let i = 0; i < codes.length; i++) {
        const code = codes[i];
        if (!code) continue;
        if (String(code).indexOf(',') >= 0) {
            const parts = String(code).split(',').map(s => s.trim()).filter(Boolean);
            if (parts.some(p => perms.indexOf(p) >= 0)) {
                return true;
            }
        } else if (perms.indexOf(code) >= 0) {
            return true;
        }
    }
    return false;
}

window.hasPermission = hasPermission;

/** 版本信息（由 /api/edition 填充）：社区版 features 为空，企业版为当前 License 开通的功能码 */
let editionInfo = { edition: 'community', features: [] };

function hasFeature(...codes) {
    const features = (editionInfo && editionInfo.features) || [];
    if (!codes || codes.length === 0) {
        return true;
    }
    for (let i = 0; i < codes.length; i++) {
        const code = codes[i];
        if (!code) continue;
        const parts = String(code).split(',').map(s => s.trim()).filter(Boolean);
        if (parts.some(p => features.indexOf(p) >= 0)) {
            return true;
        }
    }
    return false;
}

window.hasFeature = hasFeature;
window.getEditionInfo = function () { return editionInfo; };

function applyFeatureUI() {
    document.querySelectorAll('[data-feature]').forEach(el => {
        const required = el.getAttribute('data-feature');
        if (!hasFeature(required)) {
            el.style.display = 'none';
            return;
        }
        const perm = el.getAttribute('data-permission');
        if (perm) {
            el.style.display = hasPermission(perm) ? '' : 'none';
        } else {
            el.style.display = '';
        }
    });
    updateConfigTabBars();
}

window.applyFeatureUI = applyFeatureUI;

/** 页签按钮对当前用户是否可见（权限和功能开关都已应用到 style.display） */
function isTabButtonVisible(btn) {
    return !!btn && btn.style.display !== 'none';
}

function visibleConfigTabs(section) {
    return Array.from(section.querySelectorAll('.system-config-tabs > .tab-buttons > .tab-btn[data-tab]'))
        .filter(isTabButtonVisible);
}

/**
 * 配置页（.system-config-tabs）只剩一个可见页签时隐藏页签栏；当前激活的页签变得不可见时，
 * 正在显示的页面改为激活第一个可见页签。在 applyFeatureUI 末尾调用，权限和功能变化后都会走到。
 */
function updateConfigTabBars() {
    document.querySelectorAll('.content-section').forEach(section => {
        const bar = section.querySelector('.system-config-tabs > .tab-buttons');
        if (!bar) return;
        const visible = visibleConfigTabs(section);
        bar.style.display = visible.length <= 1 ? 'none' : '';
        const active = bar.querySelector(':scope > .tab-btn.active');
        if (section.classList.contains('active') && active && !isTabButtonVisible(active)
                && visible.length && typeof showConfigTab === 'function') {
            showConfigTab(visible[0].getAttribute('data-tab'), visible[0]);
        }
    });
    // 带 data-tab-access 的菜单不自带权限，页面里有可见页签就显示
    document.querySelectorAll('.menu-item[data-tab-access]').forEach(item => {
        item.style.display = hasVisibleConfigTab(item.dataset.section) ? '' : 'none';
    });
    updateMenuGroupVisibility();
}

/** 配置页里是否还有当前用户可见的页签（页面不存在或没有页签栏时按 false） */
function hasVisibleConfigTab(sectionId) {
    const section = document.getElementById(sectionId + '-section');
    return !!section && visibleConfigTabs(section).length > 0;
}

/** 侧栏分组里的子菜单全部隐藏时，整组（含组名）一起隐藏 */
function updateMenuGroupVisibility() {
    const configGroup = document.querySelector('.menu-group');
    if (!configGroup) return;
    const anyVisible = Array.from(configGroup.querySelectorAll('.menu-item-sub'))
        .some(item => item.style.display !== 'none');
    configGroup.style.display = anyVisible ? '' : 'none';
}

window.updateConfigTabBars = updateConfigTabBars;

/** 重新读取版本信息并刷新功能可见性；企业版导入或清除 License 后也调用它 */
async function loadEditionInfo() {
    try {
        const response = await fetch('/api/edition');
        if (response.ok) {
            const data = await response.json();
            editionInfo = {
                edition: (data && data.edition) || 'community',
                features: Array.isArray(data && data.features) ? data.features : []
            };
        }
    } catch (e) {
        console.warn('Load edition failed', e);
    }
    applyFeatureUI();
    // 扩展页面（如企业品牌）可监听此事件，按新开通的功能刷新自己
    try {
        window.dispatchEvent(new CustomEvent('opsflow:editionchange', { detail: editionInfo }));
    } catch (e) {
        console.warn('editionchange event failed', e);
    }
    return editionInfo;
}

window.loadEditionInfo = loadEditionInfo;

function applyPermissionUI() {
    document.querySelectorAll('[data-permission]').forEach(el => {
        const required = el.getAttribute('data-permission');
        const allowed = hasPermission(required);
        el.style.display = allowed ? '' : 'none';
        if (el.classList && el.classList.contains('menu-item')) {
            // keep layout consistent
        }
    });
    updateMenuGroupVisibility();
    applyFeatureUI();
}

window.applyPermissionUI = applyPermissionUI;

// 加载用户信息；preloaded 为 checkAuth 已取到的用户对象，传入时不再重复请求
async function loadUserInfo(preloaded) {
    try {
        const response = preloaded ? null : await fetch('/api/auth/user');
        if (preloaded || response.ok) {
            const user = preloaded || await response.json();
            currentUser = user;
            document.getElementById('username').textContent = user.username || t('header.user');
            const changePwdItem = document.getElementById('changePasswordMenuItem');
            if (changePwdItem) {
                changePwdItem.style.display = user.localUser === false ? 'none' : '';
            }
            if (typeof updateTwoFactorMenuItem === 'function') {
                updateTwoFactorMenuItem();
            }
            if (user.mustChangePassword) {
                // 弱口令登录：其他 API 均被后端 403 拦截，先强制改密（改完重新加载后再处理两步验证）
                showChangePasswordModal(true);
                return;
            }
            const twoFactor = user.twoFactor || {};
            if (twoFactor.mustEnroll) {
                // 管理员要求全员两步验证且本账号未绑定：其他 API 均被后端 403 拦截，先完成绑定
                // 弹窗背后的侧栏先按权限收起，避免露出没有权限的菜单
                applyPermissionUI();
                if (typeof openTwoFactorModal === 'function') openTwoFactorModal(true);
                return;
            }
            if (twoFactor.recoveryCodeUsed) {
                // 本次用恢复码登录：提示剩余数量（一次性标记，后端读取后即清除）
                notify(t('twoFactor.recoveryUsed', { n: Number(twoFactor.recoveryCodesLeft) || 0 }), 'warning');
            }
            if (typeof loadUiExtensions === 'function') {
                await loadUiExtensions();
            }
            await loadEditionInfo();
            applyPermissionUI();
            await refreshProjectSwitcher();
        }
    } catch (error) {
        console.error('Load user info error:', error);
    }
}

// ============================================
// 项目列表缓存（服务管理页筛选用；不再放顶栏全局切换）
// ============================================
const PROJECT_STORAGE_KEY = 'opsflow.projectId';
let cachedProjectOptions = null;

/** 兼容旧逻辑：顶栏项目筛选已移除，列表默认不过滤项目 */
function getCurrentProjectId() {
    return '';
}
window.getCurrentProjectId = getCurrentProjectId;

/** 当前用户可见项目（含 myRole），force=true 时重新拉取 */
async function loadProjectOptions(force) {
    if (cachedProjectOptions && !force) {
        return cachedProjectOptions;
    }
    try {
        const resp = await fetch('/api/project/list');
        cachedProjectOptions = resp.ok ? ((await resp.json()) || []) : [];
    } catch (e) {
        cachedProjectOptions = [];
    }
    return cachedProjectOptions;
}
window.loadProjectOptions = loadProjectOptions;

async function refreshServiceProjectFilter() {
    const select = document.getElementById('serviceProjectFilter');
    const projects = await loadProjectOptions(true);
    // 清理历史顶栏筛选残留，避免影响其它模块
    localStorage.removeItem(PROJECT_STORAGE_KEY);
    if (!select) return;
    const prev = select.value || '';
    const allLabel = t('header.allProjects');
    const keep = prev && projects.some(p => String(p.id) === prev) ? prev : '';
    select.innerHTML = `<option value="">${escapeHtml(allLabel)}</option>` + projects.map(p =>
        `<option value="${p.id}"${String(p.id) === keep ? ' selected' : ''}>${escapeHtml(p.name)}</option>`
    ).join('');
}
window.refreshServiceProjectFilter = refreshServiceProjectFilter;
// 兼容项目管理保存后刷新
window.refreshProjectSwitcher = refreshServiceProjectFilter;

// 退出登录
async function logout() {
    try {
        await fetch('/api/auth/logout', { method: 'POST' });
    } catch (error) {
        console.error('Logout error:', error);
    }
    localStorage.removeItem('token');
    window.location.href = '/index.html';
}

// 修改密码弹窗：force=true 时为弱口令强制改密（无关闭/取消，独立容器，不受 closeModal 影响）
function showChangePasswordModal(force) {
    const menu = document.getElementById('userMenu');
    if (menu) menu.classList.remove('open');
    let host = document.getElementById('changePasswordModalHost');
    if (!host) {
        host = document.createElement('div');
        host.id = 'changePasswordModalHost';
        document.body.appendChild(host);
    }
    host.innerHTML = `
        <div class="modal-overlay"${force ? '' : ' onclick="closeChangePasswordModal(event)"'}>
            <div class="modal" onclick="event.stopPropagation()">
                <div class="modal-header">
                    <h3>${t(force ? 'password.forceTitle' : 'password.title')}</h3>
                    ${force ? '' : '<button class="modal-close" onclick="closeChangePasswordModal()">&times;</button>'}
                </div>
                <form class="modal-body" novalidate onsubmit="event.preventDefault(); submitChangePassword();">
                    <button type="submit" class="modal-implicit-submit" tabindex="-1" aria-hidden="true"></button>
                    ${force ? `<p style="margin:0 0 12px;color:#d9534f;">${t('password.forceHint')}</p>` : ''}
                    <div class="form-item">
                        <label>${t('password.old')}${REQUIRED_MARK}</label>
                        <input type="password" id="cpOldPassword" autocomplete="current-password" required>
                    </div>
                    <div class="form-item">
                        <label>${t('password.new')}${REQUIRED_MARK}</label>
                        <input type="password" id="cpNewPassword" autocomplete="new-password" placeholder="${t('password.newPlaceholder')}" required>
                    </div>
                    <div class="form-item">
                        <label>${t('password.confirm')}${REQUIRED_MARK}</label>
                        <input type="password" id="cpConfirmPassword" autocomplete="new-password" required>
                    </div>
                    <div id="cpError" style="color:#d9534f;min-height:18px;"></div>
                </form>
                <div class="modal-footer">
                    ${force ? `<button class="btn-secondary" onclick="logout()">${t('header.logout')}</button>` : `<button class="btn-secondary" onclick="closeChangePasswordModal()">${t('common.cancel')}</button>`}
                    <button class="btn-primary" id="cpSubmitBtn" onclick="submitChangePassword()">${t('password.submit')}</button>
                </div>
            </div>
        </div>
    `;
    const first = document.getElementById('cpOldPassword');
    if (first) first.focus();
}

function closeChangePasswordModal(event) {
    if (event && event.target !== event.currentTarget) return;
    const host = document.getElementById('changePasswordModalHost');
    if (host) host.innerHTML = '';
}

async function submitChangePassword() {
    const oldPassword = document.getElementById('cpOldPassword').value;
    const newPassword = document.getElementById('cpNewPassword').value;
    const confirmPassword = document.getElementById('cpConfirmPassword').value;
    const errorEl = document.getElementById('cpError');
    const btn = document.getElementById('cpSubmitBtn');
    // 回车和点按钮都会走到这里，提交中不重复发请求
    if (btn.disabled) return;
    errorEl.textContent = '';
    if (!oldPassword || !newPassword) {
        errorEl.textContent = t('password.errEmpty');
        return;
    }
    if (newPassword.length < 8) {
        errorEl.textContent = t('password.errTooShort');
        return;
    }
    if (newPassword !== confirmPassword) {
        errorEl.textContent = t('password.errMismatch');
        return;
    }
    btn.disabled = true;
    try {
        const resp = await fetch('/api/auth/change-password', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ oldPassword, newPassword })
        });
        const data = await resp.json().catch(() => ({}));
        if (resp.ok && data.success) {
            notifyAfterReload(t('password.ok'), 'success');
            window.location.reload();
            return;
        }
        errorEl.textContent = data.message || t('password.failed');
    } catch (error) {
        errorEl.textContent = t('password.failedWith', { reason: (error && error.message) || t('common.networkError') });
    } finally {
        btn.disabled = false;
    }
}

window.showChangePasswordModal = showChangePasswordModal;
window.closeChangePasswordModal = closeChangePasswordModal;
window.submitChangePassword = submitChangePassword;

// 顶栏用户菜单（退出登录收在里面）
function toggleUserMenu() {
    const menu = document.getElementById('userMenu');
    if (menu) {
        menu.classList.toggle('open');
    }
}
window.toggleUserMenu = toggleUserMenu;

// 用捕获阶段监听：站内信铃铛会 stopPropagation，冒泡阶段收不到它的点击
document.addEventListener('click', function (e) {
    const menu = document.getElementById('userMenu');
    if (menu && menu.classList.contains('open') && !menu.contains(e.target)) {
        menu.classList.remove('open');
    }
}, true);

// 页面路由：核心页面来自 js/routes.js，扩展页面由 js/ui-extensions.js 调 registerSection 登记
const SECTION_TITLE_KEYS = {};
const SECTION_ROUTES = {};
const ROUTE_SECTIONS = {};
/** 进入页面时的动作（section -> 全局函数名，或 { defaultTab }） */
const SECTION_LOADERS = {};
const CORE_ROUTES = window.OPSFLOW_CORE_ROUTES || [];
const HOME_SECTION = (CORE_ROUTES.find(r => r.home) || CORE_ROUTES[0] || { section: 'statistics' }).section;

function getSectionTitle(sectionId) {
    const key = SECTION_TITLE_KEYS[sectionId];
    if (key && typeof t === 'function') {
        return t(key);
    }
    return key || sectionId || '';
}

// 兼容旧引用
const SECTION_TITLES = new Proxy({}, {
    get(_target, prop) {
        if (typeof prop !== 'string') return undefined;
        return getSectionTitle(prop);
    }
});

/**
 * 登记一个页面：核心页面在下面按 js/routes.js 逐个登记，扩展页面由 js/ui-extensions.js 按
 * /api/ui/extensions 的菜单声明登记。不允许覆盖已有的 section 或路径。
 * def：{ section, path, labelKey, loader, defaultTab, aliases }
 */
function registerSection(def) {
    if (!def || !def.section || !def.path) {
        throw new Error('registerSection: section/path required');
    }
    const paths = [def.path].concat(def.aliases || []);
    if (SECTION_ROUTES[def.section] || paths.some(p => ROUTE_SECTIONS[p])) {
        throw new Error('registerSection: section or path already exists: ' + def.section + ' ' + paths.join(','));
    }
    SECTION_ROUTES[def.section] = def.path;
    paths.forEach(p => { ROUTE_SECTIONS[p] = def.section; });
    if (def.labelKey) SECTION_TITLE_KEYS[def.section] = def.labelKey;
    if (def.defaultTab) {
        SECTION_LOADERS[def.section] = { defaultTab: def.defaultTab };
    } else if (def.loader) {
        SECTION_LOADERS[def.section] = def.loader;
    }
}

function unregisterSection(section) {
    Object.keys(ROUTE_SECTIONS).forEach(p => {
        if (ROUTE_SECTIONS[p] === section) delete ROUTE_SECTIONS[p];
    });
    delete SECTION_ROUTES[section];
    delete SECTION_TITLE_KEYS[section];
    delete SECTION_LOADERS[section];
}

CORE_ROUTES.forEach(r => registerSection({
    section: r.section,
    path: r.path,
    labelKey: r.titleKey,
    loader: r.loader,
    defaultTab: r.defaultTab,
    aliases: r.aliases
}));

window.registerSection = registerSection;
window.unregisterSection = unregisterSection;

let suppressHistoryUpdate = false;

function getPathForSection(sectionId) {
    return SECTION_ROUTES[sectionId] || SECTION_ROUTES[HOME_SECTION];
}

/** 地址里的页签（/<path>#<tab>），没有时为空串 */
function getTabFromHash() {
    const hash = (window.location.hash || '').replace(/^#/, '');
    try {
        return decodeURIComponent(hash);
    } catch (e) {
        return '';
    }
}

function getSectionFromPath() {
    const path = window.location.pathname.replace(/\/$/, '') || '/';
    return ROUTE_SECTIONS[path] || HOME_SECTION;
}

function initSectionFromUrl() {
    const sectionId = getSectionFromPath();
    const menuItem = document.querySelector(`.menu-item[data-section="${sectionId}"]`);
    suppressHistoryUpdate = true;
    showSection(sectionId, menuItem, { updateHistory: false, tab: getTabFromHash() });
    suppressHistoryUpdate = false;

    const active = document.querySelector('.content-section.active');
    const shownSection = active ? active.id.replace(/-section$/, '') : sectionId;
    const expectedPath = getPathForSection(shownSection);
    if (window.location.pathname !== expectedPath) {
        history.replaceState({ section: shownSection }, '', expectedPath);
    }
    document.title = (SECTION_TITLES[shownSection] || t('app.console')) + ' - OPSFLOW';
    document.documentElement.removeAttribute('data-initial-section');
}

function bindMenuNavigation() {
    // 事件委托：扩展动态插入的菜单项同样生效
    document.addEventListener('click', function(e) {
        const item = e.target.closest('.menu-item[data-section]');
        if (!item) {
            return;
        }
        if (e.metaKey || e.ctrlKey || e.shiftKey || e.button === 1) {
            return;
        }
        e.preventDefault();
        showSection(item.dataset.section, item);
    });

    document.addEventListener('click', function(e) {
        const link = e.target.closest('a[href]');
        if (!link || link.classList.contains('menu-item')) {
            return;
        }
        const href = link.getAttribute('href') || '';
        const hashAt = href.indexOf('#');
        const path = hashAt >= 0 ? href.slice(0, hashAt) : href;
        const sectionId = ROUTE_SECTIONS[path];
        if (!sectionId) {
            return;
        }
        if (e.metaKey || e.ctrlKey || e.shiftKey || e.button === 1) {
            return;
        }
        e.preventDefault();
        const menuItem = document.querySelector(`.menu-item[data-section="${sectionId}"]`);
        const tab = hashAt >= 0 ? href.slice(hashAt + 1) : '';
        showSection(sectionId, menuItem, { tab });
    });

    window.addEventListener('popstate', function(e) {
        const sectionId = (e.state && e.state.section) || getSectionFromPath();
        const menuItem = document.querySelector(`.menu-item[data-section="${sectionId}"]`);
        suppressHistoryUpdate = true;
        showSection(sectionId, menuItem, { updateHistory: false, tab: getTabFromHash() });
        suppressHistoryUpdate = false;
    });

    // 同一页面里改地址栏的 #<tab>：切到对应页签（不可见或不存在时回落到默认页签）
    window.addEventListener('hashchange', function() {
        const active = document.querySelector('.content-section.active');
        if (!active || !active.querySelector('.system-config-tabs')) {
            return;
        }
        const sectionId = active.id.replace(/-section$/, '');
        const loader = SECTION_LOADERS[sectionId];
        activateConfigSectionDefault(sectionId, loader && loader.defaultTab, getTabFromHash());
    });
}

/** 页面的菜单声明了 data-feature 而当前未开通时，返回该功能码 */
function lockedFeatureOf(sectionId) {
    const menuItem = document.querySelector(`.menu-item[data-section="${sectionId}"]`);
    const feature = menuItem && menuItem.getAttribute('data-feature');
    return feature && !hasFeature(feature) ? feature : null;
}

function licenseFeatureMessage(feature) {
    if (typeof t !== 'function') {
        return '该功能为企业版功能，请导入有效 License';
    }
    const key = 'license.feature.' + feature;
    const text = t(key);
    return text && text !== key ? text : t('license.feature.generic');
}

/**
 * 当前用户能否进入该页面：以左侧菜单项的 data-permission / data-feature 为准；
 * 带 data-tab-access 的菜单看页面里有没有可见页签。
 */
function canAccessSection(sectionId) {
    const menuItem = document.querySelector(`.menu-item[data-section="${sectionId}"]`);
    if (!menuItem) {
        return true;
    }
    if (menuItem.hasAttribute('data-tab-access') && !hasVisibleConfigTab(sectionId)) {
        return false;
    }
    const perm = menuItem.getAttribute('data-permission');
    const feature = menuItem.getAttribute('data-feature');
    return (!perm || hasPermission(perm)) && (!feature || hasFeature(feature));
}

function firstAccessibleSection() {
    const items = document.querySelectorAll('.menu-item[data-section]');
    for (let i = 0; i < items.length; i++) {
        const id = items[i].dataset.section;
        if (canAccessSection(id) && items[i].style.display !== 'none') {
            return id;
        }
    }
    return 'projects';
}

// 菜单切换
function showSection(sectionId, element, options) {
    const opts = options || {};
    let updateHistory = opts.updateHistory !== false;

    // 深链或书签进入无权限页面时，改到第一个有权限的菜单；扩展页面因 License 未开通被拦时给出提示
    if (!canAccessSection(sectionId)) {
        const lockedFeature = lockedFeatureOf(sectionId);
        if (lockedFeature) {
            notify(licenseFeatureMessage(lockedFeature));
        }
        sectionId = firstAccessibleSection();
        element = document.querySelector(`.menu-item[data-section="${sectionId}"]`);
        updateHistory = true;
        opts.tab = '';
    }

    // 隐藏所有内容区域
    document.querySelectorAll('.content-section').forEach(section => {
        section.classList.remove('active');
    });
    
    // 显示选中的内容区域
    const targetSection = document.getElementById(sectionId + '-section');
    if (targetSection) {
        targetSection.classList.add('active');
    }
    
    // 更新菜单项状态
    document.querySelectorAll('.menu-item').forEach(item => {
        item.classList.remove('active');
    });
    if (element) {
        element.classList.add('active');
    } else {
        const menuItem = document.querySelector(`.menu-item[data-section="${sectionId}"]`);
        if (menuItem) {
            menuItem.classList.add('active');
        }
    }

    if (updateHistory && !suppressHistoryUpdate) {
        const path = getPathForSection(sectionId);
        const state = { section: sectionId };
        const url = path + (opts.tab ? '#' + encodeURIComponent(opts.tab) : '');
        if (window.location.pathname !== path) {
            history.pushState(state, '', url);
        } else {
            history.replaceState(state, '', url);
        }
        document.title = (SECTION_TITLES[sectionId] || t('app.console')) + ' - OPSFLOW';
    }
    
    runSectionLoader(sectionId, opts.tab);
}

/** 进入页面时的加载动作：配置类页面激活默认页签（或地址里指定的页签），其他调用登记的全局函数 */
function runSectionLoader(sectionId, tab) {
    const loader = SECTION_LOADERS[sectionId];
    if (!loader) {
        return;
    }
    try {
        if (typeof loader === 'object' && loader.defaultTab) {
            activateConfigSectionDefault(sectionId, loader.defaultTab, tab);
        } else if (typeof window[loader] === 'function') {
            window[loader]();
        }
    } catch (e) {
        console.error('Section loader failed: ' + sectionId, e);
    }
}

/**
 * 激活配置页的页签，只在当前用户可见的页签里选：地址指定的页签（requestedTab）→ 已激活的 →
 * 默认页签 → 第一个可见页签。指定的页签不存在或不可见时，把地址里的 #<tab> 去掉。
 */
function activateConfigSectionDefault(sectionId, defaultTab, requestedTab) {
    const section = document.getElementById(sectionId + '-section');
    if (!section) return;
    const visible = visibleConfigTabs(section);
    const byName = name => name ? visible.find(btn => btn.getAttribute('data-tab') === name) : null;
    const requested = byName(requestedTab);
    const target = requested
        || visible.find(btn => btn.classList.contains('active'))
        || byName(defaultTab)
        || visible[0];
    if (requestedTab && !requested && window.location.hash
            && window.location.pathname === getPathForSection(sectionId)) {
        history.replaceState(history.state, '', window.location.pathname);
    }
    if (target && typeof showConfigTab === 'function') {
        showConfigTab(target.getAttribute('data-tab'), target);
    }
}
window.activateConfigSectionDefault = activateConfigSectionDefault;
window.showSection = showSection;

/** 重新执行当前页面的加载逻辑（不改地址栏），语言切换后用来重绘 JS 渲染的内容 */
function reloadActiveSection() {
    const active = document.querySelector('.content-section.active');
    if (!active) {
        return;
    }
    const sectionId = active.id.replace(/-section$/, '');
    showSection(sectionId, document.querySelector(`.menu-item[data-section="${sectionId}"]`), { updateHistory: false });
}
window.reloadActiveSection = reloadActiveSection;

// 模态框工具函数
/** 打开通用弹窗前获得焦点的元素，关闭后把焦点还给它（弹窗开着时再次 showModal 换内容，不覆盖） */
let modalReturnFocus = null;
/** 当前弹窗在切换语言时的重绘回调（showModal 的 options.onLocaleChange） */
let modalLocaleRefresh = null;

function isGenericModalOpen() {
    const container = document.getElementById('modalContainer');
    return !!(container && container.querySelector('.modal-overlay[data-generic-modal]'));
}

/**
 * 打开通用弹窗。
 * @param {string} title 已转义的标题 HTML
 * @param {string} content 已转义的内容 HTML
 * @param {Function} [onConfirm] 点「确定」或在表单里回车时调用；不传则只有「取消」
 * @param {Object} [options]
 * @param {Function} [options.onLocaleChange] 弹窗开着时切换了语言就调用它，用新语言重绘（通常是再调一次 showModal）；
 *   不传时切换语言不重绘。表单弹窗重绘会丢掉已填内容，一般只给只读的详情类弹窗传
 */
function showModal(title, content, onConfirm, options) {
    const modalContainer = document.getElementById('modalContainer');
    if (!isGenericModalOpen()) {
        const active = document.activeElement;
        modalReturnFocus = active && active !== document.body ? active : null;
    }
    modalContainer.innerHTML = `
        <div class="modal-overlay" data-generic-modal onclick="closeModal(event)">
            <div class="modal" role="dialog" aria-modal="true" tabindex="-1" onclick="event.stopPropagation()">
                <div class="modal-header">
                    <h3>${/* html-safe: 约定：标题由调用方传入已转义的 HTML */ title}</h3>
                    <button class="modal-close" onclick="closeModal()">&times;</button>
                </div>
                <div class="modal-body">
                    ${/* html-safe: 约定：内容由调用方传入已转义的 HTML */ content}
                </div>
                <div class="modal-footer">
                    <button class="btn-secondary" onclick="closeModal()">${t('common.cancel')}</button>
                    ${onConfirm ? `<button class="btn-primary" onclick="confirmModal()">${t('common.confirm')}</button>` : ''}
                </div>
            </div>
        </div>
    `;

    window.currentModalConfirm = onConfirm;
    modalLocaleRefresh = options && typeof options.onLocaleChange === 'function' ? options.onLocaleChange : null;
    bindModalForms(modalContainer, !!onConfirm);
    resetModalDirtyState();
    // 焦点移进弹窗，Tab 从弹窗里开始；调用方之后自己聚焦输入框的不受影响
    const dialog = modalContainer.querySelector('.modal');
    if (dialog && !dialog.contains(document.activeElement)) {
        dialog.focus({ preventScroll: true });
    }
}

/**
 * Esc 关闭通用弹窗。叠在上面的确认框、下拉框、行操作菜单自己处理 Esc 时会 preventDefault
 * 或在捕获阶段拦下，这里就不再关弹窗；两步验证等自建弹窗不带 data-generic-modal，不受影响。
 */
document.addEventListener('keydown', event => {
    if (event.key !== 'Escape' || event.defaultPrevented || event.isComposing) return;
    if (!isGenericModalOpen()) return;
    event.preventDefault();
    closeModalAfterDiscardConfirm();
});

// ---- Esc 关闭前的「未保存内容」确认 ----
// 基线是表单控件的值快照：showModal 时记一次，用户第一次聚焦表单（还没改过）时再记一次，
// 盖住打开后异步回填的下拉框、默认值。只有用户亲手改过（可信的 input / change）且当前值和基线不同才算有改动，
// 代码回填、派发的事件不算；没有表单控件的只读弹窗永远不算有改动。
const MODAL_FIELD_SELECTOR = 'input:not([type=button]):not([type=submit]):not([type=reset]):not([type=hidden]), select, textarea, [contenteditable=""], [contenteditable="true"]';
let modalFieldBaseline = null;
let modalUserEdited = false;
/** 每次 showModal / closeModal 加一，确认框回来时弹窗已经换了内容就不再关 */
let modalGeneration = 0;
let modalDiscardAsking = false;

function modalFieldSnapshot() {
    const body = document.querySelector('#modalContainer .modal-overlay[data-generic-modal] .modal-body');
    if (!body) return '';
    return JSON.stringify(Array.from(body.querySelectorAll(MODAL_FIELD_SELECTOR), el => {
        if (el.isContentEditable && !('value' in el)) return el.textContent;
        if (el.type === 'checkbox' || el.type === 'radio') return el.checked;
        if (el.multiple && el.options) return Array.from(el.selectedOptions, o => o.value);
        return el.value;
    }));
}

function resetModalDirtyState() {
    modalGeneration++;
    modalUserEdited = false;
    modalFieldBaseline = isGenericModalOpen() ? modalFieldSnapshot() : null;
}

function isInGenericModalBody(target) {
    return !!(target && target.closest && target.closest('#modalContainer .modal-overlay[data-generic-modal] .modal-body'));
}

document.addEventListener('focusin', event => {
    if (event.isTrusted && !modalUserEdited && isInGenericModalBody(event.target) && event.target.matches(MODAL_FIELD_SELECTOR)) {
        modalFieldBaseline = modalFieldSnapshot();
    }
}, true);
['input', 'change'].forEach(type => document.addEventListener(type, event => {
    if (event.isTrusted && isInGenericModalBody(event.target)) modalUserEdited = true;
}, true));

function isModalDirty() {
    return modalUserEdited && modalFieldBaseline !== null && modalFieldSnapshot() !== modalFieldBaseline;
}

/** Esc 用：表单有未保存的改动先用 confirmDialog 确认，选「继续编辑」时弹窗和焦点不动 */
async function closeModalAfterDiscardConfirm() {
    if (!isModalDirty()) {
        closeModal();
        return;
    }
    if (modalDiscardAsking || typeof window.confirmDialog !== 'function') return;
    modalDiscardAsking = true;
    const generation = modalGeneration;
    try {
        const ok = await window.confirmDialog(t('common.discardChangesMessage'), {
            title: t('common.discardChangesTitle'),
            confirmText: t('common.discardChanges'),
            cancelText: t('common.keepEditing'),
            danger: true
        });
        if (ok && generation === modalGeneration && isGenericModalOpen()) closeModal();
    } finally {
        modalDiscardAsking = false;
    }
}

/**
 * 弹窗里的表单按回车等于点「确定」。
 * 「确定」按钮在表单外，原生校验不会触发，必填由各回调自己校验，所以表单统一 novalidate。
 * 多行输入框、输入法选词、下拉选择和自己处理了回车（preventDefault）的控件，浏览器本来就不会提交。
 */
function bindModalForms(container, hasConfirm) {
    container.querySelectorAll('.modal-body form').forEach(form => {
        form.noValidate = true;
        let implicit = null;
        if (hasConfirm) {
            // 表单里输入框不止一个又没有提交按钮时浏览器不会隐式提交，放一个看不见的提交按钮在最前面，
            // 同时让它成为默认按钮，免得回车点到表单里没写 type 的其他按钮
            implicit = document.createElement('button');
            implicit.type = 'submit';
            implicit.className = 'modal-implicit-submit';
            implicit.tabIndex = -1;
            implicit.setAttribute('aria-hidden', 'true');
            form.insertBefore(implicit, form.firstChild);
        }
        form.addEventListener('submit', event => {
            event.preventDefault();
            // 只认回车触发的提交；表单里其他按钮被点到时不当成「确定」
            if (implicit && (!event.submitter || event.submitter === implicit)) {
                confirmModal();
            }
        });
    });
}

function closeModal(event) {
    if (event && event.target !== event.currentTarget) return;
    if (typeof window.stopPipelineStageLogPolling === 'function') {
        window.stopPipelineStageLogPolling();
    }
    window.__pipelineStageLogContext = null;
    const wasOpen = isGenericModalOpen();
    document.getElementById('modalContainer').innerHTML = '';
    window.currentModalConfirm = null;
    modalLocaleRefresh = null;
    resetModalDirtyState();
    const returnTo = modalReturnFocus;
    modalReturnFocus = null;
    // 触发元素还在页面上才还焦点（列表重绘后原按钮已经没了）
    if (wasOpen && returnTo && document.contains(returnTo) && typeof returnTo.focus === 'function') {
        returnTo.focus({ preventScroll: true });
    }
}
window.closeModal = closeModal;

// 确定回调执行中（如连按两次回车、连点确定）不重复提交
let modalConfirmBusy = false;

async function confirmModal() {
    const fn = window.currentModalConfirm;
    if (!fn) {
        closeModal();
        return;
    }
    if (modalConfirmBusy) return;
    modalConfirmBusy = true;
    try {
        const result = fn();
        // 异步回调自行决定何时关闭（成功 closeModal）；失败时保留弹窗便于改选
        if (result && typeof result.then === 'function') {
            await result;
            return;
        }
        closeModal();
    } catch (error) {
        console.error('Modal confirm error:', error);
        notify((error && error.message) || t('common.failed'));
    } finally {
        modalConfirmBusy = false;
    }
}
window.confirmModal = confirmModal;

// 确保showModal也在全局作用域中可用
window.showModal = showModal;

// ============================================
// Dashboard初始化 - 页面加载时执行
// ============================================

// 语言切换后刷新静态文案与依赖文案的控件
window.onLocaleChange = function () {
    if (typeof applyI18n === 'function') {
        applyI18n(document);
    }
    // JS 渲染的列表不带 data-i18n，按新语言重新加载当前页面
    reloadActiveSection();
    if (typeof loadAppVersion === 'function') {
        loadAppVersion();
    }
    if (typeof refreshProjectSwitcher === 'function') {
        refreshProjectSwitcher();
    }
    const active = document.querySelector('.content-section.active');
    const sectionId = active ? active.id.replace(/-section$/, '') : '';
    if (sectionId) {
        document.title = (getSectionTitle(sectionId) || 'OPSFLOW') + ' - OPSFLOW';
    }
    // 开着的通用弹窗登记了重绘回调的，按新语言重绘
    if (modalLocaleRefresh && isGenericModalOpen()) {
        try {
            modalLocaleRefresh();
        } catch (e) {
            console.warn('modal locale refresh failed', e);
        }
    }
    // 动态渲染的列表（如扩展页面）可监听此事件按新语言重绘
    try {
        window.dispatchEvent(new CustomEvent('opsflow:localechange'));
    } catch (e) {
        console.warn('localechange event failed', e);
    }
};

// 配置区（带 .system-config-tabs 页签的页面：流水线配置、凭据与组件，以及扩展提供的配置页）的页签和按钮统一用事件委托处理。
// 「添加步骤」和各模版的「添加」按钮由 step-def.js / template.js 自行绑定，不在这里处理。
function bindConfigSectionActions() {
    const root = document.querySelector('.main-content');
    if (!root) {
        return;
    }
    const call = function (fnName, ...args) {
        const fn = window[fnName];
        if (typeof fn !== 'function') {
            console.error(fnName + ' is not available');
            notify(t('common.notLoaded'));
            return;
        }
        try {
            fn(...args);
        } catch (error) {
            console.error('Error calling ' + fnName + ':', error);
            notify(t('common.failedWith', { reason: error.message }), 'error');
        }
    };
    const buttonActions = {
        addPipelineBtn: 'showAddPipelineModal',
        addPipelineViewBtn: 'showCreatePipelineViewModal',
        addComponentBtn: 'showAddComponentModal'
    };
    root.addEventListener('click', function (e) {
        const section = e.target.closest('.content-section');
        if (!section || !section.querySelector('.system-config-tabs')) {
            return;
        }
        const handle = function (fnName, ...args) {
            e.preventDefault();
            e.stopPropagation();
            call(fnName, ...args);
        };
        const tabBtn = e.target.closest('.tab-btn[data-tab]');
        if (tabBtn) {
            handle('showConfigTab', tabBtn.getAttribute('data-tab'), tabBtn);
            // 地址里带着 #<tab> 时跟着改成当前页签，刷新后停在同一页签；没带时不改地址
            if (window.location.hash) {
                history.replaceState(history.state, '',
                    window.location.pathname + '#' + encodeURIComponent(tabBtn.getAttribute('data-tab')));
            }
            return;
        }
        const actionBtn = e.target.closest('#addPipelineBtn, #addPipelineViewBtn, #addComponentBtn');
        if (actionBtn) {
            handle(buttonActions[actionBtn.id]);
            return;
        }
        const templateTabBtn = e.target.closest('.template-tab-btn[data-template-type]');
        if (templateTabBtn) {
            handle('showTemplateTab', templateTabBtn.getAttribute('data-template-type'), templateTabBtn);
        }
    });
}

// 页面加载时初始化（全站唯一入口；各页面的数据由 showSection 在进入该页面时加载）
document.addEventListener('DOMContentLoaded', async function() {
    if (typeof applyI18n === 'function') {
        applyI18n(document);
    }
    if (typeof loadAppVersion === 'function') {
        loadAppVersion();
    }
    bindConfigSectionActions();
    if (!await checkAuth()) {
        return;
    }
    loadEnvs();
    loadServicesForSelect();
    loadNodesForSelect();
    bindMenuNavigation();
    initSectionFromUrl();
});
