// ============================================
// 前端扩展加载：按 /api/ui/extensions 插入菜单、页面片段、脚本、样式和文案
// - 每个扩展独立加载，任何一步失败都回滚该扩展已插入的内容，不影响核心页面
// - 页签（tabs）插进已有页面的页签栏（#<section>-section 里的 .system-config-tabs），按 data-order 排序，
//   面板 id 为 <tab>-config，与核心页签一样由 showConfigTab 切换；加载函数用 registerConfigTabLoader 登记
// - 菜单项与核心菜单一样带 data-section / data-permission / data-feature，
//   可见性和深链拦截完全沿用 core.js 的 applyPermissionUI / applyFeatureUI / canAccessSection
// - 不另做缓存：静态资源由服务端 Cache-Control: no-cache 走 304 校验；清单每次进入控制台拉取一次
// ============================================
(function (global) {
    const SVG_ATTRS = 'viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" '
        + 'stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"';

    const state = {
        manifest: null,
        loaded: [],
        failed: []
    };

    async function fetchOk(url, asJson) {
        const resp = await fetch(url, { credentials: 'same-origin' });
        if (!resp.ok) {
            throw new Error(url + ' HTTP ' + resp.status);
        }
        return asJson ? resp.json() : resp.text();
    }

    function mergeMessages(bundle, extId) {
        const i18n = global.OpsFlowI18n;
        if (!i18n || !i18n.messages || !bundle || typeof bundle !== 'object') {
            return;
        }
        Object.keys(bundle).forEach(locale => {
            const target = i18n.messages[locale] || (i18n.messages[locale] = {});
            const entries = bundle[locale] || {};
            Object.keys(entries).forEach(key => {
                if (Object.prototype.hasOwnProperty.call(target, key)) {
                    console.warn('[ui-ext] ' + extId + ' 文案 key 已存在，保留核心值：' + key); // i18n-ignore
                    return;
                }
                target[key] = entries[key];
            });
        });
    }

    function loadTag(tag, attrs, created) {
        return new Promise((resolve, reject) => {
            const el = document.createElement(tag);
            Object.keys(attrs).forEach(k => el.setAttribute(k, attrs[k]));
            el.onload = () => resolve(el);
            el.onerror = () => reject(new Error((attrs.src || attrs.href) + ' 加载失败')); // i18n-ignore
            created.push(el);
            (tag === 'link' ? document.head : document.body).appendChild(el);
        });
    }

    function mainMenu() {
        return document.querySelector('.sidebar .menu-section');
    }

    /** 插入位置：after 指定的菜单之后（同一 after 的扩展菜单按 order 依次往后排）；找不到时放在主菜单「系统设置」分组之前 */
    function insertMenuItem(menuEl, item, tails) {
        const container = mainMenu();
        if (!container) {
            throw new Error('找不到菜单容器'); // i18n-ignore
        }
        const key = item.after || '';
        let anchor = tails[key];
        if (!anchor && item.after) {
            anchor = container.querySelector(`.menu-item[data-section="${item.after}"]`);
        }
        if (anchor) {
            // 插在「系统设置」分组里的子菜单后面时，样式跟着用子菜单的缩进
            if (anchor.classList.contains('menu-item-sub')) {
                menuEl.classList.add('menu-item-sub');
            }
            anchor.insertAdjacentElement('afterend', menuEl);
        } else {
            const group = container.querySelector(':scope > .menu-group');
            container.insertBefore(menuEl, group || null);
        }
        tails[key] = menuEl;
    }

    function buildMenuItem(item) {
        const a = document.createElement('a');
        a.href = item.path;
        a.className = 'menu-item';
        a.dataset.section = item.section;
        a.dataset.extension = 'true';
        if (item.after) a.dataset.after = item.after;
        if (item.permission) a.setAttribute('data-permission', item.permission);
        if (item.feature) a.setAttribute('data-feature', item.feature);
        const icon = document.createElement('span');
        icon.className = 'menu-icon';
        icon.innerHTML = `<svg ${SVG_ATTRS}>${/* html-safe: 图标是后端扩展声明里写死的 SVG 片段，不是用户数据 */ item.icon || ''}</svg>`;
        const label = document.createElement('span');
        label.setAttribute('data-i18n', item.labelKey);
        label.textContent = typeof t === 'function' ? t(item.labelKey) : item.labelKey;
        a.appendChild(icon);
        a.appendChild(label);
        return a;
    }

    function orderOf(el) {
        const v = el.dataset ? el.dataset.order : undefined;
        return v === undefined || v === '' ? null : Number(v);
    }

    /**
     * 目标页面的页签栏和内容区；找不到时抛错（整个扩展回滚）。
     * 页签 id 已被占用（按钮或 <tab>-config 面板已存在）同样抛错，避免两个面板同 id。
     */
    function tabHost(tab) {
        const section = document.getElementById(tab.section + '-section');
        if (!section) {
            throw new Error('页签的目标页面不存在：' + tab.section); // i18n-ignore
        }
        const bar = section.querySelector('.system-config-tabs > .tab-buttons');
        const content = section.querySelector('.system-config-tabs > .tab-content');
        if (!bar || !content) {
            throw new Error('目标页面没有页签栏：' + tab.section); // i18n-ignore
        }
        if (bar.querySelector(`.tab-btn[data-tab="${tab.tab}"]`) || document.getElementById(tab.tab + '-config')) {
            throw new Error('页签已存在：' + tab.tab); // i18n-ignore
        }
        return { bar, content };
    }

    function buildTabButton(tab, extId) {
        const btn = document.createElement('button');
        btn.type = 'button';
        btn.className = 'tab-btn';
        btn.dataset.tab = tab.tab;
        btn.dataset.order = String(tab.order || 0);
        btn.dataset.extension = extId;
        if (tab.permission) btn.setAttribute('data-permission', tab.permission);
        if (tab.feature) btn.setAttribute('data-feature', tab.feature);
        btn.setAttribute('data-i18n', tab.labelKey);
        btn.textContent = typeof t === 'function' ? t(tab.labelKey) : tab.labelKey;
        return btn;
    }

    /**
     * 按 order 插入页签按钮：放在第一个 order 更大的按钮之前，相同 order 排在已有按钮之后
     * （先核心、再按扩展 id、再按声明顺序）。没有 data-order 的按钮不参与比较。
     */
    function insertTabButton(bar, btn, order) {
        const next = Array.from(bar.querySelectorAll(':scope > .tab-btn'))
            .find(el => el !== btn && orderOf(el) !== null && orderOf(el) > order);
        bar.insertBefore(btn, next || null);
    }

    /** 页签加载函数按名字延迟查找：切到该页签时才调用，脚本里稍后定义的函数也能用 */
    function tabLoader(name) {
        return function (scope) {
            const fn = global[name];
            if (typeof fn === 'function') {
                return fn(scope);
            }
            console.error('[ui-ext] 页签加载函数不存在：' + name); // i18n-ignore
        };
    }

    async function loadTabs(ext, created, tabLoaders) {
        const tabs = (ext.tabs || []).slice().sort((x, y) => (x.order || 0) - (y.order || 0));
        for (const tab of tabs) {
            const host = tabHost(tab);
            const html = tab.fragment ? await fetchOk(tab.fragment, false) : '';
            const btn = buildTabButton(tab, ext.id);
            const panel = document.createElement('div');
            panel.id = tab.tab + '-config';
            panel.className = 'config-panel';
            panel.dataset.extension = ext.id;
            panel.innerHTML = html;
            insertTabButton(host.bar, btn, tab.order || 0);
            host.content.appendChild(panel);
            created.push(btn, panel);
            if (tab.loader) {
                if (typeof registerConfigTabLoader !== 'function') {
                    throw new Error('core 未提供 registerConfigTabLoader'); // i18n-ignore
                }
                const loader = tabLoader(tab.loader);
                registerConfigTabLoader(tab.tab, loader);
                tabLoaders.push([tab.tab, loader]);
            }
        }
    }

    async function loadExtension(ext, tails) {
        const created = [];
        const registered = [];
        const tabLoaders = [];
        const tailsBefore = Object.assign({}, tails);
        try {
            for (const url of ext.messageBundles || []) {
                mergeMessages(await fetchOk(url, true), ext.id);
            }
            for (const url of ext.styles || []) {
                await loadTag('link', { rel: 'stylesheet', href: url }, created);
            }
            const main = document.querySelector('.main-content');
            for (const fragment of ext.fragments || []) {
                const html = await fetchOk(fragment.url, false);
                const id = fragment.section + '-section';
                if (document.getElementById(id)) {
                    throw new Error('区块已存在：' + id); // i18n-ignore
                }
                const section = document.createElement('div');
                section.id = id;
                section.className = 'content-section';
                section.dataset.extension = ext.id;
                section.innerHTML = html;
                if (!main) {
                    throw new Error('找不到内容区容器'); // i18n-ignore
                }
                main.appendChild(section);
                created.push(section);
            }
            // 脚本按顺序加载，前一个执行完再加载下一个
            for (const url of ext.scripts || []) {
                await loadTag('script', { src: url }, created);
            }
            const items = (ext.menuItems || []).slice().sort((x, y) => (x.order || 0) - (y.order || 0));
            for (const item of items) {
                if (typeof registerSection !== 'function') {
                    throw new Error('core.js 未提供 registerSection'); // i18n-ignore
                }
                registerSection({ section: item.section, path: item.path, labelKey: item.labelKey, loader: item.loader });
                registered.push(item.section);
                const menuEl = buildMenuItem(item);
                insertMenuItem(menuEl, item, tails);
                created.push(menuEl);
            }
            await loadTabs(ext, created, tabLoaders);
            created.forEach(el => {
                if (typeof applyI18n === 'function' && el.nodeType === 1 && el.tagName !== 'SCRIPT' && el.tagName !== 'LINK') {
                    applyI18n(el);
                }
            });
            state.loaded.push(ext.id);
        } catch (e) {
            console.error('[ui-ext] 扩展 ' + ext.id + ' 加载失败，已回滚', e); // i18n-ignore
            registered.forEach(section => {
                if (typeof unregisterSection === 'function') unregisterSection(section);
            });
            tabLoaders.forEach(([tab, loader]) => {
                if (typeof unregisterConfigTabLoader === 'function') unregisterConfigTabLoader(tab, loader);
            });
            created.forEach(el => el.remove());
            Object.keys(tails).forEach(k => delete tails[k]);
            Object.assign(tails, tailsBefore);
            state.failed.push(ext.id);
        }
    }

    /** 只加载一次：checkAuth 会被 core.js 和 dashboard.js 各调用一次，第二个调用方复用同一个 Promise */
    function loadUiExtensions() {
        if (!state.promise) {
            state.promise = doLoad();
        }
        return state.promise;
    }

    /**
     * 全部扩展加载完后再按 after 摆一遍菜单：扩展按 id 顺序加载，锚点可能是后加载的扩展菜单
     * （如扩展菜单 B 的锚点是扩展菜单 A）。同一锚点的多个菜单保持声明顺序。
     */
    function settleMenuOrder() {
        const items = Array.from(document.querySelectorAll('.sidebar .menu-item[data-extension][data-after]'));
        for (let pass = 0; pass <= items.length; pass++) {
            let moved = false;
            items.forEach(el => {
                const anchor = document.querySelector(`.sidebar .menu-item[data-section="${el.dataset.after}"]`);
                if (!anchor || anchor === el) return;
                let pos = anchor;
                let next = pos.nextElementSibling;
                while (next && next !== el && next.dataset && next.dataset.after === el.dataset.after
                        && items.indexOf(next) >= 0 && items.indexOf(next) < items.indexOf(el)) {
                    pos = next;
                    next = pos.nextElementSibling;
                }
                if (pos.nextElementSibling !== el) {
                    pos.insertAdjacentElement('afterend', el);
                    el.classList.toggle('menu-item-sub', anchor.classList.contains('menu-item-sub'));
                    moved = true;
                }
            });
            if (!moved) break;
        }
    }

    async function doLoad() {
        let manifest;
        try {
            manifest = await fetchOk('/api/ui/extensions', true);
        } catch (e) {
            console.warn('[ui-ext] 扩展清单加载失败，按无扩展处理', e); // i18n-ignore
            manifest = { extensions: [] };
        }
        state.manifest = manifest;
        const tails = {};
        for (const ext of (manifest && manifest.extensions) || []) {
            if (ext && ext.id) {
                await loadExtension(ext, tails);
            }
        }
        settleMenuOrder();
        return state;
    }

    global.loadUiExtensions = loadUiExtensions;
    global.OpsFlowExtensions = {
        load: loadUiExtensions,
        get manifest() { return state.manifest; },
        get loaded() { return state.loaded.slice(); },
        get failed() { return state.failed.slice(); }
    };
})(window);
