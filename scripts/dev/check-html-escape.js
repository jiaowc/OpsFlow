#!/usr/bin/env node
/**
 * 前端 HTML 转义检查（只读，不改任何文件）。
 *
 *   node scripts/dev/check-html-escape.js          列出拼进 HTML 却看不出已转义的表达式，有则退出码 1
 *   node scripts/dev/check-html-escape.js -v       同时打印每个文件检查了多少处
 *   node scripts/dev/check-html-escape.js --ext-module <模块目录>   指定带扩展页面的模块（可写多次；
 *                                                  不指定时自动发现，见 ext-modules.js）
 *
 * 检查对象：
 *   1. 含 HTML 标签的模板字面量里的 ${表达式}
 *   2. 与含 HTML 标签的字符串用 + 拼接的表达式
 *
 * 以下情况视为安全，其余都会报出来：
 *   - escapeHtml(...)、encodeURIComponent(...)、数值运算、比较、字符串字面量、嵌套的模板字面量
 *   - t('key')；带变量时 t('key', { a: x }) 的每个变量值也要满足本规则
 *   - 按命名约定返回 HTML 的函数：render* / build*，或以 Html / Options / Cell / Badge / Icon / Class / Attrs / Style 结尾
 *   - xxx.map(...).join(...)，且 map 的回调里是模板字面量或上述函数（回调里的模板会被单独检查）
 *   - 数字类属性：id、xxxId（见 NUMERIC_PROPS）、length、count 等
 *   - 局部变量：向上找到它的 const / let 声明，初始值满足本规则
 *
 * 确认安全但工具判断不了的，在表达式里加一段块注释说明原因，内容以 html-safe: 开头，
 * 例如 ${ [块注释 html-safe: 上面已转义] value }；字符串拼接的写在行尾：// html-safe: 原因
 *
 * 这是启发式检查：通过不等于绝对安全（比如把数据拼进内联事件的 JS 字符串里就查不出来），
 * 但能挡住最常见的「忘了 escapeHtml」。
 */
'use strict';

const fs = require('fs');
const path = require('path');

const repoRoot = path.resolve(__dirname, '..', '..');
const staticDir = [
    'opsflow-core/src/main/resources/static',
    'src/main/resources/static'
].map(p => path.join(repoRoot, p)).find(p => fs.existsSync(path.join(p, 'js/core.js')));

if (!staticDir) {
    console.error('找不到 static 目录（js/core.js）');
    process.exit(2);
}
const { findExtModules } = require('./ext-modules');
let extModules;
let cliArgs;
try {
    ({ modules: extModules, args: cliArgs } = findExtModules(repoRoot, process.argv.slice(2)));
} catch (e) {
    console.error(e.message);
    process.exit(2);
}
const verbose = cliArgs.includes('-v');

const SAFE_FUNCS = new Set([
    'escapeHtml', 'safeText', 'encodeURIComponent', 'Number', 'parseInt', 'parseFloat', 'Boolean',
    'formatStatus', 'formatDate', 'formatEnvType', 'templateVarCode', 'pipelineTypeLabel'
]);
const HTML_FUNC = /^(render|build)[A-Z]\w*$|(Html|Options|Cell|Badge|Icon|Class|Attrs?|Style)$/;
// REQUIRED_MARK：utils.js 里写死的必填星号 HTML
const SAFE_VAR = /(Html|Class|Attrs?|Style)$|^(i|j|n|idx|index|colspan|REQUIRED_MARK)$/;
const NUMERIC_PROPS = new Set([
    'id', 'envId', 'clusterId', 'projectId', 'serviceId', 'nodeId', 'jobId', 'taskId', 'pipelineId',
    'credentialId', 'templateId', 'parentId', 'roleId', 'userId', 'buildId', 'latestBuildId', 'stageId',
    'viewId', 'approvalId', 'componentId', 'length', 'size', 'count', 'total', 'port', 'order', 'step',
    'memberCount', 'serviceCount', 'envCount', 'replicas', 'readyReplicas', 'restarts', 'durationMs',
    'timeoutSeconds', 'servicePort', 'currentStep', 'deployParallelism'
]);

// 第三方库（js/vendor/）原样放入，不检查
const isVendorDir = full => path.relative(staticDir, full).split(path.sep).join('/') === 'js/vendor';

function walk(dir, out) {
    for (const name of fs.readdirSync(dir).sort()) {
        const full = path.join(dir, name);
        if (fs.statSync(full).isDirectory()) {
            if (!isVendorDir(full)) walk(full, out);
        }
        else if (name.endsWith('.js')) out.push(full);
    }
    return out;
}

// ---- 词法扫描：取出模板字面量（含各 ${} 表达式）和普通字符串
function scan(src) {
    const templates = [];
    const strings = [];
    const stack = [{ kind: 'code', depth: 0 }];
    let i = 0;
    let lastSig = '';
    let lastWord = '';
    const n = src.length;
    while (i < n) {
        const top = stack[stack.length - 1];
        const c = src[i];
        if (top.kind === 'tpl') {
            const start = i;
            while (i < n) {
                if (src[i] === '\\') { i += 2; continue; }
                if (src[i] === '`' || (src[i] === '$' && src[i + 1] === '{')) break;
                i++;
            }
            top.tpl.text += src.slice(start, i);
            if (i >= n) break;
            if (src[i] === '`') {
                top.tpl.end = i + 1;
                stack.pop();
                i++;
                lastSig = '`';
            } else {
                stack.push({ kind: 'code', depth: 0, exprStart: i + 2, owner: top.tpl });
                i += 2;
                lastSig = '{';
            }
            continue;
        }
        if (c === '/' && src[i + 1] === '/') {
            const j = src.indexOf('\n', i);
            i = j < 0 ? n : j;
            continue;
        }
        if (c === '/' && src[i + 1] === '*') {
            const j = src.indexOf('*/', i + 2);
            i = j < 0 ? n : j + 2;
            continue;
        }
        if (c === '\'' || c === '"') {
            let j = i + 1;
            while (j < n && src[j] !== c && src[j] !== '\n') {
                if (src[j] === '\\') j++;
                j++;
            }
            strings.push({ start: i, end: j + 1, value: src.slice(i + 1, j) });
            i = j + 1;
            lastSig = c;
            lastWord = '';
            continue;
        }
        if (c === '`') {
            const tpl = { start: i, end: n, text: '', exprs: [] };
            templates.push(tpl);
            stack.push({ kind: 'tpl', tpl });
            i++;
            continue;
        }
        if (c === '/') {
            const isRegex = '(,=:[!&|?{};\n'.includes(lastSig) || lastSig === '' || ['return', 'typeof', 'case'].includes(lastWord);
            if (isRegex) {
                let j = i + 1;
                let inClass = false;
                while (j < n && src[j] !== '\n') {
                    if (src[j] === '\\') { j += 2; continue; }
                    if (src[j] === '[') inClass = true;
                    else if (src[j] === ']') inClass = false;
                    else if (src[j] === '/' && !inClass) break;
                    j++;
                }
                i = j + 1;
                lastSig = '/';
                lastWord = '';
                continue;
            }
        }
        if (c === '{') {
            top.depth++;
        } else if (c === '}') {
            if (top.depth === 0 && stack.length > 1) {
                top.owner.exprs.push({ src: src.slice(top.exprStart, i), start: top.exprStart });
                stack.pop();
                i++;
                continue;
            }
            top.depth--;
        }
        if (!/\s/.test(c)) {
            if (/[\w$]/.test(c)) {
                lastWord = /[\w$]/.test(src[i - 1] || '') ? lastWord + c : c;
            } else {
                lastWord = '';
            }
            lastSig = c;
        } else if (c === '\n' && !/[\w$)\]'"`]/.test(lastSig)) {
            lastSig = '\n';
        }
        i++;
    }
    return { templates, strings };
}

// ---- 表达式拆分工具（只在最外层拆，括号、引号、模板内部不拆）
function splitTop(expr, seps) {
    const parts = [];
    let depth = 0;
    let quote = '';
    let start = 0;
    for (let i = 0; i < expr.length; i++) {
        const c = expr[i];
        if (quote) {
            if (c === '\\') { i++; continue; }
            if (quote === '`' && c === '$' && expr[i + 1] === '{') {
                // 跳过模板里的表达式
                let d = 1;
                i += 2;
                while (i < expr.length && d) {
                    if (expr[i] === '{') d++;
                    else if (expr[i] === '}') d--;
                    i++;
                }
                i--;
                continue;
            }
            if (c === quote) quote = '';
            continue;
        }
        if (c === '\'' || c === '"' || c === '`') { quote = c; continue; }
        if ('([{'.includes(c)) { depth++; continue; }
        if (')]}'.includes(c)) { depth--; continue; }
        if (depth !== 0) continue;
        for (const sep of seps) {
            if (expr.startsWith(sep, i)) {
                // 排除 ?. 和 ?? 被当成三元的 ?，=> 被当成比较
                if (sep === '?' && (expr[i + 1] === '.' || expr[i + 1] === '?' || expr[i - 1] === '?')) break;
                if (sep === '+' && (expr[i + 1] === '+' || expr[i - 1] === '+')) break;
                parts.push({ text: expr.slice(start, i), sep });
                start = i + sep.length;
                i += sep.length - 1;
                break;
            }
        }
    }
    parts.push({ text: expr.slice(start), sep: null });
    return parts;
}

function stripParens(expr) {
    let e = expr.trim();
    while (e.startsWith('(') && e.endsWith(')')) {
        let depth = 0;
        let wraps = true;
        for (let i = 0; i < e.length - 1; i++) {
            if (e[i] === '(') depth++;
            else if (e[i] === ')') depth--;
            if (depth === 0) { wraps = false; break; }
        }
        if (!wraps) break;
        e = e.slice(1, -1).trim();
    }
    return e;
}

/** 找局部变量最近的一次声明，返回初始值表达式 */
function findDeclaration(src, name, before) {
    const re = new RegExp('\\b(?:const|let|var)\\s+' + name.replace(/\$/g, '\\$') + '\\s*=\\s*', 'g');
    let found = -1;
    let m;
    while ((m = re.exec(src)) && m.index < before) {
        found = m.index + m[0].length;
    }
    if (found < 0 || before - found > 12000) return null;
    const rest = src.slice(found, Math.min(src.length, found + 4000));
    const stmt = splitTop(rest, [';'])[0].text;
    return stmt;
}

function isSafe(expr, ctx, depth) {
    let e = stripParens(expr.replace(/\/\*[\s\S]*?\*\//g, m => (m.includes('html-safe') ? ' __HTML_SAFE__ ' : ' ')));
    if (e.includes('__HTML_SAFE__')) return true;
    if (!e) return true;
    if (depth > 6) return false;
    if (e.startsWith('await ')) return isSafe(e.slice(6), ctx, depth + 1);

    // 三元
    const q = splitTop(e, ['?']);
    if (q.length > 1) {
        const rest = e.slice(q[0].text.length + 1);
        const branches = splitTop(rest, [':']);
        if (branches.length >= 2) {
            const a = branches[0].text;
            const b = rest.slice(a.length + 1);
            return isSafe(a, ctx, depth + 1) && isSafe(b, ctx, depth + 1);
        }
    }
    // || && ??
    const logic = splitTop(e, ['||', '&&', '??']);
    if (logic.length > 1) {
        return logic.every((part, idx) => {
            // a && b：结果要么是 a 的假值，要么是 b，只需要 b 安全
            if (part.sep === '&&') return true;
            return isSafe(part.text, ctx, depth + 1);
        });
    }
    // 比较、取反：结果是布尔
    if (/^(!|typeof\s)/.test(e) || splitTop(e, ['===', '!==', '==', '!=', '>=', '<=']).length > 1) return true;
    if (splitTop(e, [' > ', ' < ']).length > 1) return true;
    // 加法：每一项都要安全
    const plus = splitTop(e, ['+']);
    if (plus.length > 1) return plus.every(part => isSafe(part.text, ctx, depth + 1));
    // 其它算术：结果是数字
    if (splitTop(e, [' - ', ' * ', ' / ', ' % ']).length > 1) return true;

    if (/^-?\d+(\.\d+)?$/.test(e)) return true;
    if (/^'(?:[^'\\]|\\.)*'$/.test(e) || /^"(?:[^"\\]|\\.)*"$/.test(e)) return true;
    if (e.startsWith('`') && e.endsWith('`')) return true;            // 嵌套模板自己会被检查
    if (/^(true|false|null|undefined)$/.test(e)) return true;
    if (/^new Date\(/.test(e) && /\.to\w*String\(/.test(e)) return true;

    // xxx.map(...).join(...)：回调里的模板会被单独检查
    const mapJoin = e.match(/\.map\(([\s\S]*)\)\s*\.join\([\s\S]*\)$/);
    if (mapJoin) {
        const cb = mapJoin[1].trim();
        if (cb.includes('`') || SAFE_FUNCS.has(cb) || HTML_FUNC.test(cb)) return true;
        const body = cb.replace(/^[\w$]+\s*=>\s*|^\([^)]*\)\s*=>\s*/, '');
        return body !== cb && isSafe(body, ctx, depth + 1);
    }

    // 函数调用
    const call = e.match(/^((?:window\.)?[\w$.]*?)([\w$]+)\(([\s\S]*)\)$/);
    if (call) {
        const [, prefix, name, args] = call;
        const balanced = splitTop(e.slice(prefix.length + name.length), [')']).length <= 2;
        if (balanced) {
            if (name === 't' && !prefix) {
                const argParts = splitTop(args, [',']);
                if (argParts.length === 1) return true;
                const vars = argParts.slice(1).map(p => p.text).join(',').trim();
                if (!vars.startsWith('{')) return false;
                return splitTop(vars.slice(1, -1), [',']).every(prop => {
                    const kv = splitTop(prop.text, [':']);
                    if (kv.length < 2) return !prop.text.trim() || isSafe(prop.text, ctx, depth + 1);
                    return isSafe(prop.text.slice(kv[0].text.length + 1), ctx, depth + 1);
                });
            }
            if (prefix === 'Math.' || SAFE_FUNCS.has(name) || HTML_FUNC.test(name)) return true;
            if (name === 'toFixed' || name === 'toLocaleString' && /Date/.test(prefix)) return true;
        }
        return false;
    }

    // 属性访问：数字类属性
    const prop = e.match(/^[\w$]+(?:\??\.[\w$]+|\[[^\]]+\])*\??\.([\w$]+)$/);
    if (prop) return NUMERIC_PROPS.has(prop[1]);

    // 单个标识符
    if (/^[\w$]+$/.test(e)) {
        if (SAFE_VAR.test(e)) return true;
        const init = findDeclaration(ctx.src, e, ctx.pos);
        return init != null && isSafe(init, { src: ctx.src, pos: ctx.pos - 1 }, depth + 1);
    }
    return false;
}

/** 读出 + 右边的一个操作数：到最外层的 + ; , ? : 换行，或属于外层的右括号为止 */
function readOperand(src, start) {
    let depth = 0;
    let quote = '';
    let i = start;
    for (; i < src.length && i < start + 800; i++) {
        const c = src[i];
        if (quote) {
            if (c === '\\') { i++; continue; }
            if (c === quote) quote = '';
            continue;
        }
        if (c === '\'' || c === '"' || c === '`') { quote = c; continue; }
        if ('([{'.includes(c)) { depth++; continue; }
        if (')]}'.includes(c)) {
            if (depth === 0) break;
            depth--;
            continue;
        }
        if (depth === 0 && (c === ';' || c === ',' || c === '\n' || c === ':'
            || (c === '+' && src[i + 1] !== '+' && src[i - 1] !== '+')
            || (c === '?' && src[i + 1] !== '.' && src[i + 1] !== '?'))) break;
    }
    return src.slice(start, i);
}

const lineOf = (src, pos) => src.slice(0, pos).split('\n').length;
const HTML_TEXT = /<\/?[a-zA-Z]/;

const findings = [];
let checked = 0;
// 核心的 js/ 和扩展模块的 ext/（有扩展模块时）都检查
const scanRoots = [[staticDir, 'js']].concat(extModules.map(m => [m.staticDir, 'ext']));
const sourceFiles = [];
for (const [base, sub] of scanRoots) {
    for (const file of walk(path.join(base, sub), [])) {
        sourceFiles.push({ file, rel: path.relative(base, file).split(path.sep).join('/') });
    }
}
for (const { file, rel } of sourceFiles) {
    if (rel.startsWith('js/i18n/')) continue;
    const src = fs.readFileSync(file, 'utf8');
    const lines = src.split('\n');
    const { templates, strings } = scan(src);
    let fileChecked = 0;

    for (const tpl of templates) {
        if (!HTML_TEXT.test(tpl.text)) continue;
        for (const expr of tpl.exprs) {
            fileChecked++;
            if (!isSafe(expr.src, { src, pos: expr.start }, 0)) {
                findings.push({ file: rel, line: lineOf(src, expr.start), expr: expr.src.trim().replace(/\s+/g, ' ') });
            }
        }
    }
    // 字符串拼接：'<td>' + x + '</td>'
    for (const str of strings) {
        if (!HTML_TEXT.test(str.value)) continue;
        const line = lineOf(src, str.start);
        if (/\/\/\s*html-safe/.test(lines[line - 1])) continue;
        let pos = str.end;
        for (;;) {
            const rest = src.slice(pos);
            const m = rest.match(/^\s*\)*\s*\+\s*/);
            if (!m) break;
            const operandStart = pos + m[0].length;
            const operand = readOperand(src, operandStart);
            if (!operand.trim()) break;
            fileChecked++;
            const cleaned = operand;
            if (!isSafe(cleaned, { src, pos: operandStart }, 0)) {
                findings.push({ file: rel, line: lineOf(src, operandStart), expr: cleaned.trim().replace(/\s+/g, ' ') });
            }
            pos = operandStart + operand.length;
        }
    }
    checked += fileChecked;
    if (verbose) console.log(`${String(fileChecked).padStart(5)}  ${rel}`);
}

// 同一行同一表达式只报一次
const seen = new Set();
const unique = findings.filter(f => {
    const key = `${f.file}:${f.line}:${f.expr}`;
    if (seen.has(key)) return false;
    seen.add(key);
    return true;
});

console.log(`static 目录: ${scanRoots.map(r => path.relative(repoRoot, path.join(r[0], r[1]))).join('、')}，共检查 ${checked} 处拼进 HTML 的表达式`);
if (unique.length) {
    console.log(`\n看不出已转义的有 ${unique.length} 处:`);
    let current = '';
    for (const f of unique) {
        if (f.file !== current) {
            current = f.file;
            console.log('  ' + current);
        }
        console.log(`    ${String(f.line).padStart(5)}: ${f.expr.slice(0, 110)}`);
    }
    process.exit(1);
}
console.log('检查通过');
