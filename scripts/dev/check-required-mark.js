#!/usr/bin/env node
/**
 * 必填星号一致性检查（只读，不改任何文件）。
 *
 *   node scripts/dev/check-required-mark.js            有问题时退出码 1
 *   node scripts/dev/check-required-mark.js -v         同时打印每个文件找到多少个星号
 *   node scripts/dev/check-required-mark.js --ext-module <模块目录>   指定带扩展页面的模块（可写多次；
 *                                                      不指定时自动发现，见 ext-modules.js）
 *
 * 约定：必填项的标签写成 <label>文字${REQUIRED_MARK}</label>（REQUIRED_MARK 在 js/utils.js），
 * 输入框带 required（或 aria-required="true"），星号、required 和提交时的 JS 校验三者一致。
 *
 * 扫描范围：核心 static 目录和扩展模块 static/ext 下的 .html / .js，不含 js/vendor 和 *.min.js；
 * 文案：核心 js/i18n/ 下的脚本、扩展的 messages.json。
 *
 * 报错（退出码 1）：
 *   1. 标签末尾手写的 *（应改用 REQUIRED_MARK）；JS 里给标签文字拼 ' *'
 *   2. 文案值以 " *" 结尾（星号不进文案）
 *   3. class 含 required-mark 的元素没有 aria-hidden="true"
 *   4. 标了星号的 .form-item 里，第一个输入控件（不算 checkbox / radio / hidden）既没有 required
 *      也没有 aria-required；控件找不到时也报
 *      必填由脚本按条件切换、或控件是脚本动态生成的，在这一项里（星号到下一个 form-item 之间）写一段
 *      注释说明原因，内容以 required-mark-dynamic 开头：模板里用 HTML 注释，模板外用 JS 注释
 *
 * 只提示（不影响退出码）：
 *   5. 带 required 的输入控件所在的 .form-item 没有星号（登录页、两步验证这类刻意不标星号的也会列出来）
 */
'use strict';

const fs = require('fs');
const path = require('path');

const repoRoot = path.resolve(__dirname, '..', '..');
const staticDir = [
    'opsflow-core/src/main/resources/static',
    'src/main/resources/static'
].map(p => path.join(repoRoot, p)).find(p => fs.existsSync(path.join(p, 'js/utils.js')));
if (!staticDir) {
    console.error('找不到 static 目录（js/utils.js）');
    process.exit(2);
}
const { findExtModules, moduleLabel } = require('./ext-modules');
let extModules;
let cliArgs;
try {
    ({ modules: extModules, args: cliArgs } = findExtModules(repoRoot, process.argv.slice(2)));
} catch (e) {
    console.error(e.message);
    process.exit(2);
}
const verbose = cliArgs.includes('-v');

const read = f => fs.readFileSync(f, 'utf8');
// 仓库外的模块（--ext-module 指到别处）显示绝对路径
const rel = f => {
    const r = path.relative(repoRoot, f);
    return (r.startsWith('..') ? f : r).split(path.sep).join('/');
};

function walk(dir, out, skip) {
    if (!fs.existsSync(dir)) return out;
    for (const name of fs.readdirSync(dir).sort()) {
        const full = path.join(dir, name);
        if (fs.statSync(full).isDirectory()) {
            if (!skip || !skip(full)) walk(full, out, skip);
        } else if ((name.endsWith('.js') || name.endsWith('.html')) && !name.endsWith('.min.js')) {
            out.push(full);
        }
    }
    return out;
}

const vendorDir = path.join(staticDir, 'js', 'vendor');
const coreI18nDir = path.join(staticDir, 'js', 'i18n');
// 核心 static 下的 ext 目录（如果有）由扩展模块负责，这里不重复扫
const pageFiles = walk(staticDir, [], full => full === vendorDir || full === path.join(staticDir, 'ext'))
    .filter(f => !f.startsWith(coreI18nDir + path.sep));
for (const m of extModules) walk(m.extDir, pageFiles, full => path.basename(full) === 'vendor');

const errors = [];
const hints = [];
const lineOf = (text, idx) => text.slice(0, idx).split('\n').length;
const report = (list, file, text, idx, msg) => list.push(`${rel(file)}:${lineOf(text, idx)}  ${msg}`);

const DYNAMIC = 'required-mark-dynamic';
const CONTROL = /<(input|select|textarea)\b/gi;
const FORM_ITEM = /class\s*=\s*["'][^"']*\bform-item\b/g;

// 从 < 开始读到标签结束的 >，跳过 ${...} 里的 >
function tagEnd(text, start) {
    let depth = 0;
    for (let i = start; i < text.length; i++) {
        if (text[i] === '$' && text[i + 1] === '{') { depth++; i++; continue; }
        if (depth > 0) {
            if (text[i] === '{') depth++;
            else if (text[i] === '}') depth--;
            continue;
        }
        if (text[i] === '>') return i + 1;
    }
    return text.length;
}

function nextIndex(re, text, from) {
    re.lastIndex = from;
    const m = re.exec(text);
    return m ? m.index : -1;
}

function prevIndex(re, text, before) {
    re.lastIndex = 0;
    let last = -1;
    let m;
    while ((m = re.exec(text)) && m.index < before) last = m.index;
    return last;
}

const isSkippedControl = tag => /^<input\b/i.test(tag) && /\btype\s*=\s*["']?(checkbox|radio|hidden)\b/i.test(tag);
const hasRequired = tag => /\brequired\b/i.test(tag.replace(/^<\w+/, ''));

let totalMarks = 0;
for (const file of pageFiles) {
    const text = read(file);

    // 1. 手写的星号
    for (const m of text.matchAll(/\*\s*<\/label>/g)) {
        report(errors, file, text, m.index, '标签末尾手写了 *，改用 ${REQUIRED_MARK}');
    }
    for (const m of text.matchAll(/\+\s*(['"`])\s*\*\s*\1/g)) {
        report(errors, file, text, m.index, "给文字拼了 ' *'，改用 REQUIRED_MARK");
    }

    // 3. 星号元素要对读屏隐藏
    for (const m of text.matchAll(/<\w+\b[^>]*\bclass\s*=\s*["'][^"']*\brequired-mark\b(?!-)[^"']*["'][^>]*>/g)) {
        if (!/\baria-hidden\s*=\s*["']true["']/.test(m[0])) {
            report(errors, file, text, m.index, 'required-mark 元素缺少 aria-hidden="true"');
        }
    }

    // 4. 星号对应的输入控件要有 required
    // 星号写法：${REQUIRED_MARK}，或由 REQUIRED_MARK 赋值的变量 ${xxx}，或直接写的 required-mark 元素
    const aliases = ['REQUIRED_MARK'];
    for (const m of text.matchAll(/\b(?:const|let|var)\s+(\w+)\s*=[^;\n]*\bREQUIRED_MARK\b/g)) aliases.push(m[1]);
    const markRe = new RegExp('\\$\\{\\s*(?:' + aliases.join('|') + ')\\s*\\}'
        + '|<span\\b[^>]*\\bclass\\s*=\\s*["\'][^"\']*\\brequired-mark\\b(?!-)', 'g');
    // 块注释里的示例写法和 REQUIRED_MARK 自身的定义不算星号
    const code = text.replace(/\/\*[\s\S]*?\*\//g, s => s.replace(/[^\n]/g, ' '));
    const marks = [...code.matchAll(markRe)].map(m => m.index)
        .filter(i => !/\bconst\s+REQUIRED_MARK\b/.test(text.slice(text.lastIndexOf('\n', i) + 1, i)));
    totalMarks += marks.length;
    if (verbose && marks.length) console.log(`  ${rel(file)}: ${marks.length}`);

    for (const at of marks) {
        const next = nextIndex(FORM_ITEM, text, at);
        const end = next === -1 ? Math.min(text.length, at + 4000) : next;
        const region = text.slice(at, end);
        if (region.includes(DYNAMIC)) continue;
        let found = null;
        CONTROL.lastIndex = 0;
        let c;
        while ((c = CONTROL.exec(region))) {
            const tag = region.slice(c.index, tagEnd(region, c.index));
            if (isSkippedControl(tag)) continue;
            found = tag;
            break;
        }
        if (!found) {
            report(errors, file, text, at, '标了星号，但这一项里找不到输入控件（动态生成的请写 required-mark-dynamic 注释）');
        } else if (!hasRequired(found)) {
            const name = (found.match(/\b(?:id|name)\s*=\s*["']([^"']+)/) || [])[1] || found.slice(0, 40);
            report(errors, file, text, at, `标了星号，但输入控件 ${name} 没有 required / aria-required`);
        }
    }

    // 5. 有 required 却没星号（只提示）
    CONTROL.lastIndex = 0;
    let c;
    while ((c = CONTROL.exec(text))) {
        const tag = text.slice(c.index, tagEnd(text, c.index));
        if (isSkippedControl(tag) || !hasRequired(tag)) continue;
        const start = prevIndex(FORM_ITEM, text, c.index);
        const before = text.slice(start === -1 ? Math.max(0, c.index - 1500) : start, c.index);
        markRe.lastIndex = 0;
        if (!markRe.test(before)) {
            const name = (tag.match(/\b(?:id|name)\s*=\s*["']([^"']+)/) || [])[1] || tag.slice(0, 40);
            report(hints, file, text, c.index, `输入控件 ${name} 带 required，但标签没有星号`);
        }
    }
}

// 2. 文案里的星号
function checkMessageValue(file, key, value) {
    if (typeof value === 'string') {
        if (/\s\*\s*$/.test(value)) errors.push(`${rel(file)}  文案 ${key} 以 " *" 结尾，星号改由 REQUIRED_MARK 加`);
    } else if (value && typeof value === 'object') {
        for (const [k, v] of Object.entries(value)) checkMessageValue(file, key ? key + '.' + k : k, v);
    }
}
for (const file of walk(coreI18nDir, [])) {
    const text = read(file);
    for (const m of text.matchAll(/(['"])((?:\\.|(?!\1)[^\\\n])*?\s\*\s*)\1/g)) {
        report(errors, file, text, m.index, `文案 ${m[0]} 以 " *" 结尾，星号改由 REQUIRED_MARK 加`);
    }
}
for (const m of extModules) {
    for (const file of fs.existsSync(m.extDir) ? fs.readdirSync(m.extDir).sort() : []) {
        const json = path.join(m.extDir, file, 'messages.json');
        if (!fs.existsSync(json)) continue;
        try {
            checkMessageValue(json, '', JSON.parse(read(json)));
        } catch (e) {
            errors.push(`${rel(json)}  解析失败：${e.message}`);
        }
    }
}

const scope = extModules.length ? `核心 + ${extModules.map(m => moduleLabel(repoRoot, m)).join('、')}` : '核心';
console.log(`扫描 ${pageFiles.length} 个文件（${scope}），星号 ${totalMarks} 处`);
if (hints.length) {
    console.log(`\n提示：带 required 但没有星号 ${hints.length} 处（不影响结果）`);
    for (const h of hints) console.log('  ' + h);
}
if (errors.length) {
    console.log(`\n问题 ${errors.length} 处：`);
    for (const e of errors) console.log('  ' + e);
    process.exit(1);
}
console.log('\n必填星号检查通过');
