#!/usr/bin/env node
/**
 * 核心页面路由一致性检查（只读，不改任何文件）。
 *
 *   node scripts/dev/check-routes.js
 *   node scripts/dev/check-routes.js --ext-module <模块目录>   指定带扩展页面的模块（可写多次；不指定时自动发现，见 ext-modules.js）
 *
 * 以 static/js/routes.js（核心页面路由表）为准，核对：
 *   1. 路由表本身：section、path、别名不重复；有且只有一个 home；loader 与 defaultTab 二选一
 *   2. dashboard.html：每个页面有菜单项（href = path，data-section = section）和区块 #<section>-section；
 *      菜单项和区块里没有路由表之外的核心页面
 *   3. 标题文案 titleKey 在中英文里都有
 *   4. loader 是 static/js 下某个脚本定义的全局函数
 *   5. DashboardController：转发路径与路由表的 path 完全一致；别名有对应的映射
 *   6. 与扩展模块（其 Java 源码里的 UiExtension 菜单声明）的 section / path 不冲突（按源码文本匹配，尽力而为）
 * 有问题时退出码为 1。
 */
'use strict';

const fs = require('fs');
const path = require('path');
const vm = require('vm');

const repoRoot = path.resolve(__dirname, '..', '..');
const staticDir = [
    'opsflow-core/src/main/resources/static',
    'src/main/resources/static'
].map(p => path.join(repoRoot, p)).find(p => fs.existsSync(path.join(p, 'js/routes.js')));
if (!staticDir) {
    console.error('找不到 static 目录（js/routes.js）');
    process.exit(2);
}
const javaRoot = path.join(staticDir, '..', '..', 'java');
const { findExtModules, moduleLabel } = require('./ext-modules');
let extModules;
try {
    ({ modules: extModules } = findExtModules(repoRoot, process.argv.slice(2)));
} catch (e) {
    console.error(e.message);
    process.exit(2);
}
const errors = [];
const read = f => fs.readFileSync(f, 'utf8');

function walk(dir, ext, out) {
    if (!fs.existsSync(dir)) return out;
    for (const name of fs.readdirSync(dir).sort()) {
        const full = path.join(dir, name);
        if (fs.statSync(full).isDirectory()) walk(full, ext, out);
        else if (name.endsWith(ext)) out.push(full);
    }
    return out;
}

// ---- 1. 路由表
const sandbox = { window: {} };
vm.createContext(sandbox);
vm.runInContext(read(path.join(staticDir, 'js/routes.js')), sandbox);
const routes = sandbox.window.OPSFLOW_CORE_ROUTES || [];
if (!routes.length) errors.push('routes.js 里没有路由');

const sections = new Set();
const paths = new Set();
for (const r of routes) {
    if (!/^[a-z0-9][a-z0-9-]*$/.test(r.section || '')) errors.push(`section 不合法: ${r.section}`);
    if (!/^\/[a-z0-9][a-z0-9-]*$/.test(r.path || '')) errors.push(`${r.section}: path 不合法: ${r.path}`);
    if (sections.has(r.section)) errors.push(`section 重复: ${r.section}`);
    sections.add(r.section);
    for (const p of [r.path].concat(r.aliases || [])) {
        if (paths.has(p)) errors.push(`路径重复: ${p}`);
        paths.add(p);
    }
    if (!r.titleKey) errors.push(`${r.section}: 缺少 titleKey`);
    if (!!r.loader === !!r.defaultTab) errors.push(`${r.section}: loader 和 defaultTab 必须且只能有一个`);
}
const homes = routes.filter(r => r.home);
if (homes.length !== 1) errors.push(`home 页面应有且只有一个，实际 ${homes.length} 个`);

// ---- 2. dashboard.html
const html = read(path.join(staticDir, 'dashboard.html'));
const menuItems = [...html.matchAll(/<a\b[^>]*class="menu-item[^"]*"[^>]*>/g)].map(m => {
    const tag = m[0];
    return {
        href: (tag.match(/\bhref="([^"]*)"/) || [])[1],
        section: (tag.match(/\bdata-section="([^"]*)"/) || [])[1]
    };
}).filter(m => m.section);
const sectionDivs = new Set([...html.matchAll(/\bid="([a-z0-9-]+)-section"/g)].map(m => m[1]));
const tabPanes = new Map([...html.matchAll(/data-tab="([\w-]+)"/g)].map(m => [m[1], true]));

for (const r of routes) {
    const menu = menuItems.filter(m => m.section === r.section);
    if (menu.length !== 1) {
        errors.push(`dashboard.html: data-section="${r.section}" 的菜单项应有 1 个，实际 ${menu.length} 个`);
    } else if (menu[0].href !== r.path) {
        errors.push(`dashboard.html: 菜单 ${r.section} 的 href 是 ${menu[0].href}，路由表是 ${r.path}`);
    }
    if (!sectionDivs.has(r.section)) errors.push(`dashboard.html: 缺少区块 #${r.section}-section`);
    if (r.defaultTab && !tabPanes.has(r.defaultTab)) errors.push(`dashboard.html: 找不到默认页签 data-tab="${r.defaultTab}"（${r.section}）`);
}
for (const m of menuItems) {
    if (!sections.has(m.section)) errors.push(`dashboard.html: 菜单项 ${m.section} 不在 routes.js 里`);
}
for (const s of sectionDivs) {
    if (!sections.has(s)) errors.push(`dashboard.html: 区块 #${s}-section 不在 routes.js 里`);
}

// ---- 3. 标题文案
const i18nBox = {
    console: { warn() {}, log() {}, error() {} },
    localStorage: { getItem: () => null, setItem() {} },
    navigator: { language: 'zh-CN' },
    document: { documentElement: {}, getElementById: () => null, querySelectorAll: () => [] }
};
i18nBox.window = i18nBox;
vm.createContext(i18nBox);
vm.runInContext(read(path.join(staticDir, 'js/i18n/i18n.js')), i18nBox);
for (const f of walk(path.join(staticDir, 'js/i18n/messages'), '.js', [])) vm.runInContext(read(f), i18nBox);
const messages = i18nBox.OpsFlowI18n.messages;
for (const r of routes) {
    for (const locale of ['zh', 'en']) {
        if (!(messages[locale] || {})[r.titleKey]) errors.push(`${r.section}: ${locale} 缺少标题文案 ${r.titleKey}`);
    }
}

// ---- 4. loader 是全局函数
// 第三方库（js/vendor/）不参与
const vendorDir = path.join(staticDir, 'js', 'vendor') + path.sep;
const scripts = walk(path.join(staticDir, 'js'), '.js', []).filter(f => !f.startsWith(vendorDir)).map(read).join('\n');
for (const r of routes.filter(r => r.loader)) {
    const declared = new RegExp(`^(?:async\\s+)?function\\s+${r.loader}\\s*\\(`, 'm').test(scripts)
        || scripts.includes(`window.${r.loader} =`);
    if (!declared) errors.push(`${r.section}: 找不到全局函数 ${r.loader}`);
}

// ---- 5. DashboardController
const controllerFile = walk(javaRoot, 'DashboardController.java', [])[0];
if (!controllerFile) {
    errors.push('找不到 DashboardController.java');
} else {
    const src = read(controllerFile);
    const mapped = new Set();
    for (const m of src.matchAll(/@GetMapping\(\s*(\{[^}]*\}|"[^"]*")\s*\)\s*public\s+String\s+\w+\(\)\s*\{\s*return\s+([^;]+);/g)) {
        const forward = /DASHBOARD|forward:\/dashboard\.html/.test(m[2]);
        for (const p of m[1].match(/"[^"]*"/g)) {
            const value = p.slice(1, -1);
            if (forward) mapped.add(value);
            else if (!routes.some(r => (r.aliases || []).includes(value))) errors.push(`DashboardController: ${value} 不是转发，也不是路由表里的别名`);
        }
    }
    for (const r of routes) {
        if (!mapped.has(r.path)) errors.push(`DashboardController: 缺少 ${r.path}`);
        for (const a of r.aliases || []) {
            if (!new RegExp(`"${a}"`).test(src)) errors.push(`DashboardController: 缺少别名 ${a} 的映射`);
        }
    }
    for (const p of mapped) {
        if (!routes.some(r => r.path === p)) errors.push(`DashboardController: ${p} 不在 routes.js 里`);
    }
}

// ---- 6. 与扩展模块的菜单不冲突
const extJava = [].concat(...extModules.map(m => walk(m.javaDir, '.java', [])));
let extCount = 0;
for (const f of extJava) {
    for (const m of read(f).matchAll(/new\s+(?:UiExtension\.)?MenuItem\(\s*"([^"]+)"\s*,\s*"([^"]+)"/g)) {
        extCount++;
        if (sections.has(m[1])) errors.push(`${path.relative(repoRoot, f)}: 扩展 section ${m[1]} 与核心页面重复`);
        if (paths.has(m[2])) errors.push(`${path.relative(repoRoot, f)}: 扩展路径 ${m[2]} 与核心页面重复`);
    }
}

console.log(`核心页面 ${routes.length} 个，菜单项 ${menuItems.length} 个，区块 ${sectionDivs.size} 个` +
    (extModules.length ? `；扩展菜单 ${extCount} 个（${extModules.map(m => moduleLabel(repoRoot, m)).join('、')}）` : '；没有扩展模块'));
if (errors.length) {
    console.log(`\n错误 ${errors.length} 个:`);
    errors.forEach(e => console.log('  ' + e));
    process.exit(1);
}
console.log('检查通过');
