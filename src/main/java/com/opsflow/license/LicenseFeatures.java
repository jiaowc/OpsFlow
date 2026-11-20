package com.opsflow.license;

/**
 * License 功能码（与 docs/licensing/OPEN_CORE_BOUNDARY.md 保持一致）。
 * <p>社区版不含下列码；企业版包含全部。
 */
public final class LicenseFeatures {

    /** 上线审批（审批流、上线任务、审批动作） */
    public static final String DEPLOY_APPROVAL = "deploy_approval";

    /** 操作审计查询（写入可不锁，查询需本码） */
    public static final String AUDIT = "audit";

    /** 外部集成通知（飞书等；站内信不锁） */
    public static final String NOTIFY_INTEGRATION = "notify_integration";

    /** 生产环境回滚（非生产回滚不锁） */
    public static final String PROD_ROLLBACK = "prod_rollback";

    /** 企业品牌定制（登录页 / 控制台 Logo） */
    public static final String BRANDING = "branding";

    private LicenseFeatures() {
    }
}
