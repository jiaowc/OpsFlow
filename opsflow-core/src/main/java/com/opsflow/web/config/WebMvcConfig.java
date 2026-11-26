package com.opsflow.web.config;

import com.opsflow.spi.WebSecurityExtension;
import com.opsflow.web.assets.HtmlAssetVersionTransformer;
import com.opsflow.web.assets.StaticAssetVersions;
import com.opsflow.web.audit.AuditLogInterceptor;
import com.opsflow.web.interceptor.AuthInterceptor;
import com.opsflow.web.interceptor.CsrfOriginInterceptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Web MVC 配置
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {
    
    @Autowired
    private AuthInterceptor authInterceptor;

    @Autowired
    private AuditLogInterceptor auditLogInterceptor;

    @Autowired
    private CsrfOriginInterceptor csrfOriginInterceptor;

    @Autowired
    private StaticAssetVersions assetVersions;

    /** 扩展模块（如企业版）要放开的路径，社区版为空 */
    @Autowired(required = false)
    private List<WebSecurityExtension> securityExtensions = new ArrayList<>();
    
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 审计必须在认证之前，被拒绝的请求也要留痕
        registry.addInterceptor(auditLogInterceptor).addPathPatterns("/api/**");
        // 写接口的来源校验（CSRF）；Webhook 和扩展声明的回调由各自的签名校验负责
        List<String> csrfExempt = new ArrayList<>(Arrays.asList("/api/webhook/git"));
        securityExtensions.forEach(ext -> csrfExempt.addAll(ext.csrfExemptPaths()));
        registry.addInterceptor(csrfOriginInterceptor)
                .addPathPatterns("/api/**")
                .excludePathPatterns(csrfExempt);
        List<String> publicPaths = new ArrayList<>(Arrays.asList(
                "/",
                "/index.html",
                "/css/**",
                "/js/**",
                "/health",
                "/api/health",
                "/api/auth/login",
                "/api/auth/logout",
                "/api/auth/user",
                // 登录第二步：密码已验证、会话尚未绑定，校验由接口自己按 session 里的待验证状态完成
                "/api/auth/two-factor/verify",
                // 登录页加载扩展（如企业版的品牌、第三方登录按钮）用，只返回静态资源地址
                "/api/ui/login-extensions",
                "/api/webhook/git"));
        securityExtensions.forEach(ext -> publicPaths.addAll(ext.publicPaths()));
        registry.addInterceptor(authInterceptor)
                .addPathPatterns("/**")
                .excludePathPatterns(publicPaths);
    }
    
    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // 默认 no-cache：浏览器仍缓存，但每次使用前用 Last-Modified 向服务端校验（未变返回 304）。
        // 页面（html）每次返回完整内容，里面引用的脚本和样式由 HtmlAssetVersionTransformer 自动加上内容版本号（?v=），
        // 带正确版本号的请求由 StaticAssetCacheFilter 改成长期缓存；版本号按文件内容算，不需要手工维护
        registry.addResourceHandler("/**")
                .addResourceLocations("classpath:/static/")
                .setCacheControl(CacheControl.noCache())
                .resourceChain(false)
                .addTransformer(new HtmlAssetVersionTransformer(assetVersions));
    }
}



