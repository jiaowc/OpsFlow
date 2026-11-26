package com.opsflow.web.controller;

import com.opsflow.dao.model.User;
import com.opsflow.service.TwoFactorService.Policy;
import com.opsflow.service.TwoFactorService.Verification;
import com.opsflow.service.impl.TwoFactorTestSupport;
import com.opsflow.web.security.TwoFactorSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/** 本人绑定、更换、解绑，以及管理员重置 */
class TwoFactorControllerTest {

    private final TwoFactorTestSupport t = new TwoFactorTestSupport();
    private final TwoFactorController controller = new TwoFactorController();
    private final TwoFactorAdminController admin = new TwoFactorAdminController();
    private final MockHttpSession session = new MockHttpSession();
    private final User alice = user(1L, "alice", "local");

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(controller, "twoFactorService", t.service);
        ReflectionTestUtils.setField(controller, "userMapper", t.userMapper);
        ReflectionTestUtils.setField(controller, "clock", (java.util.function.LongSupplier) t.now::get);
        ReflectionTestUtils.setField(admin, "twoFactorService", t.service);
        ReflectionTestUtils.setField(admin, "userMapper", t.userMapper);
        when(t.userMapper.selectById(1L)).thenReturn(alice);
        session.setAttribute("authenticated", true);
        session.setAttribute("userId", 1L);
        t.policy(Policy.OPTIONAL);
    }

    @Test
    void 强制绑定_开始时密钥只在session_确认后落库_清除强制标记() {
        t.policy(Policy.REQUIRED);
        session.setAttribute(TwoFactorSession.MUST_ENROLL, Boolean.TRUE);
        Map<String, Object> start = controller.startEnroll(session);
        assertEquals(true, start.get("success"));
        String secret = (String) start.get("secret");
        assertTrue(((String) start.get("otpauthUri")).startsWith("otpauth://totp/OpsFlow:alice?secret=" + secret));
        assertFalse(t.service.isEnrolled(1L), "开始绑定不落库");

        assertEquals("two_factor.code_invalid", controller.confirmEnroll(body("code", t.wrongCode(secret)), session).get("code"));
        assertTrue(session.getAttribute(TwoFactorSession.MUST_ENROLL) != null);

        Map<String, Object> done = controller.confirmEnroll(body("code", t.codeNow(secret)), session);
        assertEquals(true, done.get("success"));
        assertEquals(10, ((List<?>) done.get("recoveryCodes")).size());
        assertTrue(t.service.isEnrolled(1L));
        assertNull(session.getAttribute(TwoFactorSession.MUST_ENROLL));
        assertNull(session.getAttribute(TwoFactorSession.ENROLL_SECRET));
    }

    @Test
    void 不能开始绑定的情况() {
        t.policy(Policy.OFF);
        assertEquals("two_factor.policy_off", controller.startEnroll(session).get("code"));
        t.policy(Policy.OPTIONAL);
        alice.setSource("sso");
        assertEquals("two_factor.not_eligible", controller.startEnroll(session).get("code"));
        alice.setSource("local");
        t.enroll(1L);
        assertEquals("two_factor.already_enrolled", controller.startEnroll(session).get("code"));
        assertEquals("two_factor.not_logged_in", controller.startEnroll(new MockHttpSession()).get("code"));
    }

    @Test
    void 未确认的密钥10分钟后作废() {
        String secret = (String) controller.startEnroll(session).get("secret");
        t.now.addAndGet(TwoFactorSession.ENROLL_TTL_MILLIS + 1);
        assertEquals("two_factor.enroll_expired", controller.confirmEnroll(body("code", t.codeNow(secret)), session).get("code"));
        assertFalse(t.service.isEnrolled(1L));
    }

    @Test
    void 更换验证器_要当前动态码_确认前旧绑定有效() {
        String old = t.enroll(1L);
        t.advance(1);
        assertEquals("two_factor.code_invalid", controller.replace(body("code", t.wrongCode(old)), session).get("code"));

        Map<String, Object> start = controller.replace(body("code", t.codeNow(old)), session);
        String fresh = (String) start.get("secret");
        t.advance(1);
        assertEquals(Verification.CODE_OK, t.service.verify(1L, t.codeNow(old), null), "确认前旧绑定仍然有效");

        t.advance(1);
        assertEquals(true, controller.confirmEnroll(body("code", t.codeNow(fresh)), session).get("success"));
        t.advance(1);
        assertEquals(Verification.CODE_INVALID, t.service.verify(1L, t.codeNow(old), null));
        assertEquals(Verification.CODE_OK, t.service.verify(1L, t.codeNow(fresh), null));
    }

    @Test
    void 已绑定时不能绕过当前动态码直接确认新密钥() {
        t.enroll(1L);
        session.setAttribute(TwoFactorSession.ENROLL_SECRET, t.service.newSecret());
        session.setAttribute(TwoFactorSession.ENROLL_AT, t.now.get());
        String forged = (String) session.getAttribute(TwoFactorSession.ENROLL_SECRET);
        assertEquals("two_factor.already_enrolled", controller.confirmEnroll(body("code", t.codeNow(forged)), session).get("code"));
    }

    @Test
    void 重新生成恢复码_可用恢复码证明身份() {
        String secret = t.service.newSecret();
        List<String> old = t.service.confirmEnroll(1L, secret, t.codeNow(secret));
        Map<String, Object> r = controller.regenerate(body("recoveryCode", old.get(0)), session);
        assertEquals(true, r.get("success"));
        List<?> fresh = (List<?>) r.get("recoveryCodes");
        assertEquals(10, fresh.size());
        assertEquals(Verification.RECOVERY_INVALID, t.service.verify(1L, null, old.get(1)), "旧恢复码全部作废");
    }

    @Test
    void 解绑_要当前动态码_全员强制时不允许() {
        String secret = t.enroll(1L);
        t.advance(1);
        t.policy(Policy.REQUIRED);
        assertEquals("two_factor.disable_forbidden", controller.disable(body("code", t.codeNow(secret)), session).get("code"));
        assertTrue(t.service.isEnrolled(1L));

        t.policy(Policy.OPTIONAL);
        assertEquals("two_factor.code_invalid", controller.disable(body("code", t.wrongCode(secret)), session).get("code"));
        assertEquals(true, controller.disable(body("code", t.codeNow(secret)), session).get("success"));
        assertFalse(t.service.isEnrolled(1L));
    }

    @Test
    void 管理操作连续输错5次作废会话() {
        String secret = t.enroll(1L);
        for (int i = 1; i <= 4; i++) {
            assertEquals(5 - i, controller.disable(body("code", t.wrongCode(secret)), session).get("remainingAttempts"));
        }
        assertEquals("two_factor.too_many", controller.disable(body("code", t.wrongCode(secret)), session).get("code"));
        assertTrue(session.isInvalid());
        assertTrue(t.service.isEnrolled(1L));
    }

    @Test
    void 状态() {
        t.enroll(1L);
        Map<String, Object> s = controller.status(session);
        assertEquals("optional", s.get("policy"));
        assertEquals(true, s.get("enrolled"));
        assertEquals(true, s.get("eligible"));
        assertEquals(10, s.get("recoveryCodesLeft"));
        assertTrue(s.get("confirmedTime") != null);
    }

    @Test
    void 管理员重置_绑定和恢复码都作废() {
        t.enroll(1L);
        Map<String, Object> r = admin.reset(1L);
        assertEquals(true, r.get("success"));
        assertEquals("alice", r.get("username"));
        assertFalse(t.service.isEnrolled(1L));
        assertEquals(0, t.service.recoveryCodesLeft(1L));
    }

    @Test
    void 管理接口的权限声明() throws Exception {
        assertEquals("system:config", TwoFactorAdminController.class.getMethod("summary")
                .getAnnotation(com.opsflow.web.security.RequiresPermission.class).value()[0]);
        assertEquals("user:manage", TwoFactorAdminController.class.getMethod("reset", Long.class)
                .getAnnotation(com.opsflow.web.security.RequiresPermission.class).value()[0]);
    }

    private static Map<String, String> body(String key, String value) {
        return Collections.singletonMap(key, value);
    }

    private static User user(Long id, String username, String source) {
        User u = new User();
        u.setId(id);
        u.setUsername(username);
        u.setSource(source);
        u.setStatus(1);
        return u;
    }
}
