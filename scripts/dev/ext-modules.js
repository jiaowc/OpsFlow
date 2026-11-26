'use strict';
/**
 * 前端检查脚本共用：找出带扩展页面的模块（模块的 src/main/resources/static/ext 下按扩展 id 分目录，
 * 页面由后端 UiExtension 声明）。check-i18n / check-html-escape / check-routes 都用它，额外检查这些模块。
 *
 * 查找顺序（前面的有就不看后面的）：
 *   1. 命令行 --ext-module <模块目录>，可写多次
 *   2. 环境变量 OPSFLOW_EXT_MODULES，多个目录用系统路径分隔符（: 或 ;）隔开
 *   3. 自动发现：仓库根目录下一级、含 src/main/resources/static/ext 的模块
 * 相对路径按当前目录解析。一个也没有时返回空数组，各脚本只检查核心。
 *
 * 返回 { modules, args }：modules 每项 { dir, staticDir, extDir, javaDir }；args 是去掉 --ext-module 参数后的命令行。
 */
const fs = require('fs');
const path = require('path');

const STATIC = path.join('src', 'main', 'resources', 'static');

function describe(dir) {
    const staticDir = path.join(dir, STATIC);
    return {
        dir,
        staticDir,
        extDir: path.join(staticDir, 'ext'),
        javaDir: path.join(dir, 'src', 'main', 'java')
    };
}

function findExtModules(repoRoot, argv) {
    const args = [];
    const explicit = [];
    for (let i = 0; i < argv.length; i++) {
        if (argv[i] === '--ext-module') {
            if (i + 1 >= argv.length) throw new Error('--ext-module 后面要跟模块目录');
            explicit.push(argv[++i]);
        } else if (argv[i].startsWith('--ext-module=')) {
            explicit.push(argv[i].slice('--ext-module='.length));
        } else {
            args.push(argv[i]);
        }
    }
    let dirs = explicit;
    if (!dirs.length && process.env.OPSFLOW_EXT_MODULES) {
        dirs = process.env.OPSFLOW_EXT_MODULES.split(path.delimiter).filter(Boolean);
    }
    let modules;
    if (dirs.length) {
        modules = dirs.map(d => describe(path.resolve(d)));
        for (const m of modules) {
            if (!fs.existsSync(m.extDir)) throw new Error('指定的扩展模块里没有 static/ext 目录: ' + m.dir);
        }
    } else {
        modules = fs.readdirSync(repoRoot).sort()
            .filter(name => !name.startsWith('.') && name !== 'node_modules')
            .map(name => describe(path.join(repoRoot, name)))
            .filter(m => fs.existsSync(m.extDir) && fs.statSync(m.extDir).isDirectory());
    }
    return { modules, args };
}

/** 给输出用的模块名：在仓库里时写相对路径，否则写绝对路径 */
function moduleLabel(repoRoot, m) {
    const rel = path.relative(repoRoot, m.dir);
    return rel && !rel.startsWith('..') ? rel : m.dir;
}

module.exports = { findExtModules, moduleLabel };
