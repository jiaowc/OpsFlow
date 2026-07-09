package com.opsflow.web.interceptor;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

/**
 * 认证拦截器
 */
@Component
public class AuthInterceptor implements HandlerInterceptor {

    // =========================
    // 开发临时代码：关闭登录校验
    // =========================
    // 目的：开发/调试阶段重启后无需手动登录。
    // 你后续“开发好后恢复”，只需要把这个开关改回 false 即可。
    private static final boolean DEV_DISABLE_LOGIN_CHECK = true;

    @Value("${opsflow.dev-user-name:${user.name:admin}}")
    private String devUserName;
    
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if (DEV_DISABLE_LOGIN_CHECK) {
            // 为了兼容部分 Controller 依赖 session.user/session.authenticated 的逻辑，
            // 这里直接设置为已认证状态（无需前端输入账号密码）。
            HttpSession session = request.getSession(true);
            session.setAttribute("user", devUserName);
            session.setAttribute("authenticated", true);
            return true;
        }

        // 排除静态资源和登录接口
        String path = request.getRequestURI();
        if (path.startsWith("/css/") || 
            path.startsWith("/js/") || 
            path.startsWith("/index.html") ||
            path.equals("/") ||
            path.startsWith("/api/auth/login")) {
            return true;
        }
        
        // 检查API接口的认证（除了登录接口）
        if (path.startsWith("/api/")) {
            HttpSession session = request.getSession(false);
            if (session == null || session.getAttribute("authenticated") == null) {
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                response.setContentType("application/json;charset=UTF-8");
                response.getWriter().write("{\"error\":\"未登录，请先登录\"}");
                return false;
            }
        }
        
        return true;
    }
}



