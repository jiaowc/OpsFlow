package com.opsflow.api.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class LicenseStatusDTO {

    private boolean valid;

    private boolean present;

    private String customer;

    private String licenseId;

    /** 订阅计划：enterprise，可空 */
    private String plan;

    private String issuedAt;

    private String expiresAt;

    /** 距到期剩余整天数；已过期为负数；永不过期为 null */
    private Long daysRemaining;

    /** 是否处于过期宽限期内（商业功能仍可用，但应续费） */
    private boolean inGracePeriod;

    private List<String> features = new ArrayList<>();

    /** 是否已开通上线审批 */
    private boolean deployApprovalEnabled;

    /** 是否已开通操作审计查询 */
    private boolean auditEnabled;

    /** 是否已开通外部集成通知 */
    private boolean notifyIntegrationEnabled;

    /** 是否已开通生产环境回滚 */
    private boolean prodRollbackEnabled;

    /** 是否已开通企业品牌定制（自定义 Logo） */
    private boolean brandingEnabled;

    /**
     * 是否启用 License 强校验。
     * false 表示当前已关闭授权拦截（配置 opsflow.license.enforcement-enabled=false），所有功能开放。
     */
    private boolean enforcementEnabled = true;

    private String message;
}
