package com.opsflow.integration.feishu;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * 飞书SSO登录
 */
@Slf4j
@Component
public class FeishuSSO {
    
    @Autowired
    private FeishuConfig feishuConfig;
    
    @Autowired
    private FeishuClient feishuClient;
    
    private OkHttpClient httpClient = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build();
    
    /**
     * 获取SSO登录URL
     */
    public String getSSOUrl(String state) {
        try {
            String redirectUri = URLEncoder.encode(feishuConfig.getSsoRedirectUrl(), StandardCharsets.UTF_8.toString());
            state = URLEncoder.encode(state != null ? state : "", StandardCharsets.UTF_8.toString());
            
            return String.format(
                    "https://open.feishu.cn/open-apis/authen/v1/oidc/authorize?app_id=%s&redirect_uri=%s&response_type=code&state=%s",
                    feishuConfig.getAppId(),
                    redirectUri,
                    state
            );
        } catch (Exception e) {
            log.error("生成飞书SSO登录URL异常", e);
            return null;
        }
    }
    
    /**
     * 通过code获取用户信息
     */
    public FeishuUserInfo getUserInfoByCode(String code) {
        String token = feishuClient.getAccessToken();
        if (token == null) {
            log.error("无法获取飞书访问令牌");
            return null;
        }
        
        try {
            JSONObject requestBody = new JSONObject();
            requestBody.put("grant_type", "authorization_code");
            requestBody.put("code", code);
            
            RequestBody body = RequestBody.create(
                    requestBody.toJSONString(),
                    MediaType.parse("application/json; charset=utf-8")
            );
            
            Request request = new Request.Builder()
                    .url(feishuConfig.getApiUrl() + "/authen/v1/oidc/access_token")
                    .post(body)
                    .addHeader("Authorization", "Bearer " + token)
                    .build();
            
            try (Response response = httpClient.newCall(request).execute()) {
                if (response.isSuccessful() && response.body() != null) {
                    JSONObject result = JSON.parseObject(response.body().string());
                    if (result.getInteger("code") == 0) {
                        JSONObject data = result.getJSONObject("data");
                        String accessToken = data.getString("access_token");
                        return getUserInfo(accessToken);
                    }
                }
            }
        } catch (Exception e) {
            log.error("通过code获取用户信息异常", e);
        }
        
        return null;
    }
    
    /**
     * 通过access_token获取用户信息
     */
    private FeishuUserInfo getUserInfo(String accessToken) {
        try {
            Request request = new Request.Builder()
                    .url(feishuConfig.getApiUrl() + "/authen/v1/user_info")
                    .get()
                    .addHeader("Authorization", "Bearer " + accessToken)
                    .build();
            
            try (Response response = httpClient.newCall(request).execute()) {
                if (response.isSuccessful() && response.body() != null) {
                    JSONObject result = JSON.parseObject(response.body().string());
                    if (result.getInteger("code") == 0) {
                        JSONObject data = result.getJSONObject("data");
                        
                        FeishuUserInfo userInfo = new FeishuUserInfo();
                        userInfo.setUserId(data.getString("user_id"));
                        userInfo.setOpenId(data.getString("open_id"));
                        userInfo.setUnionId(data.getString("union_id"));
                        
                        JSONObject userInfoObj = data.getJSONObject("user_info");
                        if (userInfoObj != null) {
                            userInfo.setName(userInfoObj.getString("name"));
                            userInfo.setEmail(userInfoObj.getString("email"));
                            userInfo.setMobile(userInfoObj.getString("mobile"));
                            userInfo.setAvatar(userInfoObj.getString("avatar_url"));
                        }
                        
                        return userInfo;
                    }
                }
            }
        } catch (Exception e) {
            log.error("获取用户信息异常", e);
        }
        
        return null;
    }
    
    /**
     * 飞书用户信息
     */
    public static class FeishuUserInfo {
        private String userId;
        private String openId;
        private String unionId;
        private String name;
        private String email;
        private String mobile;
        private String avatar;
        
        // Getters and Setters
        public String getUserId() { return userId; }
        public void setUserId(String userId) { this.userId = userId; }
        
        public String getOpenId() { return openId; }
        public void setOpenId(String openId) { this.openId = openId; }
        
        public String getUnionId() { return unionId; }
        public void setUnionId(String unionId) { this.unionId = unionId; }
        
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        
        public String getEmail() { return email; }
        public void setEmail(String email) { this.email = email; }
        
        public String getMobile() { return mobile; }
        public void setMobile(String mobile) { this.mobile = mobile; }
        
        public String getAvatar() { return avatar; }
        public void setAvatar(String avatar) { this.avatar = avatar; }
    }
}









