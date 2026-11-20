package com.opsflow.web.interceptor;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * CSRF 防护：写接口（POST/PUT/DELETE/PATCH）校验请求来源。
 * <p>
 * 登录态放在 Session Cookie 里，浏览器会自动带上它，其他站点的页面可以借此替已登录用户发起写请求。
 * 浏览器对这类请求一定会带 Origin（或 Referer），所以这里要求它与当前站点一致；
 * 配合 Session Cookie 的 SameSite=Lax（见 application.yml）形成两层防护。
 * </p>
 * <p>
 * 没有 Origin 和 Referer 的请求（curl、服务端调用）不拦：它们不是浏览器发的，不存在借用 Cookie 的问题。
 * Git Webhook、飞书回调有自己的签名校验，不经过这里（见 WebMvcConfig）。
 * </p>
 */
@Slf4j
@Component
public class CsrfOriginInterceptor implements HandlerInterceptor {

    @Value("${opsflow.security.csrf-origin-check:true}")
    private boolean enabled;

    /** 额外允许的来源，逗号分隔，如 https://ops.example.com；反向代理改写了 Host 且没传 X-Forwarded-Host 时用 */
    @Value("${opsflow.security.allowed-origins:}")
    private String allowedOrigins;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if (!enabled || isSafeMethod(request.getMethod())) {
            return true;
        }
        String source = firstNonBlank(request.getHeader("Origin"), request.getHeader("Referer"));
        if (source == null) {
            return true;
        }
        List<String> hosts = new ArrayList<>();
        addHosts(hosts, request.getHeader("Host"));
        addHosts(hosts, request.getHeader("X-Forwarded-Host"));
        if (isAllowedSource(source, hosts, splitCsv(allowedOrigins))) {
            return true;
        }
        log.warn("拒绝跨站写请求: {} {} 来源={} Host={}", request.getMethod(), request.getRequestURI(),
                source, request.getHeader("Host"));
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("application/json;charset=UTF-8");
        String message = "请求来源与当前站点不一致，已拒绝";
        response.getWriter().write("{\"error\":\"" + message + "\",\"message\":\"" + message + "\"}");
        return false;
    }

    static boolean isSafeMethod(String method) {
        if (method == null) {
            return false;
        }
        String m = method.toUpperCase(Locale.ROOT);
        return "GET".equals(m) || "HEAD".equals(m) || "OPTIONS".equals(m) || "TRACE".equals(m);
    }

    /**
     * 来源（Origin 或 Referer）是否属于当前站点。
     *
     * @param source         Origin 或 Referer 的值
     * @param requestHosts   请求的 Host / X-Forwarded-Host，形如 host 或 host:port
     * @param allowedOrigins 配置里额外允许的来源，形如 https://ops.example.com
     */
    static boolean isAllowedSource(String source, List<String> requestHosts, List<String> allowedOrigins) {
        HostPort from = HostPort.ofUrl(source);
        if (from == null) {
            // "null"（沙箱 iframe、本地文件）或无法解析的值
            return false;
        }
        for (String allowed : allowedOrigins) {
            HostPort a = HostPort.ofUrl(allowed);
            if (a != null && a.matches(from)) {
                return true;
            }
        }
        for (String host : requestHosts) {
            HostPort h = HostPort.ofHostHeader(host);
            if (h != null && h.matches(from)) {
                return true;
            }
        }
        return false;
    }

    private static void addHosts(List<String> hosts, String header) {
        hosts.addAll(splitCsv(header));
    }

    private static List<String> splitCsv(String value) {
        List<String> result = new ArrayList<>();
        if (!StringUtils.hasText(value)) {
            return result;
        }
        Arrays.stream(value.split(",")).map(String::trim).filter(StringUtils::hasText).forEach(result::add);
        return result;
    }

    private static String firstNonBlank(String a, String b) {
        if (StringUtils.hasText(a)) {
            return a.trim();
        }
        return StringUtils.hasText(b) ? b.trim() : null;
    }

    /** 主机名 + 端口；端口为 -1 表示没写（Host 头里省略端口时无法知道协议，只比主机名） */
    static final class HostPort {
        private final String host;
        private final int port;

        private HostPort(String host, int port) {
            this.host = host.toLowerCase(Locale.ROOT);
            this.port = port;
        }

        static HostPort ofUrl(String url) {
            try {
                URI uri = URI.create(url.trim());
                if (uri.getHost() == null || uri.getScheme() == null) {
                    return null;
                }
                int port = uri.getPort();
                if (port < 0) {
                    port = "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
                }
                return new HostPort(uri.getHost(), port);
            } catch (Exception e) {
                return null;
            }
        }

        static HostPort ofHostHeader(String header) {
            try {
                URI uri = URI.create("http://" + header.trim());
                if (uri.getHost() == null) {
                    return null;
                }
                return new HostPort(uri.getHost(), uri.getPort());
            } catch (Exception e) {
                return null;
            }
        }

        boolean matches(HostPort other) {
            if (!host.equals(other.host)) {
                return false;
            }
            return port < 0 || other.port < 0 || port == other.port;
        }
    }
}
