package com.opsflow.integration.feishu;

import com.opsflow.service.SystemConfigService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 飞书「审批通知」配置（系统设置 → 通知设置）
 * 与 {@link FeishuSsoConfig} 完全独立，可用不同飞书应用。
 */
@Component
public class FeishuConfig {

    public static final String TYPE = "feishu_notify";
    private static final String LEGACY_TYPE = "feishu";

    @Autowired
    private SystemConfigService systemConfigService;

    public boolean isEnabled() {
        if (systemConfigService.get(TYPE, "approvalEnabled") != null) {
            return systemConfigService.getBoolean(TYPE, "approvalEnabled", false);
        }
        return systemConfigService.getBoolean(LEGACY_TYPE, "approvalEnabled", false);
    }

    public String getAppId() {
        return first("appId");
    }

    public String getAppSecret() {
        return first("appSecret");
    }

    public String getVerificationToken() {
        return first("verificationToken");
    }

    public String getEncryptKey() {
        return first("encryptKey");
    }

    public String getApiUrl() {
        String url = first("apiUrl");
        return StringUtils.hasText(url) ? url : "https://open.feishu.cn/open-apis";
    }

    public String getReceiveIdType() {
        String type = first("receiveIdType");
        return StringUtils.hasText(type) ? type : "user_id";
    }

    public String getApprovalCallbackUrl() {
        return first("approvalCallbackUrl");
    }

    /**
     * OpsFlow 访问地址（内网地址即可），写入飞书通知方便跳转系统内审批
     */
    public String getPortalUrl() {
        String portal = first("portalUrl");
        if (StringUtils.hasText(portal)) {
            return portal;
        }
        return systemConfigService.get("notify", "portalUrl");
    }

    private String first(String key) {
        String v = systemConfigService.get(TYPE, key);
        if (StringUtils.hasText(v)) {
            return v;
        }
        return systemConfigService.get(LEGACY_TYPE, key);
    }
}
