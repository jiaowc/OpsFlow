package com.opsflow.web.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opsflow.common.util.GitRepoKey;
import com.opsflow.service.PipelineRunService;
import com.opsflow.web.audit.AuditLogInterceptor;
import com.opsflow.web.security.RequiresPermission;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/**
 * Git 推送 Webhook：GitLab / GitHub / Gitee 推送分支后，自动构建开启了「推送自动构建」的流水线任务。
 * <p>该接口不走登录态，使用 opsflow.webhook.secret 校验：
 * GitLab「Secret token」、Gitee「WebHook 密码」、GitHub「Secret」（HMAC-SHA256 签名）。</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/webhook")
public class GitWebhookController {

    private static final String ZERO_SHA = "0000000000000000000000000000000000000000";
    private static final String BRANCH_REF_PREFIX = "refs/heads/";

    @Autowired
    private PipelineRunService pipelineRunService;

    @Value("${opsflow.webhook.secret:}")
    private String secret;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @PostMapping("/git")
    public ResponseEntity<Map<String, Object>> onGitPush(@RequestBody(required = false) String body,
                                                         HttpServletRequest request) {
        if (secret == null || secret.trim().isEmpty()) {
            return reply(HttpStatus.FORBIDDEN, false, "Webhook 未启用：未配置 opsflow.webhook.secret");
        }
        if (!verify(request, body == null ? "" : body)) {
            return reply(HttpStatus.UNAUTHORIZED, false, "Webhook 校验失败");
        }

        String event = firstNonBlank(request.getHeader("X-Gitlab-Event"),
                request.getHeader("X-GitHub-Event"), request.getHeader("X-Gitee-Event"));
        if ("ping".equalsIgnoreCase(event)) {
            return reply(HttpStatus.OK, true, "pong");
        }

        JsonNode payload;
        try {
            payload = objectMapper.readTree(body == null ? "{}" : body);
        } catch (Exception e) {
            return reply(HttpStatus.BAD_REQUEST, false, "请求体不是合法 JSON");
        }

        String ref = text(payload, "ref");
        if (ref == null || !ref.startsWith(BRANCH_REF_PREFIX)) {
            return reply(HttpStatus.OK, true, "忽略：非分支推送事件");
        }
        if (ZERO_SHA.equals(text(payload, "after")) || payload.path("deleted").asBoolean(false)) {
            return reply(HttpStatus.OK, true, "忽略：分支删除");
        }
        String branch = ref.substring(BRANCH_REF_PREFIX.length());

        Set<String> repoKeys = extractRepoKeys(payload);
        if (repoKeys.isEmpty()) {
            return reply(HttpStatus.BAD_REQUEST, false, "无法从请求中识别仓库地址");
        }
        String pusher = firstNonBlank(text(payload, "user_username"), text(payload, "user_name"),
                text(payload.path("pusher"), "name"), text(payload.path("sender"), "login"));
        request.setAttribute(AuditLogInterceptor.ATTR_OPERATOR, "webhook" + (pusher != null ? "(" + pusher + ")" : ""));

        Map<String, Object> result = pipelineRunService.triggerByGitPush(repoKeys, branch, pusher);
        result.put("success", true);
        result.put("branch", branch);
        result.put("repositories", repoKeys);
        return ResponseEntity.ok(result);
    }

    /**
     * 供页面展示 Webhook 配置说明
     */
    @RequiresPermission("pipeline:view")
    @GetMapping("/info")
    public Map<String, Object> info() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("enabled", secret != null && !secret.trim().isEmpty());
        result.put("path", "/api/webhook/git");
        return result;
    }

    private boolean verify(HttpServletRequest request, String body) {
        String token = firstNonBlank(request.getHeader("X-Gitlab-Token"), request.getHeader("X-Gitee-Token"));
        if (token != null) {
            return constantTimeEquals(token, secret);
        }
        String signature = request.getHeader("X-Hub-Signature-256");
        if (signature != null && signature.startsWith("sha256=")) {
            return constantTimeEquals(signature.substring("sha256=".length()), hmacSha256Hex(body));
        }
        return false;
    }

    private String hmacSha256Hex(String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal(body.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            log.warn("计算 Webhook 签名失败: {}", e.getMessage());
            return "";
        }
    }

    private static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    private static Set<String> extractRepoKeys(JsonNode payload) {
        Set<String> keys = new LinkedHashSet<>();
        for (String section : new String[]{"project", "repository"}) {
            JsonNode node = payload.path(section);
            for (String field : new String[]{"path_with_namespace", "full_name", "git_http_url",
                    "git_ssh_url", "clone_url", "ssh_url", "http_url", "web_url", "html_url", "url"}) {
                String key = GitRepoKey.normalize(text(node, field));
                if (key != null) {
                    keys.add(key);
                }
            }
        }
        return keys;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value != null && value.isTextual() && !value.asText().trim().isEmpty() ? value.asText().trim() : null;
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.trim().isEmpty()) {
                return v.trim();
            }
        }
        return null;
    }

    private static ResponseEntity<Map<String, Object>> reply(HttpStatus status, boolean success, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", success);
        body.put("message", message);
        return ResponseEntity.status(status).body(body);
    }
}
