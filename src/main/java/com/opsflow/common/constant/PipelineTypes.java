package com.opsflow.common.constant;

/**
 * 流水线模版类型常量与工具方法。
 * <p>
 * 合法取值：{@link #CI}（仅构建）、{@link #CD}（仅部署）、{@link #CICD}（构建+部署）。
 * 上线任务创建时要求关联类型为 CD 的模版。
 * </p>
 */
public final class PipelineTypes {

    /** 持续集成：拉代码、构建镜像等 */
    public static final String CI = "ci";
    /** 持续部署：渲染模版、部署到 K8s 等；上线任务必须使用此类型 */
    public static final String CD = "cd";
    /** CI/CD 全链路：构建与部署步骤均包含 */
    public static final String CICD = "cicd";

    private PipelineTypes() {
    }

    /**
     * 判断是否为合法的流水线类型。
     *
     * @param type 已规范化的类型字符串
     * @return 是否为 ci / cd / cicd 之一
     */
    public static boolean isValid(String type) {
        return CI.equals(type) || CD.equals(type) || CICD.equals(type);
    }

    /**
     * 规范化类型字符串：去空白、转小写，并将 ci/cd、ci-cd 等别名统一为 cicd。
     *
     * @param type 原始类型；为 null 时返回 null
     * @return 规范化后的类型
     */
    public static String normalize(String type) {
        if (type == null) {
            return null;
        }
        String t = type.trim().toLowerCase();
        if ("ci/cd".equals(t) || "ci-cd".equals(t) || "ci_cd".equals(t)) {
            return CICD;
        }
        return t;
    }

    /**
     * 是否支持「回滚到历史镜像」：CD 与 CI/CD（含部署能力）可用；纯 CI 不可用。
     */
    public static boolean supportsImageRollback(String type) {
        String t = normalize(type);
        return CD.equals(t) || CICD.equals(t);
    }

    /**
     * 返回类型的中文/英文展示名，用于错误提示与界面展示。
     *
     * @param type 原始或已规范化的类型
     * @return 展示名，如 CI、CD、CI/CD；未知时返回原值或 "-"
     */
    public static String displayName(String type) {
        String t = normalize(type);
        if (CI.equals(t)) {
            return "CI";
        }
        if (CD.equals(t)) {
            return "CD";
        }
        if (CICD.equals(t)) {
            return "CI/CD";
        }
        return type != null ? type : "-";
    }
}
