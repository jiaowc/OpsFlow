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

// 检查登录状态
async function checkAuth() {
    try {
        const response = await fetch('/api/auth/user');
        if (!response.ok) {
            window.location.href = '/index.html';
            return;
        }
        const user = await response.json();
        if (!user.authenticated) {
            window.location.href = '/index.html';
            return;
        }
        await loadUserInfo();
    } catch (error) {
        window.location.href = '/index.html';
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

/** License 功能状态（由 /api/license/status 填充） */
let licenseStatus = {
    valid: false,
    present: false,
    features: [],
    deployApprovalEnabled: false,
    auditEnabled: false,
    notifyIntegrationEnabled: false,
    prodRollbackEnabled: false,
    // false = 后端已关闭强校验，前端按功能全开处理
    enforcementEnabled: false,
    message: ''
};

const LICENSE_FEATURE_FLAGS = {
    deploy_approval: 'deployApprovalEnabled',
    audit: 'auditEnabled',
    notify_integration: 'notifyIntegrationEnabled',
    prod_rollback: 'prodRollbackEnabled',
    branding: 'brandingEnabled'
};

function hasFeature(...codes) {
    // License 强校验关闭时：全部功能可见可用
    if (licenseStatus && licenseStatus.enforcementEnabled === false) {
        return true;
    }
    const features = (licenseStatus && licenseStatus.features) || [];
    if (!codes || codes.length === 0) {
        return true;
    }
    for (let i = 0; i < codes.length; i++) {
        const code = codes[i];
        if (!code) continue;
        if (String(code).indexOf(',') >= 0) {
            const parts = String(code).split(',').map(s => s.trim()).filter(Boolean);
            if (parts.some(p => featureCodeEnabled(p, features))) {
                return true;
            }
        } else if (featureCodeEnabled(code, features)) {
            return true;
        }
    }
    return false;
}

function featureCodeEnabled(code, features) {
    if (features.indexOf(code) >= 0) {
        return true;
    }
    const flag = LICENSE_FEATURE_FLAGS[code];
    return !!(flag && licenseStatus && licenseStatus[flag]);
}

window.hasFeature = hasFeature;
window.getLicenseStatus = function () { return licenseStatus; };

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
    // 无上线审批 License 时，待审批 KPI 一并隐藏（即使无 data-feature 也能兜底）
    const pendingKpi = document.getElementById('kpiPendingApprovals');
    if (pendingKpi && !hasFeature('deploy_approval')) {
        pendingKpi.style.display = 'none';
    }
    // License 强校验关闭时，隐藏 License 配置菜单（后期开启 enforcement 后自动恢复）
    const licenseMenu = document.querySelector('[data-section="license-config"]');
    if (licenseMenu) {
        if (licenseStatus && licenseStatus.enforcementEnabled === false) {
            licenseMenu.style.display = 'none';
        } else {
            const perm = licenseMenu.getAttribute('data-permission');
            licenseMenu.style.display = (!perm || hasPermission(perm)) ? '' : 'none';
        }
    }
}

window.applyFeatureUI = applyFeatureUI;

async function loadLicenseStatus() {
    try {
        const response = await fetch('/api/license/status');
        if (response.ok) {
            licenseStatus = await response.json() || licenseStatus;
            if (!Array.isArray(licenseStatus.features)) {
                licenseStatus.features = [];
            }
            Object.keys(LICENSE_FEATURE_FLAGS).forEach(code => {
                const flag = LICENSE_FEATURE_FLAGS[code];
                if (licenseStatus[flag] && licenseStatus.features.indexOf(code) < 0) {
                    licenseStatus.features.push(code);
                }
            });
        }
    } catch (e) {
        console.warn('Load license status failed', e);
    }
    applyFeatureUI();
    return licenseStatus;
}

window.loadLicenseStatus = loadLicenseStatus;

function applyPermissionUI() {
    document.querySelectorAll('[data-permission]').forEach(el => {
        const required = el.getAttribute('data-permission');
        const allowed = hasPermission(required);
        el.style.display = allowed ? '' : 'none';
        if (el.classList && el.classList.contains('menu-item')) {
            // keep layout consistent
        }
    });
    const configGroup = document.querySelector('.menu-group');
    if (configGroup) {
        const visibleSubs = configGroup.querySelectorAll('.menu-item-sub');
        let anyVisible = false;
        visibleSubs.forEach(item => {
            if (item.style.display !== 'none') {
                anyVisible = true;
            }
        });
        configGroup.style.display = anyVisible ? '' : 'none';
    }
    applyFeatureUI();
}

window.applyPermissionUI = applyPermissionUI;

// 加载用户信息
async function loadUserInfo() {
    try {
        const response = await fetch('/api/auth/user');
        if (response.ok) {
            const user = await response.json();
            currentUser = user;
            document.getElementById('username').textContent = user.username || '用户';
            const changePwdItem = document.getElementById('changePasswordMenuItem');
            if (changePwdItem) {
                changePwdItem.style.display = user.localUser === false ? 'none' : '';
            }
            if (user.mustChangePassword) {
                // 弱口令登录：其他 API 均被后端 403 拦截，先强制改密
                showChangePasswordModal(true);
                return;
            }
            await loadLicenseStatus();
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
    const esc = s => String(s == null ? '' : s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
    const allLabel = typeof t === 'function' ? t('header.allProjects') : '全部项目';
    const keep = prev && projects.some(p => String(p.id) === prev) ? prev : '';
    select.innerHTML = `<option value="">${esc(allLabel)}</option>` + projects.map(p =>
        `<option value="${p.id}"${String(p.id) === keep ? ' selected' : ''}>${esc(p.name)}</option>`
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
                    <h3>${force ? '请先修改初始密码' : '修改密码'}</h3>
                    ${force ? '' : '<button class="modal-close" onclick="closeChangePasswordModal()">&times;</button>'}
                </div>
                <div class="modal-body">
                    ${force ? '<p style="margin:0 0 12px;color:#d9534f;">当前密码过于简单（弱口令），修改后才能继续使用系统。</p>' : ''}
                    <div class="form-item">
                        <label>旧密码 *</label>
                        <input type="password" id="cpOldPassword" autocomplete="current-password">
                    </div>
                    <div class="form-item">
                        <label>新密码 *</label>
                        <input type="password" id="cpNewPassword" autocomplete="new-password" placeholder="至少 8 位，不能为常见弱口令或与用户名相同">
                    </div>
                    <div class="form-item">
                        <label>确认新密码 *</label>
                        <input type="password" id="cpConfirmPassword" autocomplete="new-password">
                    </div>
                    <div id="cpError" style="color:#d9534f;min-height:18px;"></div>
                </div>
                <div class="modal-footer">
                    ${force ? '<button class="btn-secondary" onclick="logout()">退出登录</button>' : '<button class="btn-secondary" onclick="closeChangePasswordModal()">取消</button>'}
                    <button class="btn-primary" id="cpSubmitBtn" onclick="submitChangePassword()">确认修改</button>
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
    errorEl.textContent = '';
    if (!oldPassword || !newPassword) {
        errorEl.textContent = '旧密码和新密码不能为空';
        return;
    }
    if (newPassword.length < 8) {
        errorEl.textContent = '新密码长度不能少于 8 位';
        return;
    }
    if (newPassword !== confirmPassword) {
        errorEl.textContent = '两次输入的新密码不一致';
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
            notifyAfterReload('密码修改成功', 'success');
            window.location.reload();
            return;
        }
        errorEl.textContent = data.message || '修改失败';
    } catch (error) {
        errorEl.textContent = '修改失败：' + ((error && error.message) || '网络错误');
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

// 模块路径映射（标题走 i18n）
const SECTION_TITLE_KEYS = {
    statistics: 'menu.statistics',
    pipeline: 'menu.pipeline',
    tasks: 'menu.tasks',
    services: 'menu.services',
    projects: 'menu.projects',
    environments: 'menu.environments',
    clusters: 'menu.clusters',
    approval: 'menu.approval',
    nodes: 'menu.nodes',
    users: 'menu.users',
    audit: 'menu.audit',
    'pipeline-config': 'menu.pipelineConfig',
    'credentials-config': 'menu.credentials',
    'integration-config': 'menu.integrations',
    'branding-config': 'menu.branding',
    'license-config': 'menu.license'
};

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

const SECTION_ROUTES = {
    statistics: '/statistics',
    pipeline: '/pipeline',
    tasks: '/tasks',
    services: '/services',
    projects: '/projects',
    environments: '/environments',
    clusters: '/clusters',
    approval: '/approval',
    nodes: '/nodes',
    users: '/users',
    audit: '/audit',
    'pipeline-config': '/pipeline-config',
    'credentials-config': '/credentials',
    'integration-config': '/integrations',
    'branding-config': '/branding',
    'license-config': '/license'
};

const ROUTE_SECTIONS = Object.fromEntries(
    Object.entries(SECTION_ROUTES).map(([section, path]) => [path, section])
);

let suppressHistoryUpdate = false;

function getPathForSection(sectionId) {
    return SECTION_ROUTES[sectionId] || '/statistics';
}

function getSectionFromPath() {
    const path = window.location.pathname.replace(/\/$/, '') || '/';
    return ROUTE_SECTIONS[path] || 'statistics';
}

function initSectionFromUrl() {
    const sectionId = getSectionFromPath();
    const menuItem = document.querySelector(`.menu-item[data-section="${sectionId}"]`);
    suppressHistoryUpdate = true;
    showSection(sectionId, menuItem, { updateHistory: false });
    suppressHistoryUpdate = false;

    const active = document.querySelector('.content-section.active');
    const shownSection = active ? active.id.replace(/-section$/, '') : sectionId;
    const expectedPath = getPathForSection(shownSection);
    if (window.location.pathname !== expectedPath) {
        history.replaceState({ section: shownSection }, '', expectedPath);
    }
    document.title = (SECTION_TITLES[shownSection] || '控制台') + ' - OPSFLOW';
    document.documentElement.removeAttribute('data-initial-section');
}

function bindMenuNavigation() {
    document.querySelectorAll('.menu-item[data-section]').forEach(item => {
        item.addEventListener('click', function(e) {
            if (e.metaKey || e.ctrlKey || e.shiftKey || e.button === 1) {
                return;
            }
            e.preventDefault();
            showSection(this.dataset.section, this);
        });
    });

    document.addEventListener('click', function(e) {
        const link = e.target.closest('a[href]');
        if (!link || link.classList.contains('menu-item')) {
            return;
        }
        const path = link.getAttribute('href');
        const sectionId = ROUTE_SECTIONS[path];
        if (!sectionId) {
            return;
        }
        if (e.metaKey || e.ctrlKey || e.shiftKey || e.button === 1) {
            return;
        }
        e.preventDefault();
        const menuItem = document.querySelector(`.menu-item[data-section="${sectionId}"]`);
        showSection(sectionId, menuItem);
    });

    window.addEventListener('popstate', function(e) {
        const sectionId = (e.state && e.state.section) || getSectionFromPath();
        const menuItem = document.querySelector(`.menu-item[data-section="${sectionId}"]`);
        suppressHistoryUpdate = true;
        showSection(sectionId, menuItem, { updateHistory: false });
        suppressHistoryUpdate = false;
    });
}

/** 当前用户能否进入该页面：以左侧菜单项的 data-permission / data-feature 为准 */
function canAccessSection(sectionId) {
    const menuItem = document.querySelector(`.menu-item[data-section="${sectionId}"]`);
    if (!menuItem) {
        return true;
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

    // 深链或书签进入无权限页面时，改到第一个有权限的菜单
    if (!canAccessSection(sectionId)) {
        sectionId = firstAccessibleSection();
        element = document.querySelector(`.menu-item[data-section="${sectionId}"]`);
        updateHistory = true;
    }

    // License 未开通时，拦截商业功能深链
    if (sectionId === 'audit' && !hasFeature('audit')) {
        notify(typeof t === 'function' ? t('license.feature.audit') : '操作审计为 Pro 订阅功能，请导入有效 License');
        sectionId = firstAccessibleSection();
        element = document.querySelector(`.menu-item[data-section="${sectionId}"]`);
    } else if (sectionId === 'integration-config' && !hasFeature('notify_integration')) {
        notify(typeof t === 'function' ? t('license.feature.notify') : '集成与通知为 Pro 订阅功能，请导入有效 License');
        sectionId = firstAccessibleSection();
        element = document.querySelector(`.menu-item[data-section="${sectionId}"]`);
    } else if ((sectionId === 'tasks' || sectionId === 'approval') && !hasFeature('deploy_approval')) {
        sectionId = 'statistics';
        element = document.querySelector('.menu-item[data-section="statistics"]');
    }

    // 隐藏所有内容区域
    document.querySelectorAll('.content-section').forEach(section => {
        section.classList.remove('active');
    });
    
    // 加载对应区域的数据
    if (sectionId === 'pipeline' && typeof loadPipelineJobsView === 'function') {
        loadPipelineJobsView();
    }
    
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
        if (window.location.pathname !== path) {
            history.pushState(state, '', path);
        } else {
            history.replaceState(state, '', path);
        }
        document.title = (SECTION_TITLES[sectionId] || '控制台') + ' - OPSFLOW';
    }
    
    // 根据选中的区域加载数据
    switch(sectionId) {
        case 'pipeline':
            if (typeof loadPipelineJobsView === 'function') loadPipelineJobsView();
            break;
        case 'tasks':
            if (typeof loadTasks === 'function') loadTasks();
            break;
        case 'services':
            if (typeof loadServices === 'function') loadServices();
            break;
        case 'environments':
            if (typeof loadEnvironments === 'function') loadEnvironments();
            break;
        case 'clusters':
            if (typeof loadClusters === 'function') loadClusters();
            break;
        case 'approval':
            if (typeof loadApprovalFlows === 'function') loadApprovalFlows();
            break;
        case 'nodes':
            if (typeof loadNodes === 'function') loadNodes();
            break;
        case 'users':
            if (typeof showUserTab === 'function') {
                const firstTabBtn = Array.from(document.querySelectorAll('#users-section .tab-btn'))
                    .find(btn => btn.style.display !== 'none');
                if (firstTabBtn) {
                    showUserTab('user-list', firstTabBtn);
                } else {
                    if (typeof loadUsers === 'function') loadUsers();
                }
            }
            break;
        case 'audit':
            if (typeof loadAuditPage === 'function') loadAuditPage();
            break;
        case 'projects':
            if (typeof loadProjects === 'function') loadProjects();
            break;
        case 'pipeline-config':
            activateConfigSectionDefault('pipeline-config', 'step-def');
            break;
        case 'credentials-config':
            activateConfigSectionDefault('credentials-config', 'keychain');
            break;
        case 'integration-config':
            activateConfigSectionDefault('integration-config', 'notify');
            break;
        case 'license-config':
            if (typeof loadLicensePage === 'function') loadLicensePage();
            break;
        case 'branding-config':
            if (typeof loadBrandingAdminPanel === 'function') loadBrandingAdminPanel();
            if (typeof applyI18n === 'function') applyI18n(document.getElementById('branding-config-section'));
            break;
        case 'statistics':
            if (typeof loadStatistics === 'function') loadStatistics();
            break;
    }
}

function activateConfigSectionDefault(sectionId, defaultTab) {
    const section = document.getElementById(sectionId + '-section');
    if (!section) return;
    const activeBtn = section.querySelector('.tab-buttons > .tab-btn.active')
        || section.querySelector(`.tab-buttons > .tab-btn[data-tab="${defaultTab}"]`)
        || section.querySelector('.tab-buttons > .tab-btn');
    if (activeBtn && typeof showConfigTab === 'function') {
        showConfigTab(activeBtn.getAttribute('data-tab'), activeBtn);
    }
}
window.activateConfigSectionDefault = activateConfigSectionDefault;
window.showSection = showSection;

// 模态框工具函数
function showModal(title, content, onConfirm) {
    const modalContainer = document.getElementById('modalContainer');
    modalContainer.innerHTML = `
        <div class="modal-overlay" onclick="closeModal(event)">
            <div class="modal" onclick="event.stopPropagation()">
                <div class="modal-header">
                    <h3>${title}</h3>
                    <button class="modal-close" onclick="closeModal()">&times;</button>
                </div>
                <div class="modal-body">
                    ${content}
                </div>
                <div class="modal-footer">
                    <button class="btn-secondary" onclick="closeModal()">${typeof t === 'function' ? t('common.cancel') : '取消'}</button>
                    ${onConfirm ? `<button class="btn-primary" onclick="confirmModal()">${typeof t === 'function' ? t('common.confirm') : '确认'}</button>` : ''}
                </div>
            </div>
        </div>
    `;
    
    window.currentModalConfirm = onConfirm;
}

function closeModal(event) {
    if (event && event.target !== event.currentTarget) return;
    if (typeof window.stopPipelineStageLogPolling === 'function') {
        window.stopPipelineStageLogPolling();
    }
    window.__pipelineStageLogContext = null;
    document.getElementById('modalContainer').innerHTML = '';
    window.currentModalConfirm = null;
}
window.closeModal = closeModal;

async function confirmModal() {
    const fn = window.currentModalConfirm;
    if (!fn) {
        closeModal();
        return;
    }
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
        notify((error && error.message) || '操作失败');
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
    if (typeof loadPublicBranding === 'function') {
        loadPublicBranding();
    }
    if (typeof loadBrandingAdminPanel === 'function') {
        loadBrandingAdminPanel();
    }
    if (typeof refreshProjectSwitcher === 'function') {
        refreshProjectSwitcher();
    }
    const active = document.querySelector('.content-section.active');
    const sectionId = active ? active.id.replace(/-section$/, '') : '';
    if (sectionId) {
        document.title = (getSectionTitle(sectionId) || 'OPSFLOW') + ' - OPSFLOW';
    }
};

// 页面加载时初始化
document.addEventListener('DOMContentLoaded', async function() {
    if (typeof applyI18n === 'function') {
        applyI18n(document);
    }
    if (typeof loadPublicBranding === 'function') {
        loadPublicBranding();
    }
    await checkAuth();
    loadEnvs();
    loadServicesForSelect();
    loadNodesForSelect();
    bindMenuNavigation();
    initSectionFromUrl();
    
    // 绑定Pipeline相关按钮事件（等待模块加载完成）
    function bindPipelineButton() {
        const addPipelineBtn = document.getElementById('addPipelineBtn');
        console.log('Binding pipeline button, button found:', !!addPipelineBtn);
        console.log('showAddPipelineModal available:', typeof window.showAddPipelineModal);
        console.log('showModal available:', typeof window.showModal);
        
        if (addPipelineBtn && typeof window.showAddPipelineModal === 'function') {
            // 移除可能存在的旧事件监听器
            const newBtn = addPipelineBtn.cloneNode(true);
            addPipelineBtn.parentNode.replaceChild(newBtn, addPipelineBtn);
            
            // 绑定新的事件监听器
            newBtn.addEventListener('click', function(e) {
                e.preventDefault();
                e.stopPropagation();
                console.log('Add Pipeline button clicked');
                
                if (typeof window.showAddPipelineModal === 'function') {
                    console.log('Calling showAddPipelineModal...');
                    try {
                        window.showAddPipelineModal();
                        console.log('showAddPipelineModal called successfully');
                    } catch (error) {
                        console.error('Error calling showAddPipelineModal:', error);
                        notify('打开Pipeline模态框时出错: ' + error.message);
                    }
                } else {
                    console.error('showAddPipelineModal not available');
                    notify('Pipeline功能未加载，请刷新页面');
                }
            });
            console.log('Pipeline button bound successfully');
            return true;
        } else {
            console.warn('Pipeline button binding failed:', {
                buttonExists: !!addPipelineBtn,
                functionExists: typeof window.showAddPipelineModal === 'function'
            });
        }
        return false;
    }
    
    // 绑定系统管理相关按钮的函数（使用事件委托）
    function bindSystemButtons() {
        if (window.__opsflowSystemButtonsBound) {
            return true;
        }
        console.log('bindSystemButtons called');
        console.log('showConfigTab available:', typeof window.showConfigTab);
        
        const configRoot = document.querySelector('.main-content');
        console.log('config root found:', !!configRoot);
        
        if (!configRoot) {
            console.error('main-content not found');
            return false;
        }
        window.__opsflowSystemButtonsBound = true;
        
        // 使用事件委托绑定配置区标签页与按钮
        configRoot.addEventListener('click', function(e) {
            // 仅处理三个配置页内的点击
            const inConfig = e.target.closest('#pipeline-config-section, #credentials-config-section, #integration-config-section');
            if (!inConfig) {
                return;
            }
            // 检查是否点击了标签页按钮
            const tabBtn = e.target.closest('.tab-btn[data-tab]');
            if (tabBtn) {
                e.preventDefault();
                e.stopPropagation();
                const tabName = tabBtn.getAttribute('data-tab');
                console.log('Tab button clicked:', tabName);
                console.log('showConfigTab type:', typeof window.showConfigTab);
                
                if (typeof window.showConfigTab === 'function') {
                    try {
                        window.showConfigTab(tabName, tabBtn);
                    } catch (error) {
                        console.error('Error calling showConfigTab:', error);
                        notify('调用系统功能时出错: ' + error.message);
                    }
                } else {
                    console.error('showConfigTab function not available');
                    console.log('Available functions:', Object.keys(window).filter(k => k.includes('show') || k.includes('Config')));
                    notify('系统功能未加载完成，请刷新页面');
                }
                return;
            }
            
            // 检查是否点击了添加步骤按钮
            const addStepDefBtn = e.target.closest('#addStepDefBtn');
            if (addStepDefBtn) {
                e.preventDefault();
                e.stopPropagation();
                if (typeof window.showCreateStepDefModal === 'function') {
                    window.showCreateStepDefModal();
                } else {
                    notify('步骤管理功能未加载，请刷新页面');
                }
                return;
            }

            // 检查是否点击了添加视图按钮
            const addPipelineViewBtn = e.target.closest('#addPipelineViewBtn');
            if (addPipelineViewBtn) {
                e.preventDefault();
                e.stopPropagation();
                if (typeof window.showCreatePipelineViewModal === 'function') {
                    window.showCreatePipelineViewModal();
                } else {
                    notify('视图管理功能未加载，请刷新页面');
                }
                return;
            }

            // 检查是否点击了添加组件按钮
            const addComponentBtn = e.target.closest('#addComponentBtn');
            if (addComponentBtn) {
                e.preventDefault();
                e.stopPropagation();
                console.log('Add component button clicked');
                
                if (typeof window.showAddComponentModal === 'function') {
                    try {
                        window.showAddComponentModal();
                    } catch (error) {
                        console.error('Error calling showAddComponentModal:', error);
                        notify('打开组件添加对话框时出错: ' + error.message);
                    }
                } else {
                    console.error('showAddComponentModal function not available');
                    notify('组件管理功能未加载完成，请刷新页面');
                }
                return;
            }
            
            // 检查是否点击了模版管理子标签
            const templateTabBtn = e.target.closest('.template-tab-btn[data-template-type]');
            if (templateTabBtn) {
                e.preventDefault();
                e.stopPropagation();
                const templateType = templateTabBtn.getAttribute('data-template-type');
                if (typeof window.showTemplateTab === 'function') {
                    window.showTemplateTab(templateType, templateTabBtn);
                }
                return;
            }

            // 模版「添加」按钮：不在此处处理，避免 stopPropagation 抢事件；由 template.js / 按钮 onclick 负责
            if (e.target.closest('#addDockerfileTemplateBtn')
                || e.target.closest('#addDeploymentTemplateBtn')
                || e.target.closest('#addServiceTemplateBtn')) {
                return;
            }

            // 检查是否点击了SSO标签页按钮
            const ssoTabBtn = e.target.closest('.sso-tab-btn[data-sso-type]');
            if (ssoTabBtn) {
                e.preventDefault();
                e.stopPropagation();
                const ssoType = ssoTabBtn.getAttribute('data-sso-type');
                console.log('SSO tab button clicked:', ssoType);

                if (typeof window.showSSOTab === 'function') {
                    try {
                        window.showSSOTab(ssoType, ssoTabBtn);
                    } catch (error) {
                        console.error('Error calling showSSOTab:', error);
                        notify('切换SSO标签页时出错: ' + error.message);
                    }
                } else {
                    console.error('showSSOTab function not available');
                    notify('SSO功能未加载完成，请刷新页面');
                }
                return;
            }
        });

        console.log('System buttons bound using event delegation');
        return true;
    }

    // 监听模块加载完成事件
    window.addEventListener('systemModuleLoaded', function() {
        console.log('System module loaded event received');
        bindPipelineButton();
        bindSystemButtons();
    });
    
    // 立即检查函数是否已可用（如果system.js已经加载完成）
    if (typeof window.showConfigTab === 'function') {
        console.log('System functions already available, binding immediately');
        bindSystemButtons();
    } else {
        console.log('System functions not yet available, will wait for event or retry');
    }
    
    // 延迟绑定（作为备用方案）
    setTimeout(function() {
        if (!bindPipelineButton()) {
            // 如果第一次绑定失败，继续重试
            let retries = 0;
            const retryInterval = setInterval(function() {
                if (bindPipelineButton() || retries >= 10) {
                    clearInterval(retryInterval);
                    if (retries >= 10) {
                        console.warn('Failed to bind pipeline button after 10 retries');
                    }
                }
                retries++;
            }, 200);
        }
    }, 500);
    
    // 延迟加载数据，等待认证完成
    setTimeout(() => {
        if (typeof loadTasks === 'function') loadTasks();
        if (typeof loadStatistics === 'function') loadStatistics();
        if (typeof loadSystemConfigs === 'function') loadSystemConfigs();
        
        // 检查当前激活的标签页，如果是流水线管理，则加载 pipeline 数据
        const activePipelineMgmtPanel = document.getElementById('pipeline-mgmt-config');
        if (activePipelineMgmtPanel && activePipelineMgmtPanel.classList.contains('active')) {
            if (typeof loadPipelines === 'function') {
                loadPipelines();
            }
        }
        
    }, 100);
    
    // 立即尝试绑定系统管理按钮（不等待函数加载，使用事件委托）
    setTimeout(function() {
        console.log('Attempting to bind system buttons immediately');
        bindSystemButtons();
    }, 200);
    
    // 延迟绑定系统管理按钮（作为备用方案，如果systemModuleLoaded事件没有触发）
    setTimeout(function() {
        console.log('Delayed binding check, showConfigTab available:', typeof window.showConfigTab);
        // 即使函数还没加载，也先绑定事件委托（事件委托会在点击时检查函数）
        bindSystemButtons();
        
        if (typeof window.showConfigTab !== 'function') {
            console.log('Functions not available, starting retry mechanism');
            // 如果函数还没加载，继续重试
            let retries = 0;
            const retryInterval = setInterval(function() {
                console.log(`Retry ${retries}: showConfigTab available:`, typeof window.showConfigTab);
                if (typeof window.showConfigTab === 'function' || retries >= 20) {
                    clearInterval(retryInterval);
                    if (typeof window.showConfigTab === 'function') {
                        console.log('Functions available after retry');
                    } else {
                        console.warn('Failed to load system functions after 20 retries');
                        console.log('Available window properties:', Object.keys(window).filter(k => k.toLowerCase().includes('show') || k.toLowerCase().includes('config')));
                    }
                }
                retries++;
            }, 200);
        }
    }, 500);
});
