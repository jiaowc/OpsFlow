package com.opsflow.web.config;

import com.opsflow.web.audit.AuditLogInterceptor;
import com.opsflow.web.interceptor.AuthInterceptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
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
        registry.addResourceHandler("/**")
                .addResourceLocations("classpath:/static/");
    }
}



