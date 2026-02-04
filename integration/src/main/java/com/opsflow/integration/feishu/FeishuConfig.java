package com.opsflow.integration.feishu;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 飞书配置
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "feishu")
public class FeishuConfig {
    
    /**
     * 飞书应用App ID
     */
    private String appId;
    
    /**
     * 飞书应用App Secret
     */
    private String appSecret;
    
    /**
     * 飞书API地址
     */
    private String apiUrl = "https://open.feishu.cn/open-apis";
    
    /**
     * 飞书SSO登录重定向地址
     */
    private String ssoRedirectUrl;
    
    /**
     * 飞书审批回调地址
     */
    private String approvalCallbackUrl;
}










