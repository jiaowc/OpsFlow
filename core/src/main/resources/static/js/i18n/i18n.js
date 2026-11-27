/**
 * OpsFlow 前端 i18n（MVP）
 * - localStorage: opsflow.locale = zh | en
 * - HTML: data-i18n / data-i18n-placeholder / data-i18n-title
 * - JS: t('key') / t('key', {name: value}) / setLocale('en')
 * - 模块文案：js/i18n/messages/<模块>.js 里调用 OpsFlowI18n.register({ zh: {...}, en: {...} })，
 *   这里只放公共文案（app / login / header / menu / common / password 等）
 * - 不需要翻译的中文（如语言名）在元素上加 data-i18n-skip；JS 里在行尾加 // i18n-ignore
 * - 检查：node scripts/dev/check-i18n.js
 */
(function (global) {
    var STORAGE_KEY = 'opsflow.locale';

    var messages = {
        zh: {
            'app.name': 'OPSFLOW',
            'app.consoleTitle': '控制台 - OPSFLOW',
            'login.title': 'OPSFLOW',
            'login.lead.line1': '从代码提交到上线，',
            'login.lead.line2': '一条流水线走完。',
            'login.leadSub': '构建、部署和发布记录集中在一处，每一步都能追溯。',
            'login.flow.checkout': '拉取代码',
            'login.flow.build': '构建镜像',
            'login.flow.push': '推送仓库',
            'login.flow.deploy': '部署到测试环境',
            'login.flow.running': '进行中',
            'login.cardTitle': '登录',
            'login.cardHint': '使用账号密码登录控制台',
            'login.username': '用户名',
            'login.password': '密码',
            'login.usernamePlaceholder': '请输入用户名',
            'login.passwordPlaceholder': '请输入密码',
            'login.submit': '登录',
            'login.failed': '登录失败，请检查用户名和密码',
            'login.retry': '登录失败，请稍后重试',
            // 登录第二步（两步验证）：登录页只加载本文件，相关文案放在这里
            'login.twoFactor.title': '两步验证',
            'login.twoFactor.hint': '打开手机上的验证器 App，输入账号 {username} 的 6 位动态码。',
            'login.twoFactor.recoveryHint': '输入一个未用过的恢复码（格式 xxxx-xxxx），每个恢复码只能用一次。',
            'login.twoFactor.codeLabel': '动态码',
            'login.twoFactor.recoveryLabel': '恢复码',
            'login.twoFactor.verify': '验证',
            'login.twoFactor.useRecovery': '改用恢复码',
            'login.twoFactor.useCode': '改用动态码',
            'login.twoFactor.back': '返回重新登录',
            'login.twoFactor.empty': '请输入动态码',
            'login.twoFactor.emptyRecovery': '请输入恢复码',
            'twoFactor.err.codeInvalid': '动态码不正确，还可以再试 {n} 次。',
            'twoFactor.err.recoveryInvalid': '恢复码不正确或已经用过，还可以再试 {n} 次。',
            'twoFactor.err.tooMany': '尝试次数过多，请重新输入密码登录。',
            'twoFactor.err.expired': '验证已超时，请重新输入密码登录。',

            'header.project': '项目',
            'header.allProjects': '全部项目',
            'header.projectSwitcherTitle': '切换后，服务 / 流水线 / 上线任务只显示该项目的数据',
            'header.inbox': '站内信',
            'header.inboxMarkAll': '全部已读',
            'header.inboxLoading': '加载中...',
            'header.user': '用户',
            'header.logout': '退出登录',
            'header.language': '语言',

            'app.console': '控制台',
            'app.version.buildTime': '构建时间: {time}',
            'app.version.artifact': '产物: {artifact}',
            'app.version.current': '当前版本 {version}',
            'app.source.notice': 'OpsFlow 社区版 {version} · AGPLv3',
            'app.source.link': '源码',

            'header.changePassword': '修改密码',
            'header.twoFactor': '两步验证',
            'inbox.loading': '加载中...',
            'inbox.loadFailed': '加载失败',
            'inbox.empty': '暂无站内信',
            'inbox.defaultTitle': '通知',

            'password.title': '修改密码',
            'password.forceTitle': '请先修改初始密码',
            'password.forceHint': '当前密码过于简单（弱口令），修改后才能继续使用系统。',
            'password.old': '旧密码',
            'password.new': '新密码',
            'password.newPlaceholder': '至少 8 位，不能为常见弱口令或与用户名相同',
            'password.confirm': '确认新密码',
            'password.submit': '确认修改',
            'password.errEmpty': '旧密码和新密码不能为空',
            'password.errTooShort': '新密码长度不能少于 8 位',
            'password.errMismatch': '两次输入的新密码不一致',
            'password.ok': '密码修改成功',
            'password.failed': '修改失败',
            'password.failedWith': '修改失败：{reason}',

            'common.close': '关闭',
            'common.confirmTitle': '请确认',
            'common.inputTitle': '请输入',
            'common.required': '此项不能为空',
            'common.failedWith': '操作失败: {reason}',
            'common.unknownError': '未知错误',
            'common.networkError': '网络错误',
            'common.notLoaded': '页面功能未加载，请刷新页面',
            'common.enabled': '启用',
            'common.disabled': '禁用',
            'common.selectEnv': '请选择环境',
            'common.selectService': '请选择服务',
            'common.modify': '修改',
            'common.remove': '移除',
            'common.actions': '操作',
            'common.status': '状态',
            'common.description': '描述',
            'common.type': '类型',
            'common.createOk': '创建成功',
            'common.createFailed': '创建失败',
            'common.updateOk': '修改成功',
            'common.updateFailed': '修改失败',
            'common.updatedOk': '更新成功',
            'common.updatedFailed': '更新失败',
            'common.deleteOk': '删除成功',
            'common.deleteFailed': '删除失败',
            'common.saveOk': '保存成功',
            'common.saveFailed': '保存失败',
            'common.addFailed': '添加失败',
            'common.loadFailed': '加载失败',
            'common.select': '请选择',
            'common.all': '全部',
            'common.notSet': '不设置',

            'menu.statistics': '工作台',
            'menu.pipeline': '工作视图',
            'menu.projects': '项目管理',
            'menu.services': '服务管理',
            'menu.environments': '环境配置',
            'menu.clusters': '集群管理',
            'menu.nodes': '节点管理',
            'menu.users': '用户管理',
            'menu.configGroup': '系统设置',
            'menu.pipelineConfig': '流水线配置',
            'menu.credentials': '凭据与集成',
            'menu.auth': '登录方式',
            'menu.ldap': 'LDAP',
            'menu.security': '安全设置',

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

            'license.feature.generic': '该功能为企业版功能，请导入有效 License',
        },
        en: {
            'app.name': 'OPSFLOW',
            'app.consoleTitle': 'Console - OPSFLOW',
            'login.title': 'OPSFLOW',
            'login.lead.line1': 'From commit to release,',
            'login.lead.line2': 'one pipeline all the way.',
            'login.leadSub': 'Builds, deployments and release history in one place, every step traceable.',
            'login.flow.checkout': 'Check out code',
            'login.flow.build': 'Build image',
            'login.flow.push': 'Push to registry',
            'login.flow.deploy': 'Deploy to test',
            'login.flow.running': 'Running',
            'login.cardTitle': 'Sign in',
            'login.cardHint': 'Use your account to sign in to the console',
            'login.username': 'Username',
            'login.password': 'Password',
            'login.usernamePlaceholder': 'Enter username',
            'login.passwordPlaceholder': 'Enter password',
            'login.submit': 'Sign in',
            'login.failed': 'Sign-in failed. Check username and password.',
            'login.retry': 'Sign-in failed. Please try again.',
            'login.twoFactor.title': 'Two-step verification',
            'login.twoFactor.hint': 'Open your authenticator app and enter the 6-digit code for {username}.',
            'login.twoFactor.recoveryHint': 'Enter an unused recovery code (xxxx-xxxx). Each recovery code works once.',
            'login.twoFactor.codeLabel': 'Code',
            'login.twoFactor.recoveryLabel': 'Recovery code',
            'login.twoFactor.verify': 'Verify',
            'login.twoFactor.useRecovery': 'Use a recovery code',
            'login.twoFactor.useCode': 'Use a code instead',
            'login.twoFactor.back': 'Back to sign in',
            'login.twoFactor.empty': 'Enter the code',
            'login.twoFactor.emptyRecovery': 'Enter a recovery code',
            'twoFactor.err.codeInvalid': 'That code is incorrect. {n} attempts left.',
            'twoFactor.err.recoveryInvalid': 'That recovery code is incorrect or already used. {n} attempts left.',
            'twoFactor.err.tooMany': 'Too many attempts. Sign in with your password again.',
            'twoFactor.err.expired': 'Verification timed out. Sign in with your password again.',

            'header.project': 'Project',
            'header.allProjects': 'All projects',
            'header.projectSwitcherTitle': 'Services, pipelines and deploy tasks are filtered by the selected project',
            'header.inbox': 'Inbox',
            'header.inboxMarkAll': 'Mark all read',
            'header.inboxLoading': 'Loading...',
            'header.user': 'User',
            'header.logout': 'Sign out',
            'header.language': 'Language',

            'app.console': 'Console',
            'app.version.buildTime': 'Built: {time}',
            'app.version.artifact': 'Artifact: {artifact}',
            'app.version.current': 'Version {version}',
            'app.source.notice': 'OpsFlow Community Edition {version} · AGPLv3',
            'app.source.link': 'Source code',

            'header.changePassword': 'Change password',
            'header.twoFactor': 'Two-step verification',
            'inbox.loading': 'Loading...',
            'inbox.loadFailed': 'Failed to load',
            'inbox.empty': 'No messages',
            'inbox.defaultTitle': 'Notification',

            'password.title': 'Change password',
            'password.forceTitle': 'Change your initial password first',
            'password.forceHint': 'Your current password is too weak. Change it to continue.',
            'password.old': 'Current password',
            'password.new': 'New password',
            'password.newPlaceholder': 'At least 8 characters; not a common weak password or your username',
            'password.confirm': 'Confirm new password',
            'password.submit': 'Update password',
            'password.errEmpty': 'Current and new passwords are required',
            'password.errTooShort': 'The new password must be at least 8 characters',
            'password.errMismatch': 'The new passwords do not match',
            'password.ok': 'Password changed successfully',
            'password.failed': 'Failed to change password',
            'password.failedWith': 'Failed to change password: {reason}',

            'common.close': 'Close',
            'common.confirmTitle': 'Please confirm',
            'common.inputTitle': 'Enter a value',
            'common.required': 'This field is required',
            'common.failedWith': 'Operation failed: {reason}',
            'common.unknownError': 'Unknown error',
            'common.networkError': 'Network error',
            'common.notLoaded': 'This feature has not loaded. Please refresh the page.',
            'common.enabled': 'Enabled',
            'common.disabled': 'Disabled',
            'common.selectEnv': 'Select an environment',
            'common.selectService': 'Select a service',
            'common.modify': 'Edit',
            'common.remove': 'Remove',
            'common.actions': 'Actions',
            'common.status': 'Status',
            'common.description': 'Description',
            'common.type': 'Type',
            'common.createOk': 'Created successfully',
            'common.createFailed': 'Failed to create',
            'common.updateOk': 'Updated successfully',
            'common.updateFailed': 'Failed to update',
            'common.updatedOk': 'Updated successfully',
            'common.updatedFailed': 'Failed to update',
            'common.deleteOk': 'Deleted successfully',
            'common.deleteFailed': 'Failed to delete',
            'common.saveOk': 'Saved successfully',
            'common.saveFailed': 'Failed to save',
            'common.addFailed': 'Failed to add',
            'common.loadFailed': 'Failed to load',
            'common.select': 'Select',
            'common.all': 'All',
            'common.notSet': 'Not set',

            'menu.statistics': 'Workbench',
            'menu.pipeline': 'Work View',
            'menu.projects': 'Projects',
            'menu.services': 'Services',
            'menu.environments': 'Environments',
            'menu.clusters': 'Clusters',
            'menu.nodes': 'Nodes',
            'menu.users': 'Users',
            'menu.configGroup': 'System settings',
            'menu.pipelineConfig': 'Pipeline Config',
            'menu.credentials': 'Credentials & Integrations',
            'menu.auth': 'Sign-in Methods',
            'menu.ldap': 'LDAP',
            'menu.security': 'Security',

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

            'license.feature.generic': 'This is an Enterprise feature. Import a valid License to unlock.',
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

    /**
     * 登记一个模块的文案：{ zh: { key: text }, en: { key: text } }。
     * key 已存在时保留先登记的值并告警，避免模块之间互相覆盖。
     */
    function register(bundle) {
        if (!bundle || typeof bundle !== 'object') return;
        Object.keys(bundle).forEach(function (locale) {
            var target = messages[locale] || (messages[locale] = {});
            var entries = bundle[locale] || {};
            Object.keys(entries).forEach(function (key) {
                if (Object.prototype.hasOwnProperty.call(target, key)) {
                    console.warn('[i18n] duplicate key ignored: ' + locale + ' ' + key);
                    return;
                }
                target[key] = entries[key];
            });
        });
    }

    /** 日期、数字格式化用的 BCP 47 语言标签 */
    function getDateLocale() {
        return getLocale() === 'en' ? 'en-US' : 'zh-CN';
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
        // 只在整页刷新文案时重置标题；局部刷新（如某个区块）不能覆盖当前页面的标题
        if (scope === document && document.title && document.body && document.body.classList.contains('dashboard-page')) {
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
    global.getDateLocale = getDateLocale;
    global.setLocale = setLocale;
    global.applyI18n = applyI18n;
    global.onLocaleSwitcherChange = onLocaleSwitcherChange;
    global.OpsFlowI18n = {
        t: t,
        getLocale: getLocale,
        setLocale: setLocale,
        applyI18n: applyI18n,
        register: register,
        getDateLocale: getDateLocale,
        messages: messages
    };
})(window);
