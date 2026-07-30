package com.opsflow.integration.feishu;

import com.opsflow.service.SystemConfigService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 飞书 SSO 登录配置（系统设置 → SSO登录 → 飞书）
 * 与 {@link FeishuConfig}（审批通知）完全独立。
 */
@Component
public class FeishuSsoConfig {

    public static final String TYPE = "feishu_sso";
    private static final String LEGACY_TYPE = "feishu";

    @Autowired
    private SystemConfigService systemConfigService;

    public boolean isEnabled() {
        if (systemConfigService.get(TYPE, "enabled") != null) {
            return systemConfigService.getBoolean(TYPE, "enabled", false);
        }
        return systemConfigService.getBoolean(LEGACY_TYPE, "enabled", false);
    }

    public String getAppId() {
        return first("appId");
    }

    public String getAppSecret() {
        return first("appSecret");
    }

    public String getRedirectUri() {
        String redirect = first("redirectUri");
        if (!StringUtils.hasText(redirect)) {
            redirect = first("ssoRedirectUrl");
        }
        return redirect;
    }

    public String getApiUrl() {
        String url = first("apiUrl");
        return StringUtils.hasText(url) ? url : "https://open.feishu.cn/open-apis";
    }

    private String first(String key) {
        String v = systemConfigService.get(TYPE, key);
        if (StringUtils.hasText(v)) {
            return v;
        }
        return systemConfigService.get(LEGACY_TYPE, key);
    }
}
