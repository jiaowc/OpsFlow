package com.opsflow.common.util;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * 本地账号口令策略：弱口令判定（登录后强制改密、修改密码时校验新密码）。
 */
public final class PasswordPolicy {

    /** 新密码最小长度 */
    public static final int MIN_LENGTH = 8;

    private static final Set<String> WEAK_PASSWORDS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "123456", "admin", "password"
    )));

    private PasswordPolicy() {
    }

    /**
     * 是否为弱口令：常见弱口令（忽略大小写）、与用户名相同（忽略大小写）、长度不足 {@link #MIN_LENGTH}。
     */
    public static boolean isWeak(String username, String rawPassword) {
        if (rawPassword == null || rawPassword.length() < MIN_LENGTH) {
            return true;
        }
        String lower = rawPassword.toLowerCase(Locale.ROOT);
        if (WEAK_PASSWORDS.contains(lower)) {
            return true;
        }
        return username != null && !username.trim().isEmpty()
                && lower.equals(username.trim().toLowerCase(Locale.ROOT));
    }
}
