package com.opsflow.integration.feishu;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * 飞书API客户端
 */
@Slf4j
@Component
public class FeishuClient {
    
    @Autowired
    private FeishuConfig feishuConfig;
    
    private OkHttpClient httpClient = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build();
    
    private String accessToken;
    private long tokenExpireTime;
    
    /**
     * 获取访问令牌
     */
    public String getAccessToken() {
        if (accessToken != null && System.currentTimeMillis() < tokenExpireTime) {
            return accessToken;
        }
        
        try {
            JSONObject requestBody = new JSONObject();
            requestBody.put("app_id", feishuConfig.getAppId());
            requestBody.put("app_secret", feishuConfig.getAppSecret());
            
            RequestBody body = RequestBody.create(
                    requestBody.toJSONString(),
                    MediaType.parse("application/json; charset=utf-8")
            );
            
            Request request = new Request.Builder()
                    .url(feishuConfig.getApiUrl() + "/auth/v3/tenant_access_token/internal")
                    .post(body)
                    .build();
            
            try (Response response = httpClient.newCall(request).execute()) {
                if (response.isSuccessful() && response.body() != null) {
                    JSONObject result = JSON.parseObject(response.body().string());
                    if (result.getInteger("code") == 0) {
                        accessToken = result.getString("tenant_access_token");
                        int expire = result.getInteger("expire") != null ? result.getInteger("expire") : 7200;
                        tokenExpireTime = System.currentTimeMillis() + (expire - 300) * 1000L; // 提前5分钟刷新
                        return accessToken;
                    } else {
                        log.error("获取飞书访问令牌失败: {}", result.getString("msg"));
                    }
                }
            }
        } catch (Exception e) {
            log.error("获取飞书访问令牌异常", e);
        }
        
        return null;
    }
    
    /**
     * 发送消息
     */
    public boolean sendMessage(String receiveId, String receiveIdType, String msgType, JSONObject content) {
        String token = getAccessToken();
        if (token == null) {
            log.error("无法获取飞书访问令牌");
            return false;
        }
        
        try {
            JSONObject requestBody = new JSONObject();
            requestBody.put("receive_id", receiveId);
            requestBody.put("receive_id_type", receiveIdType); // open_id, user_id, email, chat_id
            requestBody.put("msg_type", msgType); // text, interactive
            requestBody.put("content", content.toJSONString());
            
            RequestBody body = RequestBody.create(
                    requestBody.toJSONString(),
                    MediaType.parse("application/json; charset=utf-8")
            );
            
            Request request = new Request.Builder()
                    .url(feishuConfig.getApiUrl() + "/im/v1/messages?receive_id_type=" + receiveIdType)
                    .post(body)
                    .addHeader("Authorization", "Bearer " + token)
                    .build();
            
            try (Response response = httpClient.newCall(request).execute()) {
                if (response.isSuccessful() && response.body() != null) {
                    JSONObject result = JSON.parseObject(response.body().string());
                    return result.getInteger("code") == 0;
                }
            }
        } catch (Exception e) {
            log.error("发送飞书消息异常", e);
        }
        
        return false;
    }
    
    /**
     * 发送文本消息
     */
    public boolean sendTextMessage(String userId, String text) {
        JSONObject content = new JSONObject();
        content.put("text", text);
        return sendMessage(userId, "user_id", "text", content);
    }
    
    /**
     * 发送交互式卡片消息（用于审批）
     */
    public boolean sendInteractiveMessage(String userId, JSONObject card) {
        JSONObject content = new JSONObject();
        content.put("card", card);
        return sendMessage(userId, "user_id", "interactive", content);
    }
    
    /**
     * 通过用户手机号获取用户ID
     */
    public String getUserIdByMobile(String mobile) {
        String token = getAccessToken();
        if (token == null) {
            return null;
        }
        
        try {
            JSONObject requestBody = new JSONObject();
            requestBody.put("mobiles", new String[]{mobile});
            
            RequestBody body = RequestBody.create(
                    requestBody.toJSONString(),
                    MediaType.parse("application/json; charset=utf-8")
            );
            
            Request request = new Request.Builder()
                    .url(feishuConfig.getApiUrl() + "/contact/v3/users/batch_get_id")
                    .post(body)
                    .addHeader("Authorization", "Bearer " + token)
                    .build();
            
            try (Response response = httpClient.newCall(request).execute()) {
                if (response.isSuccessful() && response.body() != null) {
                    JSONObject result = JSON.parseObject(response.body().string());
                    if (result.getInteger("code") == 0) {
                        JSONObject data = result.getJSONObject("data");
                        if (data != null && data.containsKey("user_list")) {
                            JSONObject userList = data.getJSONObject("user_list");
                            if (userList.containsKey(mobile)) {
                                return userList.getJSONObject(mobile).getString("user_id");
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.error("通过手机号获取飞书用户ID异常", e);
        }
        
        return null;
    }
    
    /**
     * 通过用户邮箱获取用户ID
     */
    public String getUserIdByEmail(String email) {
        String token = getAccessToken();
        if (token == null) {
            return null;
        }
        
        try {
            Request request = new Request.Builder()
                    .url(feishuConfig.getApiUrl() + "/contact/v3/users/batch_get_id?user_id_type=user_id&emails=" + email)
                    .get()
                    .addHeader("Authorization", "Bearer " + token)
                    .build();
            
            try (Response response = httpClient.newCall(request).execute()) {
                if (response.isSuccessful() && response.body() != null) {
                    JSONObject result = JSON.parseObject(response.body().string());
                    if (result.getInteger("code") == 0) {
                        JSONObject data = result.getJSONObject("data");
                        if (data != null && data.containsKey("user_list")) {
                            JSONObject userList = data.getJSONObject("user_list");
                            if (userList.containsKey(email)) {
                                return userList.getJSONObject(email).getString("user_id");
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.error("通过邮箱获取飞书用户ID异常", e);
        }
        
        return null;
    }
}









