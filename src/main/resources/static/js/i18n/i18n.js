/**
 * OpsFlow 前端 i18n（MVP）
 * - localStorage: opsflow.locale = zh | en
 * - HTML: data-i18n / data-i18n-placeholder / data-i18n-title
 * - JS: t('key') / setLocale('en')
 */
(function (global) {
    var STORAGE_KEY = 'opsflow.locale';

    var messages = {
        zh: {
            'app.name': 'OPSFLOW',
            'app.consoleTitle': '控制台 - OPSFLOW',
            'login.title': 'OPSFLOW',
            'login.username': '用户名',
            'login.password': '密码',
            'login.usernamePlaceholder': '请输入用户名',
            'login.passwordPlaceholder': '请输入密码',
            'login.submit': '登录',
            'login.or': '或',
            'login.feishu': '使用飞书登录',
            'login.failed': '登录失败，请检查用户名和密码',
            'login.retry': '登录失败，请稍后重试',

            'header.project': '项目',
            'header.allProjects': '全部项目',
            'header.projectSwitcherTitle': '切换后，服务 / 流水线 / 上线任务只显示该项目的数据',
            'header.inbox': '站内信',
            'header.inboxMarkAll': '全部已读',
            'header.inboxLoading': '加载中...',
            'header.user': '用户',
            'header.logout': '退出登录',
            'header.language': '语言',

            'menu.statistics': '工作台',
            'menu.pipeline': '工作视图',
            'menu.tasks': '上线任务',
            'menu.projects': '项目管理',
            'menu.services': '服务管理',
            'menu.environments': '环境配置',
            'menu.clusters': '集群管理',
            'menu.approval': '审批流程',
            'menu.nodes': '节点管理',
            'menu.users': '用户管理',
            'menu.audit': '操作审计',
            'menu.configGroup': '配置',
            'menu.pipelineConfig': '流水线配置',
            'menu.credentials': '凭据与组件',
            'menu.integrations': '集成与通知',
            'menu.branding': '品牌设置',
            'menu.license': 'License',

            'common.confirm': '确认',
            'common.cancel': '取消',
            'common.save': '保存',
            'common.delete': '删除',
            'common.edit': '编辑',
            'common.refresh': '刷新',
            'common.search': '搜索',
            'common.loading': '加载中...',
            'common.failed': '操作失败',
            'common.noPermission': '无权限',

            'license.feature.audit': '操作审计为 Pro 订阅功能，请导入有效 License',
            'license.feature.notify': '集成与通知为 Pro 订阅功能，请导入有效 License',
            'license.feature.deploy': '上线审批为 Pro 订阅功能，请导入有效 License',
            'license.feature.prodRollback': '生产环境回滚为 Pro 订阅功能，请导入含 prod_rollback 的 License',

            'branding.title': '企业品牌',
            'branding.hint': '上传 Logo 后将显示在登录页与控制台顶栏（PNG/JPG/WEBP/SVG，≤512KB）',
            'branding.nameLabel': '品牌名称',
            'branding.logoLabel': '企业 Logo',
            'branding.sizeHint': '建议尺寸：横版 440×112 px（宽高比不超过 4:1），透明背景的 PNG 或 SVG；文件不超过 512KB。登录页最大显示 220×56 px，顶栏最大显示 160×36 px，超出会等比缩小。',
            'branding.upload': '上传 Logo',
            'branding.clear': '清除 Logo',
            'branding.uploaded': '已上传自定义 Logo',
            'branding.empty': '尚未上传 Logo，将显示默认品牌名',
            'branding.needLicense': '企业品牌为 Pro 功能，请导入含 branding 的 License',
            'branding.pickFile': '请选择 Logo 文件',
            'branding.uploadOk': 'Logo 已更新',
            'branding.titleOk': '品牌名称已保存',
            'branding.clearConfirm': '确定清除自定义 Logo？',
            'branding.clearOk': '已清除自定义 Logo'
        },
        en: {
            'app.name': 'OPSFLOW',
            'app.consoleTitle': 'Console - OPSFLOW',
            'login.title': 'OPSFLOW',
            'login.username': 'Username',
            'login.password': 'Password',
            'login.usernamePlaceholder': 'Enter username',
            'login.passwordPlaceholder': 'Enter password',
            'login.submit': 'Sign in',
            'login.or': 'or',
            'login.feishu': 'Sign in with Feishu',
            'login.failed': 'Sign-in failed. Check username and password.',
            'login.retry': 'Sign-in failed. Please try again.',

            'header.project': 'Project',
            'header.allProjects': 'All projects',
            'header.projectSwitcherTitle': 'Services, pipelines and deploy tasks are filtered by the selected project',
            'header.inbox': 'Inbox',
            'header.inboxMarkAll': 'Mark all read',
            'header.inboxLoading': 'Loading...',
            'header.user': 'User',
            'header.logout': 'Sign out',
            'header.language': 'Language',

            'menu.statistics': 'Workbench',
            'menu.pipeline': 'Work View',
            'menu.tasks': 'Deploy Tasks',
            'menu.projects': 'Projects',
            'menu.services': 'Services',
            'menu.environments': 'Environments',
            'menu.clusters': 'Clusters',
            'menu.approval': 'Approvals',
            'menu.nodes': 'Nodes',
            'menu.users': 'Users',
            'menu.audit': 'Audit Log',
            'menu.configGroup': 'Settings',
            'menu.pipelineConfig': 'Pipeline Config',
            'menu.credentials': 'Credentials',
            'menu.integrations': 'Integrations',
            'menu.branding': 'Branding',
            'menu.license': 'License',

            'common.confirm': 'Confirm',
            'common.cancel': 'Cancel',
            'common.save': 'Save',
            'common.delete': 'Delete',
            'common.edit': 'Edit',
            'common.refresh': 'Refresh',
            'common.search': 'Search',
            'common.loading': 'Loading...',
            'common.failed': 'Operation failed',
            'common.noPermission': 'Permission denied',

            'license.feature.audit': 'Audit Log is a Pro feature. Import a valid License to unlock.',
            'license.feature.notify': 'Integrations is a Pro feature. Import a valid License to unlock.',
            'license.feature.deploy': 'Deploy approval is a Pro feature. Import a valid License to unlock.',
            'license.feature.prodRollback': 'Production rollback is a Pro feature. Import a License with prod_rollback.',

            'branding.title': 'Branding',
            'branding.hint': 'Upload a logo for the login page and console header (PNG/JPG/WEBP/SVG, ≤512KB)',
            'branding.nameLabel': 'Brand name',
            'branding.logoLabel': 'Company logo',
            'branding.sizeHint': 'Recommended: landscape 440×112 px (aspect ratio up to 4:1), transparent PNG or SVG, max 512KB. Displayed at up to 220×56 px on the login page and 160×36 px in the header; larger images are scaled down proportionally.',
            'branding.upload': 'Upload logo',
            'branding.clear': 'Clear logo',
            'branding.uploaded': 'Custom logo uploaded',
            'branding.empty': 'No custom logo yet; default brand name is shown',
            'branding.needLicense': 'Branding is a Pro feature. Import a License with branding.',
            'branding.pickFile': 'Please choose a logo file',
            'branding.uploadOk': 'Logo updated',
            'branding.titleOk': 'Brand name saved',
            'branding.clearConfirm': 'Clear the custom logo?',
            'branding.clearOk': 'Custom logo cleared'
        }
    };

    function normalizeLocale(locale) {
        if (!locale) return 'zh';
        var v = String(locale).toLowerCase();
        if (v.indexOf('en') === 0) return 'en';
        return 'zh';
    }

    function getLocale() {
        try {
            var saved = localStorage.getItem(STORAGE_KEY);
            if (saved === 'zh' || saved === 'en') {
                return saved;
            }
        } catch (e) { /* ignore */ }
        var nav = (navigator.language || navigator.userLanguage || 'zh').toLowerCase();
        return normalizeLocale(nav);
    }

    function t(key, vars) {
        if (!key) return '';
        var locale = getLocale();
        var catalog = messages[locale] || messages.zh;
        var text = (catalog && catalog[key]) || (messages.zh && messages.zh[key]) || key;
        if (vars && typeof vars === 'object') {
            Object.keys(vars).forEach(function (k) {
                text = text.replace(new RegExp('\\{' + k + '\\}', 'g'), String(vars[k]));
            });
        }
        return text;
    }

    function applyI18n(root) {
        var scope = root || document;
        if (!scope || !scope.querySelectorAll) return;

        scope.querySelectorAll('[data-i18n]').forEach(function (el) {
            var key = el.getAttribute('data-i18n');
            if (key) el.textContent = t(key);
        });
        scope.querySelectorAll('[data-i18n-placeholder]').forEach(function (el) {
            var key = el.getAttribute('data-i18n-placeholder');
            if (key) el.setAttribute('placeholder', t(key));
        });
        scope.querySelectorAll('[data-i18n-title]').forEach(function (el) {
            var key = el.getAttribute('data-i18n-title');
            if (key) el.setAttribute('title', t(key));
        });

        var switcher = document.getElementById('localeSwitcher');
        if (switcher) {
            switcher.value = getLocale();
        }

        var locale = getLocale();
        if (document.documentElement) {
            document.documentElement.lang = locale === 'en' ? 'en' : 'zh-CN';
        }
        if (document.title && document.body && document.body.classList.contains('dashboard-page')) {
            document.title = t('app.consoleTitle');
        }
    }

    function setLocale(locale, options) {
        var next = normalizeLocale(locale);
        try {
            localStorage.setItem(STORAGE_KEY, next);
        } catch (e) { /* ignore */ }
        applyI18n(document);
        if (typeof global.onLocaleChange === 'function') {
            try {
                global.onLocaleChange(next, options || {});
            } catch (e) {
                console.warn('onLocaleChange failed', e);
            }
        }
        return next;
    }

    function onLocaleSwitcherChange(selectEl) {
        if (!selectEl) return;
        setLocale(selectEl.value);
    }

    // 启动时同步 html lang，不强制刷 DOM（由各页显式 apply）
    try {
        var bootLocale = getLocale();
        if (document.documentElement) {
            document.documentElement.lang = bootLocale === 'en' ? 'en' : 'zh-CN';
        }
    } catch (e) { /* ignore */ }

    global.t = t;
    global.getLocale = getLocale;
    global.setLocale = setLocale;
    global.applyI18n = applyI18n;
    global.onLocaleSwitcherChange = onLocaleSwitcherChange;
    global.OpsFlowI18n = {
        t: t,
        getLocale: getLocale,
        setLocale: setLocale,
        applyI18n: applyI18n,
        messages: messages
    };
})(window);
