// ============================================
// 核心页面路由表（唯一来源）
// - dashboard.html 头部脚本用它决定首屏显示哪个区块；core.js 用它生成路由、标题和进入页面时的加载动作
// - 加一个核心页面：这里加一行，dashboard.html 加菜单项和区块（#<section>-section），
//   i18n.js 加标题文案，DashboardController 加路径；node scripts/dev/check-routes.js 会核对这几处
// - 企业版等扩展页面不写在这里，由后端 UiExtension 声明、js/ui-extensions.js 调 registerSection 登记
//
// 字段：
//   section     页面标识，对应菜单的 data-section 和区块 #<section>-section
//   path        浏览器地址
//   titleKey    页面标题（浏览器标签）的 i18n key
//   loader      进入页面时调用的全局函数名
//   defaultTab  配置类页面：进入时激活的默认页签（没有已激活页签时），与 loader 二选一
//   aliases     旧地址，前端按同一页面处理（后端另有重定向）
//   home        访问 / 或未知地址时显示的页面，只能有一个
// ============================================
window.OPSFLOW_CORE_ROUTES = Object.freeze([
    { section: 'statistics', path: '/statistics', titleKey: 'menu.statistics', loader: 'loadStatistics', home: true },
    { section: 'pipeline', path: '/pipeline', titleKey: 'menu.pipeline', loader: 'loadPipelineJobsView' },
    { section: 'projects', path: '/projects', titleKey: 'menu.projects', loader: 'loadProjects' },
    { section: 'services', path: '/services', titleKey: 'menu.services', loader: 'loadServices' },
    { section: 'environments', path: '/environments', titleKey: 'menu.environments', loader: 'loadEnvironments' },
    { section: 'clusters', path: '/clusters', titleKey: 'menu.clusters', loader: 'loadClusters' },
    { section: 'nodes', path: '/nodes', titleKey: 'menu.nodes', loader: 'loadNodes' },
    { section: 'users', path: '/users', titleKey: 'menu.users', loader: 'loadUsersPage' },
    { section: 'pipeline-config', path: '/pipeline-config', titleKey: 'menu.pipelineConfig', defaultTab: 'step-def', aliases: ['/system'] },
    { section: 'credentials-config', path: '/credentials', titleKey: 'menu.credentials', defaultTab: 'keychain' },
    { section: 'auth-config', path: '/authentication', titleKey: 'menu.auth', defaultTab: 'ldap', aliases: ['/ldap'] },
    { section: 'security-config', path: '/security', titleKey: 'menu.security', defaultTab: 'two-factor' }
].map(Object.freeze));
