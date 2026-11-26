package com.opsflow.integration.git;

import com.opsflow.dao.mapper.ComponentMapper;
import com.opsflow.dao.model.Component;
import com.opsflow.integration.credential.ComponentAuthResolver;
import com.opsflow.integration.credential.ResolvedAuth;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 自建 GitLab（http、非默认端口）拉分支/Tag：协议和端口取自组件配置，项目路径只编码一次。
 */
class GitRefServiceTest {

    private HttpServer server;
    private final List<String> requestedUris = new CopyOnWriteArrayList<>();
    private final List<String> tokens = new CopyOnWriteArrayList<>();
    private GitRefService service;
    private String base;

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            // getRawPath 保留原始编码，才能看出 %2F 有没有被二次编码成 %252F
            requestedUris.add(exchange.getRequestURI().getRawPath());
            String token = exchange.getRequestHeaders().getFirst("PRIVATE-TOKEN");
            tokens.add(token == null ? "" : token);
            String raw = exchange.getRequestURI().getRawPath();
            byte[] body;
            int status = 200;
            if (raw.endsWith("/projects/qa%2Fdemo/repository/branches")) {
                body = "[{\"name\":\"main\"},{\"name\":\"feature/qa-1\"}]".getBytes(StandardCharsets.UTF_8);
            } else if (raw.endsWith("/projects/qa%2Fdemo/repository/tags")) {
                body = "[{\"name\":\"v1.0.0\"}]".getBytes(StandardCharsets.UTF_8);
            } else {
                status = 404;
                body = "{\"message\":\"404 Project Not Found\"}".getBytes(StandardCharsets.UTF_8);
            }
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();

        Component gitlab = new Component();
        gitlab.setId(1L);
        gitlab.setType("gitlab");
        gitlab.setStatus(1);
        gitlab.setUrl(base + "/");

        ComponentMapper componentMapper = mock(ComponentMapper.class);
        when(componentMapper.selectById(1L)).thenReturn(gitlab);

        ResolvedAuth auth = new ResolvedAuth();
        auth.setAuthType("token");
        auth.setAuthConfig(Collections.singletonMap("token", "t0ken"));
        ComponentAuthResolver resolver = mock(ComponentAuthResolver.class);
        when(resolver.resolve(any())).thenReturn(auth);

        service = new GitRefService();
        ReflectionTestUtils.setField(service, "componentMapper", componentMapper);
        ReflectionTestUtils.setField(service, "componentAuthResolver", resolver);
        ReflectionTestUtils.setField(service, "gitCredentialService", mock(GitCredentialService.class));
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void http带端口的自建GitLab能拉到分支() {
        List<String> refs = service.listRefs(base + "/qa/demo.git", "branch", 1L);

        assertEquals(Arrays.asList("main", "feature/qa-1"), refs);
        assertEquals(Collections.singletonList("/api/v4/projects/qa%2Fdemo/repository/branches"), requestedUris,
                "项目路径只能编码一次，不能出现 %252F");
        assertEquals(Collections.singletonList("t0ken"), tokens);
    }

    @Test
    void Tag模式返回的是Tag而不是分支() {
        assertEquals(Collections.singletonList("v1.0.0"), service.listRefs(base + "/qa/demo", "tag", 1L));
    }

    @Test
    void 组件地址与仓库地址按主机加端口比较() {
        assertTrue(GitRefService.sameAuthority(URI.create("http://192.168.88.4:18190"), "192.168.88.4:18190"));
        assertTrue(GitRefService.sameAuthority(URI.create("https://git.example.com"), "git.example.com"));
        assertTrue(GitRefService.sameAuthority(URI.create("https://git.example.com:443"), "Git.Example.com"));
        assertTrue(GitRefService.sameAuthority(URI.create("https://git.example.com"), "oauth2@git.example.com"));
        assertFalse(GitRefService.sameAuthority(URI.create("http://192.168.88.4:18190"), "192.168.88.4:8080"));
        assertFalse(GitRefService.sameAuthority(URI.create("http://192.168.88.4:18190"), "192.168.88.4"));
        assertFalse(GitRefService.sameAuthority(URI.create("https://git.example.com"), "other.example.com"));
    }

    @Test
    void GitLab部署在子路径时去掉路径前缀() {
        assertEquals("qa/demo", GitRefService.stripContextPath("/gitlab", "gitlab/qa/demo"));
        assertEquals("qa/demo", GitRefService.stripContextPath("", "qa/demo"));
        assertEquals("gitlabs/demo", GitRefService.stripContextPath("/gitlab", "gitlabs/demo"));
    }
}
