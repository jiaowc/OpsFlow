package com.opsflow.web.security;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 登录失败限流：同一账号连续失败 5 次后锁定 15 分钟。
 */
class LoginAttemptGuardTest {

    private final LoginAttemptGuard guard = new LoginAttemptGuard();

    private void fail(String username, int times) {
        for (int i = 0; i < times; i++) {
            guard.recordFailure(username);
        }
    }

    @Test
    void locksAfterFiveConsecutiveFailures() {
        fail("alice", 4);
        assertEquals(0, guard.lockedSeconds("alice"), "第 4 次失败还不锁");
        fail("alice", 1);
        long seconds = guard.lockedSeconds("alice");
        assertTrue(seconds > 14 * 60 && seconds <= 15 * 60, "锁定约 15 分钟，实际 " + seconds + " 秒");
    }

    @Test
    void usernameIsCaseAndWhitespaceInsensitive() {
        fail("Alice", 3);
        fail(" alice ", 2);
        assertTrue(guard.lockedSeconds("ALICE") > 0, "换大小写或加空格不能绕过计数");
    }

    @Test
    void otherAccountsAreNotAffected() {
        fail("alice", 5);
        assertEquals(0, guard.lockedSeconds("bob"));
    }

    @Test
    void successClearsTheCounter() {
        fail("alice", 4);
        guard.recordSuccess("alice");
        fail("alice", 4);
        assertEquals(0, guard.lockedSeconds("alice"), "成功登录后重新计数");
    }

    @Test
    void lockExpiresAndCounterStartsOver() {
        fail("alice", 5);
        expireLock("alice");
        assertEquals(0, guard.lockedSeconds("alice"));
        fail("alice", 4);
        assertEquals(0, guard.lockedSeconds("alice"), "锁过期后从 0 重新计数，而不是再失败一次就锁");
    }

    @Test
    void nullUsernameIsHandled() {
        fail(null, 5);
        assertTrue(guard.lockedSeconds(null) > 0);
        assertTrue(guard.lockedSeconds("  ") > 0, "null 和空白用户名共用同一个计数");
    }

    @SuppressWarnings("unchecked")
    private void expireLock(String username) {
        Map<String, Object> attempts = (Map<String, Object>) ReflectionTestUtils.getField(guard, "attempts");
        Object attempt = attempts.get(username);
        ReflectionTestUtils.setField(attempt, "lockedUntil", System.currentTimeMillis() - 1000);
    }
}
