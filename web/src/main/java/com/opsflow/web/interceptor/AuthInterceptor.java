package com.opsflow.web.interceptor;

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
    
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
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



