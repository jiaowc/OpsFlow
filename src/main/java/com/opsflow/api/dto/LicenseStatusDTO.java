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

    private String issuedAt;

    private String expiresAt;

    private List<String> features = new ArrayList<>();

    /** 是否已开通上线审批 */
    private boolean deployApprovalEnabled;

    /**
     * 是否启用 License 强校验。
     * false 表示当前已关闭授权拦截（配置 opsflow.license.enforcement-enabled=false），所有功能开放。
     */
    private boolean enforcementEnabled = true;

    private String message;
}
