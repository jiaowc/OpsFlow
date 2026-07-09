package com.opsflow.service.util;

import java.net.URI;

/**
 * 将组件地址与仓库路径拼接为完整 Git 仓库地址
 */
public final class GitRepoPathResolver {

    private GitRepoPathResolver() {
    }

    public static class ResolveResult {
        private final boolean success;
        private final String gitRepo;
        private final String errorMessage;

        private ResolveResult(boolean success, String gitRepo, String errorMessage) {
            this.success = success;
            this.gitRepo = gitRepo;
            this.errorMessage = errorMessage;
        }

        public static ResolveResult ok(String gitRepo) {
            return new ResolveResult(true, gitRepo, null);
        }

        public static ResolveResult fail(String errorMessage) {
            return new ResolveResult(false, null, errorMessage);
        }

        public boolean isSuccess() {
            return success;
        }

        public String getGitRepo() {
            return gitRepo;
        }

        public String getErrorMessage() {
            return errorMessage;
        }
    }

    public static ResolveResult resolve(String componentUrl, String repoPath) {
        if (componentUrl == null || componentUrl.trim().isEmpty()) {
            return ResolveResult.fail("组件访问地址不能为空");
        }
        if (repoPath == null || repoPath.trim().isEmpty()) {
            return ResolveResult.fail("仓库路径不能为空");
        }

        String baseUrl = trimTrailingSlash(componentUrl.trim());
        String path = repoPath.trim();

        if (isAbsoluteRepoPath(path)) {
            String repoHost = extractHost(path);
            String componentHost = extractHost(baseUrl);
            if (repoHost.isEmpty() || componentHost.isEmpty()) {
                return ResolveResult.fail("无法解析仓库或组件地址中的域名");
            }
            if (!hostsEqual(repoHost, componentHost)) {
                return ResolveResult.fail("仓库路径域名 (" + repoHost + ") 与组件地址域名 ("
                        + componentHost + ") 不一致，请检查");
            }
            return ResolveResult.ok(normalizeGitRepoUrl(path));
        }

        String relative = path.startsWith("/") ? path.substring(1) : path;
        String fullUrl = baseUrl + "/" + relative;
        return ResolveResult.ok(normalizeGitRepoUrl(fullUrl));
    }

    public static boolean isGitComponentType(String type) {
        if (type == null) {
            return false;
        }
        String normalized = type.trim().toLowerCase();
        return "gitlab".equals(normalized) || "github".equals(normalized);
    }

    private static boolean isAbsoluteRepoPath(String path) {
        String lower = path.toLowerCase();
        return lower.startsWith("http://") || lower.startsWith("https://") || path.startsWith("git@");
    }

    private static String extractHost(String value) {
        if (value == null || value.trim().isEmpty()) {
            return "";
        }
        value = value.trim();
        if (value.startsWith("git@")) {
            int colon = value.indexOf(':');
            if (colon > 4) {
                return value.substring(4, colon).toLowerCase();
            }
            return "";
        }
        try {
            URI uri = URI.create(value);
            if (uri.getHost() != null) {
                return uri.getHost().toLowerCase();
            }
        } catch (Exception ignored) {
            // ignore
        }
        return "";
    }

    private static boolean hostsEqual(String host1, String host2) {
        return normalizeHost(host1).equals(normalizeHost(host2));
    }

    private static String normalizeHost(String host) {
        if (host == null) {
            return "";
        }
        String normalized = host.trim().toLowerCase();
        if (normalized.startsWith("www.")) {
            normalized = normalized.substring(4);
        }
        return normalized;
    }

    private static String trimTrailingSlash(String url) {
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        return url;
    }

    private static String normalizeGitRepoUrl(String url) {
        return url.trim();
    }
}
