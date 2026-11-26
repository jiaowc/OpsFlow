package com.opsflow.integration.pipeline;

/**
 * 工作目录路径解析（支持 ~/opsflow 形式）
 */
public final class WorkspacePathHelper {

    public static final String DEFAULT_WORK_DIR = "~/opsflow";

    private WorkspacePathHelper() {
    }

    public static String normalizeDefault(String workDir) {
        if (workDir == null || workDir.trim().isEmpty()) {
            return DEFAULT_WORK_DIR;
        }
        return workDir.trim();
    }

    /**
     * 本地文件系统路径（展开 ~）
     */
    public static String toLocalPath(String path) {
        if (path == null || path.isEmpty()) {
            return path;
        }
        String trimmed = path.trim();
        if (trimmed.startsWith("~/")) {
            return System.getProperty("user.home") + trimmed.substring(1);
        }
        if ("~".equals(trimmed)) {
            return System.getProperty("user.home");
        }
        return trimmed;
    }

    /**
     * 远程 shell 路径（在 bash 中使用 $HOME 展开 ~）
     */
    public static String toRemoteShellPath(String path) {
        if (path == null || path.isEmpty()) {
            return "''";
        }
        String trimmed = path.trim();
        if (trimmed.startsWith("~/")) {
            return "\"$HOME/" + escapeForDoubleQuotedShell(trimmed.substring(2)) + "\"";
        }
        if ("~".equals(trimmed)) {
            return "\"$HOME\"";
        }
        if (trimmed.startsWith("~")) {
            return "\"$HOME" + escapeForDoubleQuotedShell(trimmed.substring(1)) + "\"";
        }
        return NodeCommandHelper.shellQuote(trimmed);
    }

    private static String escapeForDoubleQuotedShell(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
