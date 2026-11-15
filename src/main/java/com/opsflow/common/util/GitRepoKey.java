package com.opsflow.common.util;

import java.util.Locale;

/**
 * 把各种形式的 Git 仓库地址归一为 "group/sub/project"，用于比较 Webhook 推送的仓库与服务配置的仓库。
 * <p>例：https://git.x.com/a/b.git、git@git.x.com:a/b.git、ssh://git@git.x.com:2222/a/b → a/b</p>
 */
public final class GitRepoKey {

    private GitRepoKey() {
    }

    public static String normalize(String repo) {
        if (repo == null) {
            return null;
        }
        String s = repo.trim().toLowerCase(Locale.ROOT);
        if (s.isEmpty()) {
            return null;
        }
        int scheme = s.indexOf("://");
        if (scheme >= 0) {
            s = s.substring(scheme + 3);
            int slash = s.indexOf('/');
            s = slash >= 0 ? s.substring(slash + 1) : "";
        } else if (s.contains("@") && s.contains(":")) {
            // scp 风格：git@host:group/project.git
            s = s.substring(s.indexOf(':') + 1);
        }
        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        if (s.endsWith(".git")) {
            s = s.substring(0, s.length() - 4);
        }
        while (s.startsWith("/")) {
            s = s.substring(1);
        }
        return s.isEmpty() ? null : s;
    }
}
