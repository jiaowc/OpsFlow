package com.opsflow.web.controller;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.opsflow.integration.feishu.FeishuCallbackSecurity;
import com.opsflow.integration.feishu.FeishuConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.util.StreamUtils;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * 飞书回调入口（当前仅保留兼容应答）。
 * 审批以系统内操作为准，飞书侧不支持通过卡片回调同意/拒绝。
 */
@Slf4j
@RestController
@RequestMapping("/api/feishu")
public class FeishuCallbackController {

    @Autowired
    private FeishuConfig feishuConfig;

    @PostMapping(value = "/callback", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> callback(HttpServletRequest request) throws Exception {
        byte[] rawBytes = StreamUtils.copyToByteArray(request.getInputStream());
        String rawBody = new String(rawBytes, StandardCharsets.UTF_8);
        JSONObject body = parseBody(rawBody);
        if (body == null) {
            return toast("error", "无效请求");
        }

        if ("url_verification".equalsIgnoreCase(body.getString("type"))) {
            Map<String, Object> resp = new HashMap<>();
            resp.put("challenge", body.getString("challenge"));
            return resp;
        }

        log.info("收到飞书回调（当前未启用飞书内审批）: {}", truncate(rawBody));
        return toast("info", "请前往 OpsFlow 系统完成审批");
    }

    private JSONObject parseBody(String rawBody) {
        if (!StringUtils.hasText(rawBody)) {
            return null;
        }
        try {
            JSONObject body = JSON.parseObject(rawBody);
            if (body != null && body.containsKey("encrypt") && StringUtils.hasText(feishuConfig.getEncryptKey())) {
                String plain = FeishuCallbackSecurity.decrypt(body.getString("encrypt"), feishuConfig.getEncryptKey());
                return JSON.parseObject(plain);
            }
            return body;
        } catch (Exception e) {
            log.error("解析飞书回调失败", e);
            return null;
        }
    }

    private Map<String, Object> toast(String type, String content) {
        Map<String, Object> toast = new HashMap<>();
        toast.put("type", type);
        toast.put("content", content);
        Map<String, Object> resp = new HashMap<>();
        resp.put("toast", toast);
        return resp;
    }

    private String truncate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() > 500 ? s.substring(0, 500) + "..." : s;
    }
}
