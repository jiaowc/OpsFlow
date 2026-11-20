package com.opsflow.web.security;

import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 登录失败限流：同一用户名连续失败 MAX_FAILURES 次后锁定 LOCK_MILLIS。
 * 仅进程内计数，多实例部署时各实例独立计数。
 */
@Component
public class LoginAttemptGuard {

    private static final int MAX_FAILURES = 5;
    private static final long LOCK_MILLIS = 15 * 60 * 1000L;
    private static final int MAX_TRACKED = 10000;

    private final Map<String, Attempt> attempts = new ConcurrentHashMap<>();

    /**
     * @return 剩余锁定秒数；未锁定返回 0
     */
    public long lockedSeconds(String username) {
        Attempt attempt = attempts.get(key(username));
        if (attempt == null || attempt.lockedUntil == 0) {
            return 0;
        }
        long remaining = attempt.lockedUntil - System.currentTimeMillis();
        if (remaining <= 0) {
            attempts.remove(key(username));
            return 0;
        }
        return (remaining + 999) / 1000;
    }

    public void recordFailure(String username) {
        if (attempts.size() >= MAX_TRACKED) {
            long now = System.currentTimeMillis();
            attempts.values().removeIf(a -> a.lockedUntil != 0 && a.lockedUntil < now);
            if (attempts.size() >= MAX_TRACKED) {
                attempts.clear();
            }
        }
        attempts.compute(key(username), (k, attempt) -> {
            Attempt next = attempt != null ? attempt : new Attempt();
            next.failures++;
            if (next.failures >= MAX_FAILURES) {
                next.lockedUntil = System.currentTimeMillis() + LOCK_MILLIS;
            }
            return next;
        });
    }

    public void recordSuccess(String username) {
        attempts.remove(key(username));
    }

    private String key(String username) {
        return username == null ? "" : username.trim().toLowerCase(Locale.ROOT);
    }

    private static final class Attempt {
        private int failures;
        private long lockedUntil;
    }
}
