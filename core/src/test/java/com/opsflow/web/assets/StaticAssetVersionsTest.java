package com.opsflow.web.assets;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.util.StreamUtils;
import org.springframework.web.servlet.resource.ResourceResolverChain;
import org.springframework.web.servlet.resource.ResourceTransformerChain;

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StaticAssetVersionsTest {

    private final StaticAssetVersions versions = new StaticAssetVersions();

    @Test
    void 存在的资源有稳定的版本号() {
        String version = versions.version("/js/core.js");
        assertNotNull(version);
        assertTrue(version.matches("[0-9a-f]{10}"), version);
        assertEquals(version, versions.version("/js/core.js"));
        assertEquals("/js/core.js?v=" + version, versions.versioned("/js/core.js"));
    }

    @Test
    void 不存在或不合法的路径没有版本号() {
        assertNull(versions.version("/js/not-exists.js"));
        assertNull(versions.version("/js/../../application.yml"));
        assertNull(versions.version("/js"));
        assertNull(versions.version("/api/ui/extensions"));
        assertNull(versions.version(null));
        assertEquals("/js/not-exists.js", versions.versioned("/js/not-exists.js"));
    }

    @Test
    void 只改写页面里存在的本地脚本和样式() {
        String html = "<link rel=\"stylesheet\" href=\"/css/style.css\">\n"
                + "<script src=\"/js/core.js\"></script>\n"
                + "<script src=\"/js/not-exists.js\"></script>\n"
                + "<script src=\"https://example.com/a.js\"></script>\n"
                + "<script src=\"/js/utils.js?x=1\"></script>\n"
                + "<a href=\"/pipeline\" data-section=\"pipeline\">x</a>";
        String out = versions.rewriteHtml(html);
        assertTrue(out.contains("href=\"/css/style.css?v=" + versions.version("/css/style.css") + "\""), out);
        assertTrue(out.contains("src=\"/js/core.js?v=" + versions.version("/js/core.js") + "\""), out);
        assertTrue(out.contains("src=\"/js/not-exists.js\""), out);
        assertTrue(out.contains("src=\"https://example.com/a.js\""), out);
        assertTrue(out.contains("src=\"/js/utils.js?x=1\""), out);
        assertTrue(out.contains("href=\"/pipeline\""), out);
    }

    @Test
    void 页面经过转换后引用都带版本号且带版本号请求的片段不改写() throws Exception {
        HtmlAssetVersionTransformer transformer = new HtmlAssetVersionTransformer(versions);
        Resource page = new ClassPathResource("static/dashboard.html");
        ResourceTransformerChain passThrough = new ResourceTransformerChain() {
            @Override
            public ResourceResolverChain getResolverChain() {
                return null;
            }

            @Override
            public Resource transform(HttpServletRequest request, Resource resource) {
                return resource;
            }
        };

        Resource transformed = transformer.transform(new MockHttpServletRequest("GET", "/dashboard.html"), page, passThrough);
        String html = StreamUtils.copyToString(transformed.getInputStream(), StandardCharsets.UTF_8);
        assertTrue(html.contains("/js/core.js?v=" + versions.version("/js/core.js")), "core.js 未带版本号");
        assertFalse(html.matches("(?s).*src=\"/js/[^\"?]+\\.js\".*"), "还有没带版本号的脚本");
        assertEquals("dashboard.html", transformed.getFilename());
        assertTrue(transformed.lastModified() < 0, "页面不能带修改时间，否则回滚到旧包时会被 304 挡住");

        MockHttpServletRequest versionedRequest = new MockHttpServletRequest("GET", "/dashboard.html");
        versionedRequest.setQueryString("v=abc");
        assertSame(page, transformer.transform(versionedRequest, page, passThrough));

        Resource script = new ClassPathResource("static/js/core.js");
        assertSame(script, transformer.transform(new MockHttpServletRequest("GET", "/js/core.js"), script, passThrough));
    }

    @Test
    void 版本号正确的请求才改成长期缓存() throws Exception {
        String version = versions.version("/js/core.js");
        assertEquals(StaticAssetCacheFilter.IMMUTABLE, cacheControl("GET", "/js/core.js", "v=" + version));
        assertEquals("no-cache", cacheControl("GET", "/js/core.js", null));
        assertEquals("no-cache", cacheControl("GET", "/js/core.js", "v=0000000000"));
        assertEquals("no-cache", cacheControl("GET", "/js/core.js", "v=" + version + "&x=1"));
        assertEquals("no-cache", cacheControl("POST", "/js/core.js", "v=" + version));
        assertEquals("no-cache", cacheControl("GET", "/api/ui/extensions", "v=" + version));
    }

    /** 模拟资源处理器：总是写 Cache-Control: no-cache，看过滤器最后放出去的是什么 */
    private String cacheControl(String method, String uri, String query) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
        request.setQueryString(query);
        MockHttpServletResponse response = new MockHttpServletResponse();
        HttpServlet handler = new HttpServlet() {
            @Override
            protected void service(HttpServletRequest req, HttpServletResponse resp) {
                resp.setHeader("Cache-Control", "no-cache");
                resp.setHeader("X-Other", "kept");
            }
        };
        new StaticAssetCacheFilter(versions).doFilter(request, response, new MockFilterChain(handler));
        assertEquals("kept", response.getHeader("X-Other"));
        return response.getHeader("Cache-Control");
    }
}
