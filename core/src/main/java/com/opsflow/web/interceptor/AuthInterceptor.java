package com.opsflow.web.interceptor;

import com.opsflow.service.PermissionService;
import com.opsflow.web.controller.AuthController;
import com.opsflow.web.security.RequiresPermission;
import com.opsflow.web.security.SessionPermissionResolver;
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

    @Autowired
    private SessionPermissionResolver sessionPermissionResolver;

    @Autowired
    private com.opsflow.dao.mapper.UserMapper userMapper;

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
            path.startsWith("/api/system/version") ||
            path.startsWith("/api/auth/login") ||
            path.startsWith("/api/auth/user") ||
            path.startsWith("/api/auth/logout")) {
            return true;
        }

        if (path.startsWith("/api/")) {
            HttpSession session = request.getSession(false);
            if (session == null || session.getAttribute("authenticated") == null
                    || session.getAttribute("userId") == null) {
                writeJson(response, HttpServletResponse.SC_UNAUTHORIZED, "未登录，请先登录");
                return false;
            }
            if (!isAccountUsable(session.getAttribute("userId"))) {
                // 账号在登录之后被禁用或删除：旧会话立即作废，而不是等它自己过期
                session.invalidate();
                writeJson(response, HttpServletResponse.SC_UNAUTHORIZED, "账号已被禁用或删除，请重新登录");
                return false;
            }
            if (Boolean.TRUE.equals(session.getAttribute(AuthController.MUST_CHANGE_PASSWORD_KEY))
                    && !path.startsWith("/api/auth/")) {
                writeJson(response, HttpServletResponse.SC_FORBIDDEN, "请先修改初始密码");
                return false;
            }
            if (Boolean.TRUE.equals(session.getAttribute(com.opsflow.web.security.TwoFactorSession.MUST_ENROLL))
                    && !path.startsWith("/api/auth/")) {
                writeJson(response, HttpServletResponse.SC_FORBIDDEN, "请先绑定两步验证", "two_factor.must_enroll");
                return false;
            }
            if (!checkPermission(handler, session, response)) {
                return false;
            }
        }

        return true;
    }

    /** 每次请求按主键查一次用户状态（主键查询，开销可忽略），保证禁用/删除对已登录会话即时生效。 */
    private boolean isAccountUsable(Object userId) {
        if (!(userId instanceof Long)) {
            return false;
        }
        com.opsflow.dao.model.User user = userMapper.selectById((Long) userId);
        return user != null && (user.getStatus() == null || user.getStatus() == 1);
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

        List<String> permissions = sessionPermissionResolver.resolvePermissions(session);
        if (permissionService.hasAnyPermission(permissions, annotation.value())) {
            return true;
        }
        writeJson(response, HttpServletResponse.SC_FORBIDDEN, "无权限执行此操作");
        return false;
    }

    private void writeJson(HttpServletResponse response, int status, String message) throws Exception {
        writeJson(response, status, message, null);
    }

    /** message、code 都是本类里的常量，不含需要转义的字符 */
    private void writeJson(HttpServletResponse response, int status, String message, String code) throws Exception {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"error\":\"" + message + "\",\"message\":\"" + message + "\""
                + (code != null ? ",\"code\":\"" + code + "\"" : "") + "}");
    }
}
