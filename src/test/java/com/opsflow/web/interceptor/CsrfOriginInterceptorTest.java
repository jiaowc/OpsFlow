package com.opsflow.web.interceptor;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CsrfOriginInterceptorTest {

    private static final List<String> NONE = Collections.emptyList();

    private static boolean allowed(String source, String... hosts) {
        return CsrfOriginInterceptor.isAllowedSource(source, Arrays.asList(hosts), NONE);
    }

    @Test
    void sameOriginIsAllowed() {
        assertTrue(allowed("http://localhost:8080", "localhost:8080"));
        assertTrue(allowed("https://ops.example.com", "ops.example.com"));
        assertTrue(allowed("https://OPS.example.com", "ops.EXAMPLE.com:443"));
        // Referer 带路径
        assertTrue(allowed("https://ops.example.com/dashboard.html?x=1", "ops.example.com"));
    }

    @Test
    void otherSiteIsRejected() {
        assertFalse(allowed("https://evil.example.net", "ops.example.com"));
        assertFalse(allowed("https://ops.example.com.evil.net", "ops.example.com"));
        assertFalse(allowed("http://localhost:3000", "localhost:8080"));
    }

    @Test
    void nullOrMalformedOriginIsRejected() {
        assertFalse(allowed("null", "ops.example.com"));
        assertFalse(allowed("not a url", "ops.example.com"));
        assertFalse(allowed("//ops.example.com", "ops.example.com"));
    }

    @Test
    void forwardedHostAndConfiguredOriginsAreAccepted() {
        // 反向代理把 Host 改成了后端地址，但带了 X-Forwarded-Host
        assertTrue(allowed("https://ops.example.com", "127.0.0.1:8080", "ops.example.com"));
        assertTrue(CsrfOriginInterceptor.isAllowedSource("https://ops.example.com",
                Collections.singletonList("127.0.0.1:8080"), Collections.singletonList("https://ops.example.com")));
        assertFalse(CsrfOriginInterceptor.isAllowedSource("http://ops.example.com:8443",
                Collections.singletonList("127.0.0.1:8080"), Collections.singletonList("https://ops.example.com")));
    }

    private static CsrfOriginInterceptor interceptor(boolean enabled) {
        CsrfOriginInterceptor interceptor = new CsrfOriginInterceptor();
        ReflectionTestUtils.setField(interceptor, "enabled", enabled);
        ReflectionTestUtils.setField(interceptor, "allowedOrigins", "");
        return interceptor;
    }

    private static MockHttpServletRequest request(String method, String origin) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, "/api/service/1");
        request.addHeader("Host", "ops.example.com");
        if (origin != null) {
            request.addHeader("Origin", origin);
        }
        return request;
    }

    @Test
    void crossSiteWriteIsBlockedWith403() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        assertFalse(interceptor(true).preHandle(request("DELETE", "https://evil.example.net"), response, new Object()));
        assertEquals(403, response.getStatus());
    }

    @Test
    void readsAndNonBrowserCallsPass() throws Exception {
        CsrfOriginInterceptor interceptor = interceptor(true);
        assertTrue(interceptor.preHandle(request("GET", "https://evil.example.net"), new MockHttpServletResponse(), new Object()));
        assertTrue(interceptor.preHandle(request("POST", null), new MockHttpServletResponse(), new Object()));
        assertTrue(interceptor.preHandle(request("POST", "https://ops.example.com"), new MockHttpServletResponse(), new Object()));
    }

    @Test
    void refererIsUsedWhenOriginIsMissing() throws Exception {
        MockHttpServletRequest request = request("POST", null);
        request.addHeader("Referer", "https://evil.example.net/page");
        assertFalse(interceptor(true).preHandle(request, new MockHttpServletResponse(), new Object()));
    }

    @Test
    void checkCanBeDisabled() throws Exception {
        assertTrue(interceptor(false).preHandle(request("POST", "https://evil.example.net"), new MockHttpServletResponse(), new Object()));
    }
}
