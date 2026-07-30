package com.opsflow.integration.feishu;

import com.alibaba.fastjson.JSON;
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

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * 飞书 SSO 登录（浏览器回跳授权码模式，服务端出网换票；不依赖公网入站）。
 * 仅使用 {@link FeishuSsoConfig}。
 */
@Slf4j
@Component
public class FeishuSSO {

    private static final MediaType JSON_TYPE = MediaType.parse("application/json; charset=utf-8");

    @Autowired
    private FeishuSsoConfig feishuSsoConfig;

    @Autowired
    private FeishuClient feishuClient;

    private final OkHttpClient httpClient = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build();

    public boolean isReady() {
        return feishuSsoConfig.isEnabled()
                && StringUtils.hasText(feishuSsoConfig.getAppId())
                && StringUtils.hasText(feishuSsoConfig.getAppSecret())
                && StringUtils.hasText(feishuSsoConfig.getRedirectUri());
    }

    public String getSSOUrl(String state) {
        try {
            if (!StringUtils.hasText(feishuSsoConfig.getAppId())
                    || !StringUtils.hasText(feishuSsoConfig.getRedirectUri())) {
                log.warn("飞书 SSO 未配置 App ID 或回调地址");
                return null;
            }
            String redirectUri = URLEncoder.encode(feishuSsoConfig.getRedirectUri(), StandardCharsets.UTF_8.name());
            String stateEnc = URLEncoder.encode(state != null ? state : "", StandardCharsets.UTF_8.name());
            return String.format(
                    "https://open.feishu.cn/open-apis/authen/v1/authorize?app_id=%s&redirect_uri=%s&response_type=code&state=%s",
                    feishuSsoConfig.getAppId(),
                    redirectUri,
                    stateEnc
            );
        } catch (Exception e) {
            log.error("生成飞书SSO登录URL异常", e);
            return null;
        }
    }

    public FeishuUserInfo getUserInfoByCode(String code) {
        if (!StringUtils.hasText(code)) {
            return null;
        }
        String userAccessToken = exchangeCodeForUserAccessToken(code);
        if (!StringUtils.hasText(userAccessToken)) {
            return null;
        }
        return getUserInfo(userAccessToken);
    }

    /**
     * 优先 OAuth v2（client_id/secret + code，纯出网）；失败再回退旧版 app_access_token 方式。
     */
    private String exchangeCodeForUserAccessToken(String code) {
        String token = exchangeViaOauthV2(code);
        if (StringUtils.hasText(token)) {
            return token;
        }
        return exchangeViaOidcV1(code);
    }

    private String exchangeViaOauthV2(String code) {
        try {
            JSONObject requestBody = new JSONObject();
            requestBody.put("grant_type", "authorization_code");
            requestBody.put("client_id", feishuSsoConfig.getAppId());
            requestBody.put("client_secret", feishuSsoConfig.getAppSecret());
            requestBody.put("code", code);
            requestBody.put("redirect_uri", feishuSsoConfig.getRedirectUri());

            Request request = new Request.Builder()
                    .url(feishuSsoConfig.getApiUrl() + "/authen/v2/oauth/token")
                    .post(RequestBody.create(requestBody.toJSONString(), JSON_TYPE))
                    .addHeader("Content-Type", "application/json; charset=utf-8")
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                String raw = response.body() != null ? response.body().string() : "";
                if (!response.isSuccessful()) {
                    log.warn("飞书 OAuth v2 换票 HTTP 失败: status={}, body={}", response.code(), raw);
                    return null;
                }
                JSONObject result = JSON.parseObject(raw);
                Integer err = result.getInteger("code");
                if (err != null && err != 0) {
                    log.warn("飞书 OAuth v2 换票业务失败: {}", raw);
                    return null;
                }
                JSONObject data = result.getJSONObject("data");
                if (data == null) {
                    // 部分版本字段在根上
                    return firstNonEmpty(result.getString("access_token"), null);
                }
                return firstNonEmpty(data.getString("access_token"), result.getString("access_token"));
            }
        } catch (Exception e) {
            log.warn("飞书 OAuth v2 换票异常: {}", e.getMessage());
            return null;
        }
    }

    private String exchangeViaOidcV1(String code) {
        String appToken = feishuClient.fetchTenantAccessToken(
                feishuSsoConfig.getAppId(),
                feishuSsoConfig.getAppSecret(),
                feishuSsoConfig.getApiUrl()
        );
        if (appToken == null) {
            log.error("无法获取飞书 app/tenant access token（请检查系统设置 → SSO登录 → 飞书）");
            return null;
        }
        try {
            JSONObject requestBody = new JSONObject();
            requestBody.put("grant_type", "authorization_code");
            requestBody.put("code", code);

            Request request = new Request.Builder()
                    .url(feishuSsoConfig.getApiUrl() + "/authen/v1/access_token")
                    .post(RequestBody.create(requestBody.toJSONString(), JSON_TYPE))
                    .addHeader("Authorization", "Bearer " + appToken)
                    .addHeader("Content-Type", "application/json; charset=utf-8")
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                String raw = response.body() != null ? response.body().string() : "";
                if (!response.isSuccessful()) {
                    log.warn("飞书 v1 access_token HTTP 失败: status={}, body={}", response.code(), raw);
                    return null;
                }
                JSONObject result = JSON.parseObject(raw);
                if (result.getInteger("code") == null || result.getInteger("code") != 0) {
                    log.warn("飞书 v1 access_token 业务失败: {}", raw);
                    return null;
                }
                JSONObject data = result.getJSONObject("data");
                return data != null ? data.getString("access_token") : null;
            }
        } catch (Exception e) {
            log.error("飞书 v1 换票异常", e);
            return null;
        }
    }

    private FeishuUserInfo getUserInfo(String accessToken) {
        try {
            Request request = new Request.Builder()
                    .url(feishuSsoConfig.getApiUrl() + "/authen/v1/user_info")
                    .get()
                    .addHeader("Authorization", "Bearer " + accessToken)
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                String raw = response.body() != null ? response.body().string() : "";
                if (!response.isSuccessful()) {
                    log.warn("飞书获取用户信息 HTTP 失败: status={}, body={}", response.code(), raw);
                    return null;
                }
                JSONObject result = JSON.parseObject(raw);
                if (result.getInteger("code") == null || result.getInteger("code") != 0) {
                    log.warn("飞书获取用户信息业务失败: {}", raw);
                    return null;
                }
                JSONObject data = result.getJSONObject("data");
                if (data == null) {
                    return null;
                }
                FeishuUserInfo userInfo = new FeishuUserInfo();
                userInfo.setUserId(firstNonEmpty(data.getString("user_id"), data.getString("employee_id")));
                userInfo.setOpenId(data.getString("open_id"));
                userInfo.setUnionId(data.getString("union_id"));
                userInfo.setName(firstNonEmpty(data.getString("name"), data.getString("en_name")));
                userInfo.setEmail(firstNonEmpty(data.getString("email"), data.getString("enterprise_email")));
                userInfo.setMobile(data.getString("mobile"));
                userInfo.setAvatar(firstNonEmpty(data.getString("avatar_url"), data.getString("avatar_thumb")));

                // 兼容嵌套 user_info
                JSONObject nested = data.getJSONObject("user_info");
                if (nested != null) {
                    if (!StringUtils.hasText(userInfo.getName())) {
                        userInfo.setName(nested.getString("name"));
                    }
                    if (!StringUtils.hasText(userInfo.getEmail())) {
                        userInfo.setEmail(nested.getString("email"));
                    }
                    if (!StringUtils.hasText(userInfo.getMobile())) {
                        userInfo.setMobile(nested.getString("mobile"));
                    }
                    if (!StringUtils.hasText(userInfo.getAvatar())) {
                        userInfo.setAvatar(nested.getString("avatar_url"));
                    }
                }
                if (!StringUtils.hasText(userInfo.getUserId())
                        && !StringUtils.hasText(userInfo.getOpenId())
                        && !StringUtils.hasText(userInfo.getUnionId())) {
                    log.warn("飞书用户信息缺少可用 ID: {}", raw);
                    return null;
                }
                return userInfo;
            }
        } catch (Exception e) {
            log.error("获取飞书用户信息异常", e);
            return null;
        }
    }

    private static String firstNonEmpty(String a, String b) {
        if (StringUtils.hasText(a)) {
            return a.trim();
        }
        if (StringUtils.hasText(b)) {
            return b.trim();
        }
        return null;
    }

    public static class FeishuUserInfo {
        private String userId;
        private String openId;
        private String unionId;
        private String name;
        private String email;
        private String mobile;
        private String avatar;

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
