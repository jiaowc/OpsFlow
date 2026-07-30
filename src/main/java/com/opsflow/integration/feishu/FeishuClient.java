package com.opsflow.integration.feishu;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.concurrent.TimeUnit;

/**
 * 飞书 API 客户端
 */
@Slf4j
@Component
public class FeishuClient {

    @Autowired
    private FeishuConfig feishuConfig;

    private final OkHttpClient httpClient = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build();

    private String accessToken;
    private long tokenExpireTime;
    private String tokenAppId;
    private String tokenAppSecret;

    public boolean isConfigured() {
        return StringUtils.hasText(feishuConfig.getAppId())
                && StringUtils.hasText(feishuConfig.getAppSecret());
    }

    /**
     * 配置变更后强制刷新 token
     */
    public synchronized void invalidateToken() {
        accessToken = null;
        tokenExpireTime = 0;
        tokenAppId = null;
        tokenAppSecret = null;
    }

    /**
     * 获取租户访问令牌
     */
    public synchronized String getAccessToken() {
        String appId = feishuConfig.getAppId();
        String appSecret = feishuConfig.getAppSecret();
        if (accessToken != null
                && System.currentTimeMillis() < tokenExpireTime
                && appId != null && appId.equals(tokenAppId)
                && appSecret != null && appSecret.equals(tokenAppSecret)) {
            return accessToken;
        }
        if (!isConfigured()) {
            log.warn("飞书审批通知 AppId/AppSecret 未配置（请在系统设置 → 通知设置 → 飞书审批通知中填写）");
            return null;
        }

        try {
            return fetchAndCacheNotifyToken(appId, appSecret);
        } catch (Exception e) {
            log.error("获取飞书访问令牌异常", e);
        }
        return null;
    }

    /**
     * 按指定凭证获取 tenant_access_token（供 SSO 等独立场景使用，不污染通知 token 缓存）
     */
    public String fetchTenantAccessToken(String appId, String appSecret, String apiUrl) {
        if (!StringUtils.hasText(appId) || !StringUtils.hasText(appSecret)) {
            return null;
        }
        String base = StringUtils.hasText(apiUrl) ? apiUrl : "https://open.feishu.cn/open-apis";
        try {
            JSONObject requestBody = new JSONObject();
            requestBody.put("app_id", appId);
            requestBody.put("app_secret", appSecret);
            RequestBody body = RequestBody.create(
                    requestBody.toJSONString(),
                    MediaType.parse("application/json; charset=utf-8")
            );
            Request request = new Request.Builder()
                    .url(base + "/auth/v3/tenant_access_token/internal")
                    .post(body)
                    .build();
            try (Response response = httpClient.newCall(request).execute()) {
                if (response.isSuccessful() && response.body() != null) {
                    JSONObject result = JSON.parseObject(response.body().string());
                    if (result.getInteger("code") != null && result.getInteger("code") == 0) {
                        return result.getString("tenant_access_token");
                    }
                    log.error("获取飞书访问令牌失败: {}", result.getString("msg"));
                }
            }
        } catch (Exception e) {
            log.error("获取飞书访问令牌异常", e);
        }
        return null;
    }

    private String fetchAndCacheNotifyToken(String appId, String appSecret) throws Exception {
            JSONObject requestBody = new JSONObject();
            requestBody.put("app_id", appId);
            requestBody.put("app_secret", appSecret);

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
                    if (result.getInteger("code") != null && result.getInteger("code") == 0) {
                        accessToken = result.getString("tenant_access_token");
                        int expire = result.getInteger("expire") != null ? result.getInteger("expire") : 7200;
                        tokenExpireTime = System.currentTimeMillis() + (expire - 300) * 1000L;
                        tokenAppId = appId;
                        tokenAppSecret = appSecret;
                        return accessToken;
                    }
                    log.error("获取飞书访问令牌失败: {}", result.getString("msg"));
                }
            }
            return null;
    }

    /**
     * 发送消息，成功返回 message_id
     */
    public String sendMessage(String receiveId, String receiveIdType, String msgType, String contentJson) {
        String token = getAccessToken();
        if (token == null) {
            log.error("无法获取飞书访问令牌");
            return null;
        }
        if (!StringUtils.hasText(receiveId) || !StringUtils.hasText(contentJson)) {
            return null;
        }

        try {
            JSONObject requestBody = new JSONObject();
            requestBody.put("receive_id", receiveId);
            requestBody.put("msg_type", msgType);
            requestBody.put("content", contentJson);

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
                String resp = response.body() != null ? response.body().string() : "";
                if (!response.isSuccessful()) {
                    log.error("发送飞书消息 HTTP 失败: status={}, body={}", response.code(), resp);
                    return null;
                }
                JSONObject result = JSON.parseObject(resp);
                if (result.getInteger("code") != null && result.getInteger("code") == 0) {
                    JSONObject data = result.getJSONObject("data");
                    return data != null ? data.getString("message_id") : null;
                }
                log.error("发送飞书消息失败: {}", resp);
            }
        } catch (Exception e) {
            log.error("发送飞书消息异常", e);
        }
        return null;
    }

    public boolean sendTextMessage(String receiveId, String text) {
        JSONObject content = new JSONObject();
        content.put("text", text);
        String receiveIdType = StringUtils.hasText(feishuConfig.getReceiveIdType())
                ? feishuConfig.getReceiveIdType() : "user_id";
        return sendMessage(receiveId, receiveIdType, "text", content.toJSONString()) != null;
    }

    /**
     * 发送交互卡片，返回 message_id
     */
    public String sendInteractiveCard(String receiveId, JSONObject card) {
        String receiveIdType = StringUtils.hasText(feishuConfig.getReceiveIdType())
                ? feishuConfig.getReceiveIdType() : "user_id";
        return sendInteractiveCard(receiveId, receiveIdType, card);
    }

    public String sendInteractiveCard(String receiveId, String receiveIdType, JSONObject card) {
        if (card == null) {
            return null;
        }
        // interactive 的 content 即为卡片 JSON 本身
        return sendMessage(receiveId, receiveIdType, "interactive", card.toJSONString());
    }

    /**
     * 更新已发送的交互卡片
     */
    public boolean updateInteractiveMessage(String messageId, JSONObject card) {
        String token = getAccessToken();
        if (token == null || !StringUtils.hasText(messageId) || card == null) {
            return false;
        }
        try {
            JSONObject requestBody = new JSONObject();
            requestBody.put("msg_type", "interactive");
            requestBody.put("content", card.toJSONString());

            RequestBody body = RequestBody.create(
                    requestBody.toJSONString(),
                    MediaType.parse("application/json; charset=utf-8")
            );

            Request request = new Request.Builder()
                    .url(feishuConfig.getApiUrl() + "/im/v1/messages/" + messageId)
                    .patch(body)
                    .addHeader("Authorization", "Bearer " + token)
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                String resp = response.body() != null ? response.body().string() : "";
                if (!response.isSuccessful()) {
                    log.error("更新飞书消息 HTTP 失败: status={}, body={}", response.code(), resp);
                    return false;
                }
                JSONObject result = JSON.parseObject(resp);
                boolean ok = result.getInteger("code") != null && result.getInteger("code") == 0;
                if (!ok) {
                    log.error("更新飞书消息失败: {}", resp);
                }
                return ok;
            }
        } catch (Exception e) {
            log.error("更新飞书消息异常", e);
            return false;
        }
    }

    public String getUserIdByMobile(String mobile) {
        return resolveUserId("mobiles", mobile);
    }

    public String getUserIdByEmail(String email) {
        return resolveUserId("emails", email);
    }

    private String resolveUserId(String field, String value) {
        String token = getAccessToken();
        if (token == null || !StringUtils.hasText(value)) {
            return null;
        }
        try {
            JSONObject requestBody = new JSONObject();
            JSONArray arr = new JSONArray();
            arr.add(value);
            requestBody.put(field, arr);

            RequestBody body = RequestBody.create(
                    requestBody.toJSONString(),
                    MediaType.parse("application/json; charset=utf-8")
            );

            Request request = new Request.Builder()
                    .url(feishuConfig.getApiUrl() + "/contact/v3/users/batch_get_id?user_id_type=user_id")
                    .post(body)
                    .addHeader("Authorization", "Bearer " + token)
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                String resp = response.body() != null ? response.body().string() : "";
                if (!response.isSuccessful()) {
                    log.error("批量查询飞书用户失败: status={}, body={}", response.code(), resp);
                    return null;
                }
                JSONObject result = JSON.parseObject(resp);
                if (result.getInteger("code") == null || result.getInteger("code") != 0) {
                    log.error("批量查询飞书用户失败: {}", resp);
                    return null;
                }
                JSONObject data = result.getJSONObject("data");
                if (data == null) {
                    return null;
                }
                JSONArray userList = data.getJSONArray("user_list");
                if (userList == null || userList.isEmpty()) {
                    return null;
                }
                JSONObject first = userList.getJSONObject(0);
                return first != null ? first.getString("user_id") : null;
            }
        } catch (Exception e) {
            log.error("通过 {} 获取飞书用户 ID 异常", field, e);
            return null;
        }
    }
}
