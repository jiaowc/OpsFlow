#!/usr/bin/env node
/**
 * 前端 i18n 检查（只读，不改任何文件）。
 *
 *   node scripts/dev/check-i18n.js                 检查 key，并列出各文件剩余的硬编码中文行数
 *   node scripts/dev/check-i18n.js -v              同时打印剩余中文所在的行
 *   node scripts/dev/check-i18n.js --strict a b    额外要求这些文件不能再有硬编码中文
 *                                                  （核心文件相对 static 目录，如 dashboard.html；扩展写 ext/<id>/<文件>）
 *   node scripts/dev/check-i18n.js --ext-module <模块目录>   指定带扩展页面的模块（可写多次）
 *
 * 扫描范围：核心 static 目录；另外扫带扩展页面的模块的 static/ext（扩展页面和各自的 messages.json），
 * 这类模块怎么找见 ext-modules.js（没指定时自动发现仓库根目录下一级含 static/ext 的模块）。
 *
 * 检查项（前三项不通过时退出码为 1）：
 *   1. 中英文 key 一一对应（核心和每个扩展的 messages.json 各自核对）；
 *      扩展的 key 不能和核心或别的扩展重名（前端合并时会静默保留先到的值）
 *   2. 页面和脚本里用到的 key 都有定义（data-i18n* 属性、t('key')）
 *   3. --strict 指定的文件里没有硬编码中文
 *   4. 列出没有被引用的 key（仅提示）
 *
 * 不算硬编码中文的情况：注释；HTML 里由 data-i18n*、data-i18n-skip 接管的文本和属性（同一行的其他中文照样算）、<title>；
 * JS 里行尾带 // i18n-ignore 的行；文案文件本身（js/i18n/）。
 */
'use strict';

const fs = require('fs');
const path = require('path');
const vm = require('vm');

const repoRoot = path.resolve(__dirname, '..', '..');
const staticDir = [
    'opsflow-core/src/main/resources/static',
    'src/main/resources/static'
].map(p => path.join(repoRoot, p)).find(p => fs.existsSync(path.join(p, 'js/i18n/i18n.js')));

if (!staticDir) {
    console.error('找不到 static 目录（js/i18n/i18n.js）');
    process.exit(2);
}

// 带扩展页面的模块（没有时只检查核心）
const { findExtModules, moduleLabel } = require('./ext-modules');
let extModules;
let args;
try {
    ({ modules: extModules, args } = findExtModules(repoRoot, process.argv.slice(2)));
} catch (e) {
    console.error(e.message);
    process.exit(2);
}

const verbose = args.includes('-v');
const strictIdx = args.indexOf('--strict');
const strictFiles = strictIdx >= 0 ? args.slice(strictIdx + 1).filter(a => !a.startsWith('-')) : [];

// 第三方库（js/vendor/）原样放入，不检查
const isVendorDir = full => path.relative(staticDir, full).split(path.sep).join('/') === 'js/vendor';

function walk(dir, out) {
    for (const name of fs.readdirSync(dir).sort()) {
        const full = path.join(dir, name);
        if (fs.statSync(full).isDirectory()) {
            if (isVendorDir(full)) continue;
            walk(full, out);
        } else if (/\.(js|html)$/.test(name)) {
            out.push(full);
        }
    }
    return out;
}

const extModuleOf = f => extModules.find(m => f.startsWith(m.extDir + path.sep));
const rel = f => {
    const m = extModuleOf(f);
    return path.relative(m ? m.staticDir : staticDir, f).split(path.sep).join('/');
};
const files = walk(staticDir, []).concat(...extModules.map(m => walk(m.extDir, [])));
const isMessageFile = f => rel(f).startsWith('js/i18n/');

// ---- 1. 加载文案：在沙箱里执行 i18n.js 和 js/i18n/messages/*.js，取最终的 messages
function loadMessages() {
    const warnings = [];
    const sandbox = {
        console: { warn: m => warnings.push(String(m)), log() {}, error() {} },
        localStorage: { getItem: () => null, setItem() {} },
        navigator: { language: 'zh-CN' },
        document: { documentElement: {}, getElementById: () => null, querySelectorAll: () => [] }
    };
    sandbox.window = sandbox;
    vm.createContext(sandbox);
    const ordered = [path.join(staticDir, 'js/i18n/i18n.js')]
        .concat(files.filter(f => rel(f).startsWith('js/i18n/messages/')));
    for (const f of ordered) {
        vm.runInContext(fs.readFileSync(f, 'utf8'), sandbox, { filename: f });
    }
    return { messages: sandbox.OpsFlowI18n.messages, warnings };
}

const { messages, warnings } = loadMessages();
const zh = messages.zh || {};
const en = messages.en || {};
const errors = [];

warnings.forEach(w => errors.push('重复定义: ' + w));
Object.keys(zh).filter(k => !(k in en)).forEach(k => errors.push('en 缺少 key: ' + k));
Object.keys(en).filter(k => !(k in zh)).forEach(k => errors.push('zh 缺少 key: ' + k));
const coreKeyCount = Object.keys(zh).length;

// 扩展的 messages.json：各自核对中英文，再并入总表（不能和已有的 key 重名）
const extKeyOwner = {};
const extBundles = [];
for (const m of extModules) {
    for (const n of fs.readdirSync(m.extDir).sort()) {
        if (fs.existsSync(path.join(m.extDir, n, 'messages.json'))) extBundles.push({ name: n, extDir: m.extDir });
    }
}
for (const { name, extDir } of extBundles) {
    const file = 'ext/' + name + '/messages.json';
    let bundle;
    try {
        bundle = JSON.parse(fs.readFileSync(path.join(extDir, name, 'messages.json'), 'utf8'));
    } catch (e) {
        errors.push(`${file}: 不是合法的 JSON: ${e.message}`);
        continue;
    }
    const bz = bundle.zh || {};
    const be = bundle.en || {};
    Object.keys(bz).filter(k => !(k in be)).forEach(k => errors.push(`${file}: en 缺少 key: ${k}`));
    Object.keys(be).filter(k => !(k in bz)).forEach(k => errors.push(`${file}: zh 缺少 key: ${k}`));
    for (const k of Object.keys(bz)) {
        if (k in zh) {
            errors.push(`${file}: key ${k} 与${extKeyOwner[k] ? ' ext/' + extKeyOwner[k] : '核心'}重名`);
            continue;
        }
        zh[k] = bz[k];
        if (k in be) en[k] = be[k];
        extKeyOwner[k] = name;
    }
}

// ---- 2. 用到的 key 是否都有定义
const HAN = /[一-鿿]/;
const used = new Set();
const usedPrefixes = new Set();
const sources = {};
for (const f of files) {
    const text = fs.readFileSync(f, 'utf8');
    sources[f] = text;
    if (isMessageFile(f)) continue;
    const refs = [];
    if (f.endsWith('.html')) {
        for (const m of text.matchAll(/data-i18n(?:-placeholder|-title)?="([^"]+)"/g)) refs.push(m[1]);
    }
    // t('key') / t("key")；模板字符串内联脚本里的 data-i18n 也一并识别
    for (const m of text.matchAll(/(?<![\w.$])t\(\s*(['"])([\w.-]+)\1/g)) refs.push(m[2]);
    if (f.endsWith('.js')) {
        for (const m of text.matchAll(/data-i18n(?:-placeholder|-title)?="([\w.-]+)"/g)) refs.push(m[1]);
    }
    for (const key of refs) {
        // t('prefix.' + code) 这类拼接：只记前缀，不按完整 key 检查
        if (key.endsWith('.')) {
            usedPrefixes.add(key);
            continue;
        }
        used.add(key);
        if (!(key in zh)) errors.push(`${rel(f)}: 未定义的 key ${key}`);
    }
}

// ---- 3. 剩余硬编码中文
/**
 * 去掉一行 HTML 里已由 i18n 接管的部分，剩下的中文才算硬编码：
 *   - 带 data-i18n="k" 或 data-i18n-skip 的元素，其开始标签之后、下一个标签之前的文本
 *   - 带 data-i18n-placeholder / data-i18n-title 的标签里对应的 placeholder / title 属性
 * 同一行里其他元素的文本、onclick 等属性里的中文仍会被报出来。
 */
function stripTranslatedHtml(code) {
    // 跨行的标签：行首的属性续行、行尾没闭合的开始标签也按标签处理
    return code.replace(/(?:<[a-zA-Z]|^)[^<>]*(?:>|$)/g, tag => {
        let out = tag;
        if (/\sdata-i18n-placeholder="/.test(out)) out = out.replace(/\splaceholder="[^"]*"/, '');
        if (/\sdata-i18n-title="/.test(out)) out = out.replace(/\stitle="[^"]*"/, '');
        if (/\sdata-i18n(?:="[^"]*"|-skip\b)/.test(out)) out += '\u0000';
        return out;
    }).replace(/\u0000[^<]*/g, '');
}

function hardcodedLines(f) {
    const lines = sources[f].split('\n');
    const out = [];
    let inBlockComment = false;
    const html = f.endsWith('.html');
    lines.forEach((line, i) => {
        let code = line;
        if (html) {
            if (inBlockComment) {
                if (!code.includes('-->')) return;
                code = code.slice(code.indexOf('-->') + 3);
                inBlockComment = false;
            }
            code = code.replace(/<!--.*?-->/g, '');
            if (code.includes('<!--')) {
                inBlockComment = true;
                code = code.slice(0, code.indexOf('<!--'));
            }
            if (/<title>/.test(code)) return;
            code = stripTranslatedHtml(code);
            // HTML 内联脚本里的注释
            code = code.replace(/\/\/.*$/, '');
        } else {
            if (inBlockComment) {
                if (!code.includes('*/')) return;
                code = code.slice(code.indexOf('*/') + 2);
                inBlockComment = false;
            }
            if (/\/\/\s*i18n-ignore\s*$/.test(code)) return;
            code = code.replace(/\/\*.*?\*\//g, '');
            if (code.includes('/*')) {
                inBlockComment = true;
                code = code.slice(0, code.indexOf('/*'));
            }
            // 行注释：// 前面不是 : （避免误伤 URL）
            code = code.replace(/(^|[^:'"`])\/\/.*$/, '$1');
        }
        if (HAN.test(code)) out.push({ line: i + 1, text: line.trim() });
    });
    return out;
}

const remaining = [];
let remainingTotal = 0;
for (const f of files) {
    if (isMessageFile(f)) continue;
    const lines = hardcodedLines(f);
    if (lines.length) {
        remaining.push({ file: rel(f), lines });
        remainingTotal += lines.length;
    }
}
for (const sf of strictFiles) {
    const hit = remaining.find(r => r.file === sf);
    if (hit) {
        hit.lines.forEach(l => errors.push(`${sf}:${l.line} 仍有硬编码中文: ${l.text.slice(0, 80)}`));
    } else if (!files.some(f => rel(f) === sf)) {
        errors.push('--strict 指定的文件不存在: ' + sf);
    }
}

// ---- 4. 未被引用的 key（动态拼接的 key 识别不了，所以只按字符串字面量出现与否、t('前缀.' + x) 的前缀判断；
//         扩展的菜单名、锁定提示由后端 UiExtension 声明，所以也看扩展模块的 Java 源码）
function walkExt(dir, re, out) {
    if (!fs.existsSync(dir)) return out;
    for (const name of fs.readdirSync(dir).sort()) {
        const full = path.join(dir, name);
        if (fs.statSync(full).isDirectory()) walkExt(full, re, out);
        else if (re.test(name)) out.push(full);
    }
    return out;
}
const extJava = [].concat(...extModules.map(m => walkExt(m.javaDir, /\.java$/, []))).map(f => fs.readFileSync(f, 'utf8'));
const allCode = files.filter(f => !isMessageFile(f)).map(f => sources[f]).concat(extJava).join('\n');
const unused = Object.keys(zh).filter(k => !used.has(k) && !allCode.includes(`'${k}'`) && !allCode.includes(`"${k}"`)
    && ![...usedPrefixes].some(p => k.startsWith(p)));

// ---- 输出
console.log(`static 目录: ${path.relative(repoRoot, staticDir)}` +
    (extModules.length ? `；扩展 ${extBundles.length} 个（${extModules.map(m => moduleLabel(repoRoot, m)).join('、')}）` : ''));
console.log(`文案 key: zh ${Object.keys(zh).length} / en ${Object.keys(en).length}（核心 ${coreKeyCount}，扩展 ${Object.keys(extKeyOwner).length}），页面和脚本引用 ${used.size} 个`);
console.log(`\n剩余硬编码中文: ${remainingTotal} 行，${remaining.length} 个文件`);
remaining.sort((a, b) => b.lines.length - a.lines.length).forEach(r => {
    console.log(`  ${String(r.lines.length).padStart(5)}  ${r.file}`);
    if (verbose) r.lines.forEach(l => console.log(`           ${l.line}: ${l.text.slice(0, 100)}`));
});
if (unused.length) {
    console.log(`\n未被引用的 key（${unused.length} 个，可能是动态拼接，仅提示）:`);
    unused.forEach(k => console.log('  ' + k));
}
if (errors.length) {
    console.log(`\n错误 ${errors.length} 个:`);
    errors.forEach(e => console.log('  ' + e));
    process.exit(1);
}
console.log('\n检查通过');
