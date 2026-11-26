package com.opsflow.web.controller;

import com.opsflow.common.util.PasswordHasher;
import com.opsflow.dao.mapper.UserMapper;
import com.opsflow.dao.model.User;
import com.opsflow.integration.ldap.LdapLoginService;
import com.opsflow.service.PermissionService;
import com.opsflow.service.TwoFactorService.Policy;
import com.opsflow.service.impl.TwoFactorTestSupport;
import com.opsflow.web.security.LoginAttemptGuard;
import com.opsflow.web.security.LoginSessionBinder;
import com.opsflow.web.security.SessionPermissionResolver;
import com.opsflow.web.security.TwoFactorSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;

import javax.servlet.http.HttpSession;
import java.lang.reflect.Constructor;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 登录时的两步验证：三种策略 × 本地 / LDAP 登录、第二步的次数上限和超时、恢复码只能用一次、防重放，
 * 以及扩展的单点登录（直接走 LoginSessionBinder）不要求动态码。
 */
class AuthControllerTwoFactorTest {

    private static final String PASSWORD = "Kx7#pQ2!vLm9";

    private final TwoFactorTestSupport t = new TwoFactorTestSupport();
    private final UserMapper userMapper = t.userMapper;
    private final LdapLoginService ldap = mock(LdapLoginService.class);
    private final LoginAttemptGuard guard = new LoginAttemptGuard();
    private final LoginSessionBinder binder = new LoginSessionBinder();
    private final AuthController controller = new AuthController();

    private final User local = user(1L, "alice", "local");
    private final User ldapUser = user(2L, "bob", "ldap");

    @BeforeEach
    void setUp() throws Exception {
        PermissionService permissions = mock(PermissionService.class);
        when(permissions.listRoleCodesByUserId(anyLong())).thenReturn(Collections.singletonList("DEV"));
        when(permissions.listPermissionCodesByUserId(anyLong())).thenReturn(Collections.singletonList("pipeline:view"));
        SessionPermissionResolver resolver = mock(SessionPermissionResolver.class);
        ReflectionTestUtils.setField(binder, "sessionPermissionResolver", resolver);

        ReflectionTestUtils.setField(controller, "userMapper", userMapper);
        ReflectionTestUtils.setField(controller, "permissionService", permissions);
        ReflectionTestUtils.setField(controller, "ldapLoginService", ldap);
        ReflectionTestUtils.setField(controller, "loginAttemptGuard", guard);
        ReflectionTestUtils.setField(controller, "sessionPermissionResolver", resolver);
        ReflectionTestUtils.setField(controller, "loginSessionBinder", binder);
        ReflectionTestUtils.setField(controller, "twoFactorService", t.service);
        ReflectionTestUtils.setField(controller, "clock", (java.util.function.LongSupplier) t.now::get);

        when(userMapper.selectOne(any())).thenAnswer(inv -> null);
        when(userMapper.selectById(1L)).thenReturn(local);
        when(userMapper.selectById(2L)).thenReturn(ldapUser);
        when(ldap.shouldUseLdap(any())).thenAnswer(inv -> {
            User u = inv.getArgument(0);
            return u != null && "ldap".equals(u.getSource());
        });
        when(ldap.login(eq("bob"), eq(PASSWORD), any())).thenReturn(ldapSuccess(ldapUser));
    }

    // ---------- 第一步：三种策略 × 本地 / LDAP ----------

    @Test
    void 策略关闭_已绑定也只需密码() {
        t.enroll(1L);
        t.enroll(2L);
        for (User u : new User[]{local, ldapUser}) {
            Session s = new Session();
            Map<String, Object> r = login(s, u);
            assertEquals(true, r.get("success"), u.getUsername());
            assertEquals(false, r.get(TwoFactorSession.MUST_ENROLL));
            assertTrue(s.authenticated());
        }
    }

    @Test
    void 用户自愿开启_已绑定要动态码_未绑定直接登录() {
        t.policy(Policy.OPTIONAL);
        for (User u : new User[]{local, ldapUser}) {
            Session notEnrolled = new Session();
            assertEquals(true, login(notEnrolled, u).get("success"), u.getUsername() + " 未绑定");
            assertFalse(notEnrolled.has(TwoFactorSession.MUST_ENROLL));

            t.enroll(u.getId());
            Session enrolled = new Session();
            Map<String, Object> r = login(enrolled, u);
            assertEquals(false, r.get("success"), u.getUsername() + " 已绑定");
            assertEquals("two_factor", r.get("step"));
            assertEquals(u.getUsername(), r.get("username"));
            assertFalse(enrolled.authenticated(), "第一步不绑定会话");
            assertEquals(u.getId(), enrolled.get(TwoFactorSession.PENDING_USER_ID));
        }
    }

    @Test
    void 全员强制_已绑定要动态码_未绑定登录后带强制绑定标记() {
        t.policy(Policy.REQUIRED);
        for (User u : new User[]{local, ldapUser}) {
            Session notEnrolled = new Session();
            Map<String, Object> r = login(notEnrolled, u);
            assertEquals(true, r.get("success"), u.getUsername());
            assertEquals(true, r.get(TwoFactorSession.MUST_ENROLL));
            assertTrue(notEnrolled.authenticated());
            assertTrue(notEnrolled.has(TwoFactorSession.MUST_ENROLL));

            t.enroll(u.getId());
            assertEquals("two_factor", login(new Session(), u).get("step"));
        }
    }

    @Test
    void 单点登录来源的账号不受策略影响() {
        t.policy(Policy.REQUIRED);
        User sso = user(3L, "carol", "sso");
        assertFalse(t.service.isEligible(sso));
        // 模拟扩展的单点登录回调：直接绑定会话，不经过 login()
        Session s = new Session();
        binder.bind(s.request().getSession(), "carol", 3L, Collections.emptyList(), Collections.emptyList());
        assertTrue(s.authenticated());
        assertFalse(s.has(TwoFactorSession.MUST_ENROLL));
        assertFalse(s.has(TwoFactorSession.PENDING_USER_ID));
    }

    @Test
    void 单点登录即使账号已绑定也不要求动态码() {
        t.policy(Policy.REQUIRED);
        t.enroll(1L);
        Session s = new Session();
        binder.bind(s.request().getSession(), "alice", 1L, Collections.emptyList(), Collections.emptyList());
        assertTrue(s.authenticated());
        assertFalse(s.has(TwoFactorSession.PENDING_USER_ID));
    }

    // ---------- 第二步 ----------

    @Test
    void 动态码正确_换会话ID并绑定会话() {
        t.policy(Policy.OPTIONAL);
        String secret = t.enroll(1L);
        t.advance(1);
        Session s = new Session();
        login(s, local);
        String pendingId = s.session.getId();

        Map<String, Object> r = verify(s, "code", t.codeNow(secret));
        assertEquals(true, r.get("success"));
        assertTrue(s.authenticated());
        assertNotEquals(pendingId, s.session.getId(), "第二步通过后要换会话 ID");
        assertFalse(s.has(TwoFactorSession.PENDING_USER_ID));
        assertEquals("alice", r.get("username"));
    }

    @Test
    void LDAP用户第二步同样生效() {
        t.policy(Policy.REQUIRED);
        String secret = t.enroll(2L);
        t.advance(1);
        Session s = new Session();
        assertEquals("two_factor", login(s, ldapUser).get("step"));
        assertEquals(true, verify(s, "code", t.codeNow(secret)).get("success"));
        assertTrue(s.authenticated());
    }

    @Test
    void 输错提示剩余次数_第5次作废并计一次登录失败() {
        t.policy(Policy.OPTIONAL);
        String secret = t.enroll(1L);
        Session s = new Session();
        login(s, local);
        for (int i = 1; i <= 4; i++) {
            Map<String, Object> r = verify(s, "code", t.wrongCode(secret));
            assertEquals("two_factor.code_invalid", r.get("code"));
            assertEquals(5 - i, r.get("remainingAttempts"));
        }
        Map<String, Object> last = verify(s, "code", t.wrongCode(secret));
        assertEquals("two_factor.too_many", last.get("code"));
        assertFalse(s.has(TwoFactorSession.PENDING_USER_ID), "待验证状态作废");
        assertFalse(s.authenticated());
        t.advance(1);
        assertEquals("two_factor.expired", verify(s, "code", t.codeNow(secret)).get("code"), "作废后正确的码也不行");
    }

    @Test
    void 第二步失败计入登录失败次数_重新输密码不会清零_满5轮锁定账号() {
        t.policy(Policy.OPTIONAL);
        String secret = t.enroll(1L);
        for (int round = 0; round < 5; round++) {
            Session s = new Session();
            assertEquals("two_factor", login(s, local).get("step"), "第 " + (round + 1) + " 轮");
            for (int i = 0; i < 5; i++) {
                verify(s, "code", t.wrongCode(secret));
            }
        }
        assertTrue(guard.lockedSeconds("alice") > 0);
        Map<String, Object> r = login(new Session(), local);
        assertEquals(false, r.get("success"));
        assertTrue(String.valueOf(r.get("message")).contains("登录失败次数过多"));
    }

    @Test
    void 超过5分钟作废() {
        t.policy(Policy.OPTIONAL);
        String secret = t.enroll(1L);
        Session s = new Session();
        login(s, local);
        t.now.addAndGet(TwoFactorSession.PENDING_TTL_MILLIS + 1);
        assertEquals("two_factor.expired", verify(s, "code", t.codeNow(secret)).get("code"));
        assertFalse(s.has(TwoFactorSession.PENDING_USER_ID));
        assertFalse(s.authenticated());
    }

    @Test
    void 没有走第一步直接提交_按超时处理() {
        assertEquals("two_factor.expired", verify(new Session(), "code", "123456").get("code"));
    }

    @Test
    void 同一个动态码不能登录两次_防重放() {
        t.policy(Policy.OPTIONAL);
        String secret = t.enroll(1L);
        t.advance(1);
        String code = t.codeNow(secret);
        Session first = new Session();
        login(first, local);
        assertEquals(true, verify(first, "code", code).get("success"));

        Session second = new Session();
        login(second, local);
        assertEquals("two_factor.code_invalid", verify(second, "code", code).get("code"));
    }

    @Test
    void 恢复码登录_只能用一次_并给一次性提示() {
        t.policy(Policy.OPTIONAL);
        String secret = t.service.newSecret();
        List<String> codes = t.service.confirmEnroll(1L, secret, t.codeNow(secret));
        Session s = new Session();
        login(s, local);
        Map<String, Object> r = verify(s, "recoveryCode", codes.get(3));
        assertEquals(true, r.get("success"));
        assertEquals(true, r.get("recoveryCodeUsed"));
        assertEquals(9, r.get("recoveryCodesLeft"));

        Map<?, ?> info = (Map<?, ?>) controller.getCurrentUser(s.session).get("twoFactor");
        assertEquals(true, info.get("recoveryCodeUsed"));
        assertEquals(9, info.get("recoveryCodesLeft"));
        Map<?, ?> again = (Map<?, ?>) controller.getCurrentUser(s.session).get("twoFactor");
        assertEquals(false, again.get("recoveryCodeUsed"), "提示只给一次");

        Session other = new Session();
        login(other, local);
        Map<String, Object> reuse = verify(other, "recoveryCode", codes.get(3));
        assertEquals("two_factor.recovery_invalid", reuse.get("code"));
        assertEquals(4, reuse.get("remainingAttempts"));
    }

    @Test
    void 弱口令加两步验证_第二步通过后仍要改密() {
        t.policy(Policy.OPTIONAL);
        local.setPassword(PasswordHasher.hash("alice"));
        String secret = t.enroll(1L);
        t.advance(1);
        Session s = new Session();
        Map<String, String> body = new HashMap<>();
        body.put("username", "alice");
        body.put("password", "alice");
        when(userMapper.selectOne(any())).thenReturn(local);
        assertEquals("two_factor", controller.login(body, s.request()).get("step"));
        Map<String, Object> r = verify(s, "code", t.codeNow(secret));
        assertEquals(true, r.get(AuthController.MUST_CHANGE_PASSWORD_KEY));
        assertTrue(s.has(AuthController.MUST_CHANGE_PASSWORD_KEY));
    }

    @Test
    void 当前用户信息带两步验证状态() {
        t.policy(Policy.REQUIRED);
        Session s = new Session();
        login(s, local);
        Map<?, ?> info = (Map<?, ?>) controller.getCurrentUser(s.session).get("twoFactor");
        assertEquals("required", info.get("policy"));
        assertEquals(false, info.get("enrolled"));
        assertEquals(true, info.get("eligible"));
        assertEquals(true, info.get("mustEnroll"));
        assertEquals(0, info.get("recoveryCodesLeft"));
    }

    // ---------- 工具 ----------

    private Map<String, Object> login(Session s, User u) {
        when(userMapper.selectOne(any())).thenReturn(u);
        Map<String, String> body = new HashMap<>();
        body.put("username", u.getUsername());
        body.put("password", PASSWORD);
        return controller.login(body, s.request());
    }

    private Map<String, Object> verify(Session s, String key, String value) {
        Map<String, String> body = new HashMap<>();
        body.put(key, value);
        return controller.verifyTwoFactor(body, s.request());
    }

    private static User user(Long id, String username, String source) {
        User u = new User();
        u.setId(id);
        u.setUsername(username);
        u.setSource(source);
        u.setStatus(1);
        u.setPassword("ldap".equals(source) ? null : PasswordHasher.hash(PASSWORD));
        return u;
    }

    private static LdapLoginService.Outcome ldapSuccess(User user) throws Exception {
        Constructor<LdapLoginService.Outcome> c = LdapLoginService.Outcome.class.getDeclaredConstructor(
                LdapLoginService.Status.class, User.class, String.class);
        c.setAccessible(true);
        return c.newInstance(LdapLoginService.Status.SUCCESS, user, null);
    }

    /** 模拟浏览器：同一个会话发多次请求，会话被作废后自动换新 */
    private static final class Session {
        HttpSession session;

        MockHttpServletRequest request() {
            MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/auth/login") {
                @Override
                public HttpSession getSession(boolean create) {
                    HttpSession current = super.getSession(create);
                    session = current != null ? current : session;
                    return current;
                }
            };
            if (session != null && !isInvalidated(session)) {
                request.setSession(session);
            }
            return request;
        }

        boolean authenticated() {
            return session != null && !isInvalidated(session) && Boolean.TRUE.equals(session.getAttribute("authenticated"));
        }

        boolean has(String key) {
            return session != null && !isInvalidated(session) && session.getAttribute(key) != null;
        }

        Object get(String key) {
            return session.getAttribute(key);
        }

        private static boolean isInvalidated(HttpSession s) {
            return s instanceof org.springframework.mock.web.MockHttpSession
                    && ((org.springframework.mock.web.MockHttpSession) s).isInvalid();
        }
    }
}
