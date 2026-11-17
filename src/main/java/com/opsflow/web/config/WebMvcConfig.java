package com.opsflow.web.config;

import com.opsflow.web.audit.AuditLogInterceptor;
import com.opsflow.web.interceptor.AuthInterceptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web MVC 配置
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {
    
    @Autowired
    private AuthInterceptor authInterceptor;

    @Autowired
    private AuditLogInterceptor auditLogInterceptor;
    
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 审计必须在认证之前，被拒绝的请求也要留痕
        registry.addInterceptor(auditLogInterceptor).addPathPatterns("/api/**");
        registry.addInterceptor(authInterceptor)
                .addPathPatterns("/**")
                .excludePathPatterns(
                    "/",
                    "/index.html",
                    "/css/**",
                    "/js/**",
                    "/health",
                    "/api/health",
                    "/api/auth/login",
                    "/api/auth/logout",
                    "/api/auth/user",
                    "/api/branding/public",
                    "/api/branding/logo",
                    "/api/feishu/callback",
                    "/api/webhook/git"
                );
    }
    
    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // no-cache：浏览器仍缓存，但每次使用前用 Last-Modified 向服务端校验（未变返回 304），
        // 避免 JS/CSS 改了却忘记升 ?v= 时浏览器继续用旧文件
        registry.addResourceHandler("/**")
                .addResourceLocations("classpath:/static/")
                .setCacheControl(CacheControl.noCache());
    }
}



