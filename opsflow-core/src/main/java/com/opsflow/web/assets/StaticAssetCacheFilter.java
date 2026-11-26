package com.opsflow.web.assets;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpServletResponseWrapper;
import java.io.IOException;

/**
 * 带版本号的静态资源请求（{@code ?v=} 与当前文件内容一致）返回长期缓存头。
 * <p>版本号对不上（比如旧页面还引用着上个版本的地址）或没带版本号时不干预，仍是 no-cache，
 * 这样新内容不会被缓存在旧地址下。</p>
 */
@Component
public class StaticAssetCacheFilter extends OncePerRequestFilter {

    static final String IMMUTABLE = "max-age=31536000, immutable";

    private final StaticAssetVersions versions;

    @Autowired
    public StaticAssetCacheFilter(StaticAssetVersions versions) {
        this.versions = versions;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (isCurrentVersion(request)) {
            chain.doFilter(request, new ImmutableCacheResponse(response));
        } else {
            chain.doFilter(request, response);
        }
    }

    private boolean isCurrentVersion(HttpServletRequest request) {
        String method = request.getMethod();
        if (!"GET".equals(method) && !"HEAD".equals(method)) {
            return false;
        }
        String query = request.getQueryString();
        if (query == null || !query.startsWith(StaticAssetVersions.PARAM + "=")) {
            return false;
        }
        String path = request.getRequestURI().substring(request.getContextPath().length());
        String version = versions.version(path);
        return version != null && query.equals(StaticAssetVersions.PARAM + "=" + version);
    }

    /** 资源处理器写 Cache-Control 时换成长期缓存；404 等不写这个头的响应不受影响 */
    private static final class ImmutableCacheResponse extends HttpServletResponseWrapper {

        ImmutableCacheResponse(HttpServletResponse response) {
            super(response);
        }

        @Override
        public void setHeader(String name, String value) {
            super.setHeader(name, HttpHeaders.CACHE_CONTROL.equalsIgnoreCase(name) ? IMMUTABLE : value);
        }

        @Override
        public void addHeader(String name, String value) {
            if (HttpHeaders.CACHE_CONTROL.equalsIgnoreCase(name)) {
                super.setHeader(name, IMMUTABLE);
            } else {
                super.addHeader(name, value);
            }
        }
    }
}
