package com.opsflow.spi;

import com.opsflow.dao.model.Env;
import com.opsflow.service.impl.PermissionServiceImpl;
import com.opsflow.web.security.SessionPermissionResolver;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Arrays;
import java.util.Collections;

/** 生产部署策略相关测试共用：模拟登录用户的权限、构造环境 */
public final class ProdDeployTestSupport {

    public static final SessionPermissionResolver RESOLVER = new SessionPermissionResolver();
    public static final PermissionServiceImpl PERMISSIONS = new PermissionServiceImpl();

    static {
        ReflectionTestUtils.setField(RESOLVER, "permissionService", PERMISSIONS);
    }

    private ProdDeployTestSupport() {
    }

    public static DefaultProdDeployPolicy communityPolicy() {
        return new DefaultProdDeployPolicy(RESOLVER, PERMISSIONS);
    }

    /** 当前线程模拟一个已登录、拥有给定权限码的用户 */
    public static void loginWith(String... permissions) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("userId", 1L);
        RESOLVER.bind(session, Collections.emptyList(), Arrays.asList(permissions));
        request.setSession(session);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    public static void logout() {
        RequestContextHolder.resetRequestAttributes();
    }

    public static Env env(long id, String name, String type) {
        Env env = new Env();
        env.setId(id);
        env.setName(name);
        env.setEnvType(type);
        env.setStatus(1);
        return env;
    }
}
