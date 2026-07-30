package com.opsflow.web.interceptor;

import com.opsflow.service.PermissionService;
import com.opsflow.web.security.RequiresPermission;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import java.util.Collections;
import java.util.List;

/**
 * 认证 + 权限拦截器
 */
@Component
public class AuthInterceptor implements HandlerInterceptor {

    /** 开发阶段可临时设为 true 跳过登录；生产/正式环境必须为 false */
    private static final boolean DEV_DISABLE_LOGIN_CHECK = false;

    @Value("${opsflow.dev-user-name:${user.name:admin}}")
    private String devUserName;

    @Autowired
    private PermissionService permissionService;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if (DEV_DISABLE_LOGIN_CHECK) {
            HttpSession session = request.getSession(true);
            session.setAttribute("user", devUserName);
            session.setAttribute("authenticated", true);
            session.setAttribute("roles", Collections.singletonList("ADMIN"));
            session.setAttribute("permissions", Collections.singletonList(PermissionService.WILDCARD));
            return true;
        }

        String path = request.getRequestURI();
        if (path.startsWith("/css/") ||
            path.startsWith("/js/") ||
            path.startsWith("/index.html") ||
            path.equals("/") ||
            path.equals("/health") ||
            path.startsWith("/api/health") ||
            path.startsWith("/api/auth/login") ||
            path.startsWith("/api/auth/user") ||
            path.startsWith("/api/auth/feishu/") ||
            path.startsWith("/api/auth/logout")) {
            return true;
        }

        if (path.startsWith("/api/")) {
            HttpSession session = request.getSession(false);
            if (session == null || session.getAttribute("authenticated") == null) {
                writeJson(response, HttpServletResponse.SC_UNAUTHORIZED, "未登录，请先登录");
                return false;
            }
            if (!checkPermission(handler, session, response)) {
                return false;
            }
        }

        return true;
    }

    private boolean checkPermission(Object handler, HttpSession session, HttpServletResponse response) throws Exception {
        if (!(handler instanceof HandlerMethod)) {
            return true;
        }
        HandlerMethod method = (HandlerMethod) handler;
        RequiresPermission annotation = method.getMethodAnnotation(RequiresPermission.class);
        if (annotation == null) {
            annotation = method.getBeanType().getAnnotation(RequiresPermission.class);
        }
        if (annotation == null || annotation.value().length == 0) {
            return true;
        }

        List<String> permissions = resolvePermissions(session);
        if (permissionService.hasAnyPermission(permissions, annotation.value())) {
            return true;
        }
        writeJson(response, HttpServletResponse.SC_FORBIDDEN, "无权限执行此操作");
        return false;
    }

    @SuppressWarnings("unchecked")
    private List<String> resolvePermissions(HttpSession session) {
        Object cached = session.getAttribute("permissions");
        if (cached instanceof List) {
            return (List<String>) cached;
        }
        Long userId = (Long) session.getAttribute("userId");
        if (userId != null) {
            List<String> codes = permissionService.listPermissionCodesByUserId(userId);
            session.setAttribute("permissions", codes);
            session.setAttribute("roles", permissionService.listRoleCodesByUserId(userId));
            return codes;
        }
        if ("admin".equals(session.getAttribute("user"))) {
            List<String> all = Collections.singletonList(PermissionService.WILDCARD);
            session.setAttribute("permissions", all);
            session.setAttribute("roles", Collections.singletonList("ADMIN"));
            return all;
        }
        return Collections.emptyList();
    }

    private void writeJson(HttpServletResponse response, int status, String message) throws Exception {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"error\":\"" + message + "\",\"message\":\"" + message + "\"}");
    }
}
