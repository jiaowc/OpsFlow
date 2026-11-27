// 社区版源码入口（AGPLv3 第 13 条：通过网络使用的用户要能拿到对应源码）。
// 只有 /api/system/version 明确返回 edition=community 时才显示；企业版、取不到版本信息时都不显示。
(function (global) {
    const REPO_URL = 'https://github.com/jiaowc/OpsFlow';
    const RELEASE_VERSION = /^\d+\.\d+\.\d+$/;

    /** 正式版本号指向对应 tag，其他情况（快照版、取不到）退回仓库首页 */
    function sourceUrl(version) {
        return typeof version === 'string' && RELEASE_VERSION.test(version)
            ? REPO_URL + '/tree/v' + version
            : REPO_URL;
    }

    function isCommunity(info) {
        return !!info && info.edition === 'community';
    }

    /** 往 el 里写「OpsFlow 社区版 vX · AGPLv3 · 源码」；不是社区版时清空并隐藏，返回是否显示 */
    function render(el, info) {
        if (!el) {
            return false;
        }
        el.textContent = '';
        if (!isCommunity(info)) {
            el.hidden = true;
            return false;
        }
        const version = typeof info.version === 'string' ? info.version.trim() : '';
        const text = t('app.source.notice', { version: version ? 'v' + version : '' }).replace(/\s+/g, ' ');
        el.appendChild(document.createTextNode(text + ' · '));
        const link = document.createElement('a');
        link.href = sourceUrl(version);
        link.target = '_blank';
        link.rel = 'noopener noreferrer';
        link.textContent = t('app.source.link');
        el.appendChild(link);
        el.hidden = false;
        return true;
    }

    /** 读免登录的版本接口；失败时返回 null（按不显示处理） */
    async function fetchVersionInfo() {
        try {
            const response = await fetch('/api/system/version');
            return response.ok ? await response.json() : null;
        } catch (e) {
            return null;
        }
    }

    global.OpsFlowSourceNotice = { render, sourceUrl, isCommunity, fetchVersionInfo };
})(window);
