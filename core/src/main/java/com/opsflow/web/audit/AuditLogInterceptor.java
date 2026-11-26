package com.opsflow.web.audit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.opsflow.dao.model.AuditLog;
import com.opsflow.service.AuditLogService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 记录所有 /api 写操作的审计日志。需注册在认证拦截器之前，这样被拒绝的越权/未登录请求也会留痕。
 */
@Component
public class AuditLogInterceptor implements HandlerInterceptor {

    /** 业务异常处理器写入的失败原因 */
    public static final String ATTR_ERROR_MESSAGE = AuditLogInterceptor.class.getName() + ".error";
    /** 无登录态的接口（如 Webhook）可自行指定操作人 */
    public static final String ATTR_OPERATOR = AuditLogInterceptor.class.getName() + ".operator";

    private static final String ATTR_START = AuditLogInterceptor.class.getName() + ".start";
    private static final String ATTR_USER = AuditLogInterceptor.class.getName() + ".user";
    private static final String ATTR_USER_ID = AuditLogInterceptor.class.getName() + ".userId";

    private static final Pattern SENSITIVE_KEY = Pattern.compile(
            "(?i).*(password|passwd|pwd|secret|token|privatekey|private_key|kubeconfig|credential|cert|apikey|api_key).*");
    /** 两步验证相关：动态码、恢复码（含新生成的恢复码列表）、绑定地址，不论值的类型一律打码 */
    private static final Set<String> ALWAYS_MASKED = new HashSet<>(Arrays.asList(
            "recoverycode", "recoverycodes", "otpauthuri", "otpauth"));
    /** 登录相关接口里的 code 是动态码或授权码；其他模块的 code 是业务编码（如角色编码），照常记录 */
    private static final Set<String> AUTH_MASKED = new HashSet<>(Arrays.asList("code", "authcode"));
    private static final String AUTH_PREFIX = "/api/auth/";
    private static final int SUMMARY_MAX = 2000;
    private static final int ERROR_MAX = 500;

    @Autowired
    private AuditLogService auditLogService;

    @Autowired
    private ObjectMapper objectMapper;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!AuditActionResolver.shouldAudit(request.getMethod(), request.getRequestURI())) {
            return true;
        }
        request.setAttribute(ATTR_START, System.currentTimeMillis());
        HttpSession session = request.getSession(false);
        if (session != null) {
            request.setAttribute(ATTR_USER, session.getAttribute("user"));
            request.setAttribute(ATTR_USER_ID, session.getAttribute("userId"));
        }
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        Object start = request.getAttribute(ATTR_START);
        if (!(start instanceof Long)) {
            return;
        }
        String path = request.getRequestURI();
        String method = request.getMethod();
        int status = response.getStatus();
        Object body = request.getAttribute(AuditRequestBodyAdvice.ATTR_BODY);

        AuditLog log = new AuditLog();
        fillOperator(log, request, body);
        log.setModule(AuditActionResolver.moduleName(path));
        log.setAction(AuditActionResolver.actionName(method, path, configTypeOf(body)));
        log.setHttpMethod(method);
        log.setPath(truncate(path, 255));
        log.setTargetId(resolveTargetId(request));
        log.setRequestSummary(AuditActionResolver.hideBody(path) ? null : summarize(body, path.startsWith(AUTH_PREFIX)));
        boolean success = ex == null && status < 400
                && !Boolean.TRUE.equals(request.getAttribute(AuditResponseBodyAdvice.ATTR_FAILED));
        log.setSuccess(success ? 1 : 0);
        log.setStatusCode(status);
        if (!success) {
            Object error = request.getAttribute(ATTR_ERROR_MESSAGE);
            String message = error != null ? String.valueOf(error) : (ex != null ? ex.getMessage() : defaultError(status));
            log.setErrorMessage(truncate(message, ERROR_MAX));
        }
        log.setClientIp(resolveClientIp(request));
        log.setDurationMs(System.currentTimeMillis() - (Long) start);
        auditLogService.record(log);
    }

    private void fillOperator(AuditLog log, HttpServletRequest request, Object body) {
        Object user = request.getAttribute(ATTR_USER);
        Object userId = request.getAttribute(ATTR_USER_ID);
        if (user == null) {
            // 登录请求：会话在处理过程中才建立
            HttpSession session = request.getSession(false);
            if (session != null) {
                user = session.getAttribute("user");
                userId = session.getAttribute("userId");
            }
        }
        if (user == null) {
            user = request.getAttribute(ATTR_OPERATOR);
        }
        if (user == null && body != null && !(body instanceof String)) {
            JsonNode node = objectMapper.valueToTree(body);
            if (node.hasNonNull("username")) {
                user = node.get("username").asText();
            }
        }
        log.setUsername(user != null ? truncate(String.valueOf(user), 100) : null);
        if (userId instanceof Number) {
            log.setUserId(((Number) userId).longValue());
        }
    }

    @SuppressWarnings("unchecked")
    private String resolveTargetId(HttpServletRequest request) {
        Object vars = request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        if (!(vars instanceof Map) || ((Map<String, String>) vars).isEmpty()) {
            return null;
        }
        return truncate(String.join(",", ((Map<String, String>) vars).values()), 100);
    }

    /** 请求体里的 configType（通用配置接口按它区分动作名） */
    private String configTypeOf(Object body) {
        if (body == null || body instanceof String) {
            return null;
        }
        try {
            JsonNode node = objectMapper.valueToTree(body);
            return node.hasNonNull("configType") ? node.get("configType").asText() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private String summarize(Object body, boolean authPath) {
        if (body == null) {
            return null;
        }
        try {
            JsonNode node = objectMapper.valueToTree(body);
            mask(node, authPath);
            return truncate(objectMapper.writeValueAsString(node), SUMMARY_MAX);
        } catch (Exception e) {
            return null;
        }
    }

    private void mask(JsonNode node, boolean authPath) {
        if (node == null) {
            return;
        }
        if (node.isObject()) {
            ObjectNode obj = (ObjectNode) node;
            Iterator<Map.Entry<String, JsonNode>> it = obj.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> entry = it.next();
                String lower = entry.getKey().toLowerCase();
                if (entry.getValue().isNull()) {
                    it.remove();
                } else if (ALWAYS_MASKED.contains(lower) || (authPath && AUTH_MASKED.contains(lower))) {
                    entry.setValue(obj.textNode("******"));
                } else if (SENSITIVE_KEY.matcher(entry.getKey()).matches()
                        && !entry.getValue().isContainerNode() && !entry.getKey().toLowerCase().endsWith("id")) {
                    entry.setValue(obj.textNode("******"));
                } else {
                    mask(entry.getValue(), authPath);
                }
            }
        } else if (node.isArray()) {
            node.forEach(child -> mask(child, authPath));
        }
    }

    private String resolveClientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (StringUtils.hasText(forwarded)) {
            return truncate(forwarded.split(",")[0].trim(), 64);
        }
        String realIp = request.getHeader("X-Real-IP");
        if (StringUtils.hasText(realIp)) {
            return truncate(realIp.trim(), 64);
        }
        return truncate(request.getRemoteAddr(), 64);
    }

    private String defaultError(int status) {
        if (status == HttpServletResponse.SC_UNAUTHORIZED) {
            return "未登录";
        }
        if (status == HttpServletResponse.SC_FORBIDDEN) {
            return "无权限";
        }
        return "HTTP " + status;
    }

    private static String truncate(String value, int max) {
        if (value == null || value.length() <= max) {
            return value;
        }
        return value.substring(0, max);
    }
}
