package com.opsflow.web.interceptor;

import com.opsflow.dao.mapper.UserRoleMapper;
import com.opsflow.service.impl.PermissionServiceImpl;
import com.opsflow.web.controller.AuthController;
import com.opsflow.web.security.RequiresPermission;
import com.opsflow.web.security.SessionPermissionResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.method.HandlerMethod;

import javax.servlet.http.HttpSession;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 认证与权限拦截：未登录 401、初始密码未改 403、缺少接口声明的权限 403。
 */
class AuthInterceptorTest {

    /** 拦截器只看注解，方法体不重要 */
    static class MethodLevel {
        @RequiresPermission({"deploy:create", "deploy:unlock"})
        public void guarded() {
        }

        public void open() {
        }
    }

    @RequiresPermission("user:manage")
    static class ClassLevel {
        public void inherits() {
        }

        @RequiresPermission("audit:view")
        public void overrides() {
        }
    }

    private AuthInterceptor interceptor;
    private UserRoleMapper userRoleMapper;

    @BeforeEach
    void setUp() {
        userRoleMapper = mock(UserRoleMapper.class);
        PermissionServiceImpl permissionService = new PermissionServiceImpl();
        ReflectionTestUtils.setField(permissionService, "userRoleMapper", userRoleMapper);
        SessionPermissionResolver resolver = new SessionPermissionResolver();
        ReflectionTestUtils.setField(resolver, "permissionService", permissionService);
        interceptor = new AuthInterceptor();
        ReflectionTestUtils.setField(interceptor, "permissionService", permissionService);
        ReflectionTestUtils.setField(interceptor, "sessionPermissionResolver", resolver);
    }

    private static HandlerMethod handler(Class<?> type, String method) throws Exception {
        return new HandlerMethod(type.getDeclaredConstructor().newInstance(), type.getDeclaredMethod(method));
    }

    private MockHttpServletRequest loggedIn(String path, String... permissions) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
        HttpSession session = request.getSession(true);
        session.setAttribute("authenticated", true);
        session.setAttribute("userId", 7L);
        when(userRoleMapper.selectPermissionCodesByUserId(7L)).thenReturn(Arrays.asList(permissions));
        return request;
    }

    private int status(MockHttpServletRequest request, Object handler) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        boolean allowed = interceptor.preHandle(request, response, handler);
        assertEquals(allowed, response.getStatus() == 200, "放行与否应和状态码一致");
        return response.getStatus();
    }

    // ---------- 未登录 ----------

    @Test
    void apiWithoutSessionIs401() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/task/list");
        MockHttpServletResponse response = new MockHttpServletResponse();
        assertFalse(interceptor.preHandle(request, response, handler(MethodLevel.class, "open")));
        assertEquals(401, response.getStatus());
        assertTrue(response.getContentAsString().contains("未登录"));
        assertNull(request.getSession(false), "未登录的请求不应该顺手创建会话");
    }

    @Test
    void halfInitializedSessionIsStill401() throws Exception {
        MockHttpServletRequest noUserId = new MockHttpServletRequest("GET", "/api/task/list");
        noUserId.getSession(true).setAttribute("authenticated", true);
        assertEquals(401, status(noUserId, handler(MethodLevel.class, "open")));

        MockHttpServletRequest notAuthenticated = new MockHttpServletRequest("GET", "/api/task/list");
        notAuthenticated.getSession(true).setAttribute("userId", 7L);
        assertEquals(401, status(notAuthenticated, handler(MethodLevel.class, "open")));
    }

    @Test
    void publicPathsNeedNoLogin() throws Exception {
        for (String path : new String[]{"/", "/index.html", "/css/style.css", "/js/login.js", "/health",
                "/api/health", "/api/system/version", "/api/branding/public", "/api/branding/logo",
                "/api/auth/login", "/api/auth/user", "/api/auth/logout", "/api/auth/feishu/login"}) {
            assertEquals(200, status(new MockHttpServletRequest("GET", path), handler(MethodLevel.class, "guarded")), path);
        }
    }

    @Test
    void pageRoutesAreNotBlockedByTheInterceptor() throws Exception {
        // /pipeline、/users 这类页面路由只返回静态页面，数据都走 /api/
        assertEquals(200, status(new MockHttpServletRequest("GET", "/pipeline"), handler(MethodLevel.class, "open")));
    }

    // ---------- 已登录 ----------

    @Test
    void endpointWithoutAnnotationOnlyNeedsLogin() throws Exception {
        assertEquals(200, status(loggedIn("/api/inbox/list"), handler(MethodLevel.class, "open")));
    }

    @Test
    void anyDeclaredPermissionIsEnough() throws Exception {
        assertEquals(200, status(loggedIn("/api/task/1/unlock", "deploy:unlock"), handler(MethodLevel.class, "guarded")));
        assertEquals(200, status(loggedIn("/api/task/1/unlock", "*"), handler(MethodLevel.class, "guarded")));
    }

    @Test
    void missingPermissionIs403() throws Exception {
        MockHttpServletRequest request = loggedIn("/api/task/1/unlock", "deploy:view");
        MockHttpServletResponse response = new MockHttpServletResponse();
        assertFalse(interceptor.preHandle(request, response, handler(MethodLevel.class, "guarded")));
        assertEquals(403, response.getStatus());
        assertTrue(response.getContentAsString().contains("无权限"));
    }

    @Test
    void classLevelAnnotationAppliesUnlessMethodOverrides() throws Exception {
        assertEquals(200, status(loggedIn("/api/users", "user:manage"), handler(ClassLevel.class, "inherits")));
        assertEquals(403, status(loggedIn("/api/users", "audit:view"), handler(ClassLevel.class, "inherits")));
        assertEquals(200, status(loggedIn("/api/users/audit", "audit:view"), handler(ClassLevel.class, "overrides")));
        assertEquals(403, status(loggedIn("/api/users/audit", "user:manage"), handler(ClassLevel.class, "overrides")),
                "方法上的声明覆盖类上的，而不是两者取并集");
    }

    @Test
    void mustChangePasswordBlocksEverythingExceptAuthEndpoints() throws Exception {
        MockHttpServletRequest blocked = loggedIn("/api/task/list", "*");
        blocked.getSession().setAttribute(AuthController.MUST_CHANGE_PASSWORD_KEY, Boolean.TRUE);
        MockHttpServletResponse response = new MockHttpServletResponse();
        assertFalse(interceptor.preHandle(blocked, response, handler(MethodLevel.class, "open")));
        assertEquals(403, response.getStatus());
        assertTrue(response.getContentAsString().contains("请先修改初始密码"));

        MockHttpServletRequest changing = loggedIn("/api/auth/change-password", "*");
        changing.getSession().setAttribute(AuthController.MUST_CHANGE_PASSWORD_KEY, Boolean.TRUE);
        assertEquals(200, status(changing, handler(MethodLevel.class, "open")));
    }

    @Test
    void permissionsAreReloadedSoRevocationTakesEffect() throws Exception {
        // 会话里缓存的权限过期后重新从库里加载，被收回的权限随之失效
        MockHttpServletRequest request = loggedIn("/api/task/1/unlock", "deploy:unlock");
        assertEquals(200, status(request, handler(MethodLevel.class, "guarded")));

        when(userRoleMapper.selectPermissionCodesByUserId(7L)).thenReturn(Arrays.asList("deploy:view"));
        assertEquals(200, status(request, handler(MethodLevel.class, "guarded")), "一分钟内仍用缓存");

        request.getSession().setAttribute("permissionsLoadedAt", System.currentTimeMillis() - 61_000L);
        assertEquals(403, status(request, handler(MethodLevel.class, "guarded")));
    }

    @Test
    void nonControllerHandlersSkipPermissionCheck() throws Exception {
        assertEquals(200, status(loggedIn("/api/files/x"), new Object()));
    }
}
