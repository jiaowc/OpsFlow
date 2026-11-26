package com.opsflow.service.impl;

import com.opsflow.dao.model.User;
import com.opsflow.service.TwoFactorService;
import com.opsflow.service.TwoFactorService.Verification;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

class TwoFactorServiceImplTest {

    private final TwoFactorTestSupport t = new TwoFactorTestSupport();
    private final TwoFactorServiceImpl service = t.service;

    @Test
    void 策略按系统配置读取_空值和不认识的值按关闭() {
        assertEquals(TwoFactorService.Policy.OFF, service.policy());
        t.policy(TwoFactorService.Policy.REQUIRED);
        assertEquals(TwoFactorService.Policy.REQUIRED, service.policy());
        assertEquals(TwoFactorService.Policy.OFF, TwoFactorService.Policy.parse("whatever"));
        assertEquals(TwoFactorService.Policy.OPTIONAL, TwoFactorService.Policy.parse(" Optional "));
        assertNull(TwoFactorService.Policy.fromCode("on"));
    }

    @Test
    void 只有本地和LDAP来源可用() {
        assertTrue(service.isEligible(user(1L, null)));
        assertTrue(service.isEligible(user(1L, "local")));
        assertTrue(service.isEligible(user(1L, "LDAP")));
        assertFalse(service.isEligible(user(1L, "sso")));
        assertFalse(service.isEligible(null));
    }

    @Test
    void 动态码不对不落库_对了才绑定并生成10个恢复码() {
        String secret = service.newSecret();
        assertNull(service.confirmEnroll(1L, secret, t.wrongCode(secret)));
        assertFalse(service.isEnrolled(1L));

        List<String> codes = service.confirmEnroll(1L, secret, t.codeNow(secret));
        assertTrue(service.isEnrolled(1L));
        assertEquals(10, codes.size());
        assertEquals(10, new HashSet<>(codes).size());
        for (String code : codes) {
            assertTrue(code.matches("[2-9a-hjkmnp-z]{4}-[2-9a-hjkmnp-z]{4}"), "格式 xxxx-xxxx，不含易混字符: " + code);
        }
        assertEquals(10, service.recoveryCodesLeft(1L));
        assertTrue(t.store.codes.stream().noneMatch(c -> codes.contains(c.getCodeHash())), "库里不存明文");
        assertTrue(t.store.find(1L).getSecret().equals(secret));
    }

    @Test
    void 确认绑定用过的动态码不能紧接着用来登录_防重放() {
        String secret = t.enroll(1L);
        assertEquals(Verification.CODE_INVALID, service.verify(1L, t.codeNow(secret), null), "绑定时用过的时间窗");
        t.advance(1);
        String next = t.codeNow(secret);
        assertEquals(Verification.CODE_OK, service.verify(1L, next, null));
        assertEquals(Verification.CODE_INVALID, service.verify(1L, next, null), "同一个动态码不能用两次");
        assertEquals(Verification.CODE_INVALID, service.verify(1L, t.codeAt(secret, -1), null), "更早的时间窗也不行");
    }

    @Test
    void 前后各容一个时间窗() {
        String secret = t.enroll(1L);
        t.advance(5);
        assertEquals(Verification.CODE_OK, service.verify(1L, t.codeAt(secret, -1), null));
        assertEquals(Verification.CODE_OK, service.verify(1L, t.codeAt(secret, 1), null));
        assertEquals(Verification.CODE_INVALID, service.verify(1L, t.codeAt(secret, 3), null));
    }

    @Test
    void 恢复码只能用一次_忽略大小写和连字符() {
        String secret = service.newSecret();
        List<String> codes = service.confirmEnroll(1L, secret, t.codeNow(secret));
        String first = codes.get(0);
        assertEquals(Verification.RECOVERY_OK, service.verify(1L, null, first.toUpperCase().replace("-", " ")));
        assertEquals(9, service.recoveryCodesLeft(1L));
        assertEquals(Verification.RECOVERY_INVALID, service.verify(1L, null, first), "用过的恢复码作废");
        assertEquals(Verification.RECOVERY_INVALID, service.verify(1L, null, "zzzz-zzzz"));
        assertEquals(Verification.RECOVERY_OK, service.verify(1L, "000000", codes.get(1)), "两个都给时只看恢复码");
    }

    @Test
    void 重新生成后旧恢复码全部作废() {
        String secret = service.newSecret();
        List<String> old = service.confirmEnroll(1L, secret, t.codeNow(secret));
        List<String> fresh = service.regenerateRecoveryCodes(1L);
        assertNotEquals(old, fresh);
        assertEquals(10, service.recoveryCodesLeft(1L));
        assertEquals(Verification.RECOVERY_INVALID, service.verify(1L, null, old.get(0)));
        assertEquals(Verification.RECOVERY_OK, service.verify(1L, null, fresh.get(0)));
    }

    @Test
    void 删除后不再有绑定和恢复码() {
        t.enroll(1L);
        service.remove(1L);
        assertFalse(service.isEnrolled(1L));
        assertEquals(0, service.recoveryCodesLeft(1L));
        assertEquals(Verification.NOT_ENROLLED, service.verify(1L, "123456", null));
    }

    @Test
    void 重新绑定覆盖旧密钥() {
        String old = t.enroll(1L);
        t.advance(1);
        String fresh = service.newSecret();
        service.confirmEnroll(1L, fresh, t.codeNow(fresh));
        t.advance(1);
        assertEquals(Verification.CODE_INVALID, service.verify(1L, t.codeNow(old), null));
        assertEquals(Verification.CODE_OK, service.verify(1L, t.codeNow(fresh), null));
    }

    @Test
    void 统计只算启用中的本地和LDAP账号() {
        when(t.userMapper.selectList(any())).thenReturn(Arrays.asList(
                user(1L, "local"), user(2L, "ldap"), user(3L, "sso"), user(4L, null)));
        t.enroll(1L);
        t.enroll(3L);
        t.policy(TwoFactorService.Policy.OPTIONAL);
        Map<String, Object> summary = service.summary();
        assertEquals("optional", summary.get("policy"));
        assertEquals(3, summary.get("eligible"));
        assertEquals(1, summary.get("enrolled"));
        assertEquals(new HashSet<>(Arrays.asList(1L)), service.enrolledAmong(Arrays.asList(1L, 2L)));
    }

    static User user(Long id, String source) {
        User user = new User();
        user.setId(id);
        user.setUsername("u" + id);
        user.setSource(source);
        user.setStatus(1);
        return user;
    }
}
