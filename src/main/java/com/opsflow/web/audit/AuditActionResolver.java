package com.opsflow.web.audit;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 根据请求路径和方法得出审计日志的「模块」和「动作」中文名称。
 */
public final class AuditActionResolver {

    private static final Map<String, String> MODULES = new HashMap<>();
    private static final Map<String, String> ACTIONS = new HashMap<>();

    /** 只读或高频无业务含义的写请求，不记审计 */
    private static final List<String> SKIP_PREFIXES = Arrays.asList(
            "/api/inbox/",
            "/api/feishu/callback",
            "/api/service/git/refs/",
            "/api/pipeline-template/check-variables",
            "/api/audit/"
    );

    /** 这些模块的请求体可能含密钥/证书等，只记录路径不记录参数 */
    private static final Set<String> BODY_HIDDEN_MODULES = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "credential", "system", "license", "webhook"
    )));

    static {
        MODULES.put("task", "上线任务");
        MODULES.put("pipeline-run", "流水线运行");
        MODULES.put("build", "构建");
        MODULES.put("pipeline", "流水线配置");
        MODULES.put("pipeline-template", "流水线模版");
        MODULES.put("pipeline-step-def", "流水线步骤");
        MODULES.put("pipeline-view", "工作视图");
        MODULES.put("service", "服务");
        MODULES.put("env", "环境");
        MODULES.put("cluster", "集群");
        MODULES.put("node", "节点");
        MODULES.put("credential", "凭据");
        MODULES.put("component", "组件");
        MODULES.put("approval", "审批");
        MODULES.put("user", "用户");
        MODULES.put("role", "角色");
        MODULES.put("permission", "权限");
        MODULES.put("system", "系统配置");
        MODULES.put("license", "License");
        MODULES.put("auth", "登录");
        MODULES.put("webhook", "Git Webhook");
        MODULES.put("project", "项目");

        ACTIONS.put("create", "新增");
        ACTIONS.put("publish", "发布");
        ACTIONS.put("rollback", "回滚");
        ACTIONS.put("build", "执行构建");
        ACTIONS.put("start", "执行构建");
        ACTIONS.put("lock", "锁定");
        ACTIONS.put("unlock", "解锁");
        ACTIONS.put("submit-approval", "提交审批");
        ACTIONS.put("approve", "审批通过");
        ACTIONS.put("reject", "审批拒绝");
        ACTIONS.put("clone", "克隆");
        ACTIONS.put("test", "连通性测试");
        ACTIONS.put("check-env", "环境检查");
        ACTIONS.put("import", "导入");
        ACTIONS.put("login", "登录");
        ACTIONS.put("logout", "退出登录");
        ACTIONS.put("reorder", "调整排序");
        ACTIONS.put("config", "保存配置");
        ACTIONS.put("batch", "批量操作");
        ACTIONS.put("git", "推送触发构建");
        ACTIONS.put("webhook", "设置推送自动构建");
        ACTIONS.put("retention", "保留策略清理");
        ACTIONS.put("members", "调整项目成员");
    }

    private AuditActionResolver() {
    }

    public static boolean shouldAudit(String method, String path) {
        if (path == null || !path.startsWith("/api/")) {
            return false;
        }
        if ("GET".equals(method) || "HEAD".equals(method) || "OPTIONS".equals(method)) {
            return false;
        }
        for (String prefix : SKIP_PREFIXES) {
            if (path.startsWith(prefix)) {
                return false;
            }
        }
        return true;
    }

    /** /api/task/12/publish → task */
    public static String moduleKey(String path) {
        String[] segments = segments(path);
        return segments.length > 0 ? segments[0] : "";
    }

    public static String moduleName(String path) {
        String key = moduleKey(path);
        return MODULES.getOrDefault(key, key);
    }

    public static boolean hideBody(String path) {
        return BODY_HIDDEN_MODULES.contains(moduleKey(path));
    }

    public static String actionName(String method, String path) {
        String[] segments = segments(path);
        for (int i = segments.length - 1; i >= 1; i--) {
            String seg = segments[i];
            if (isIdLike(seg)) {
                continue;
            }
            String action = ACTIONS.get(seg);
            if (action != null) {
                return action;
            }
            break;
        }
        switch (method) {
            case "PUT":
            case "PATCH":
                return "修改";
            case "DELETE":
                return "删除";
            default:
                return method + " " + (segments.length > 1 ? segments[segments.length - 1] : "");
        }
    }

    private static String[] segments(String path) {
        String rest = path.startsWith("/api/") ? path.substring(5) : path;
        return Arrays.stream(rest.split("/")).filter(s -> !s.isEmpty()).toArray(String[]::new);
    }

    private static boolean isIdLike(String seg) {
        return seg.chars().allMatch(Character::isDigit);
    }
}
