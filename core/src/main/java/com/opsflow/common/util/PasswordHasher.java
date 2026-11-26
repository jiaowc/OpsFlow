package com.opsflow.common.util;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.util.DigestUtils;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.regex.Pattern;

/**
 * 用户密码哈希：新密码统一 BCrypt；兼容历史无盐 MD5（32 位十六进制），登录成功后应调用方升级为 BCrypt。
 */
public final class PasswordHasher {

    private static final BCryptPasswordEncoder BCRYPT = new BCryptPasswordEncoder();

    private static final Pattern LEGACY_MD5 = Pattern.compile("^[0-9a-fA-F]{32}$");

    private PasswordHasher() {
    }

    public static String hash(String rawPassword) {
        return BCRYPT.encode(rawPassword);
    }

    public static boolean matches(String rawPassword, String storedHash) {
        if (rawPassword == null || !StringUtils.hasText(storedHash)) {
            return false;
        }
        if (isLegacyMd5(storedHash)) {
            String md5 = DigestUtils.md5DigestAsHex(rawPassword.getBytes(StandardCharsets.UTF_8));
            return MessageDigest.isEqual(
                    md5.getBytes(StandardCharsets.US_ASCII),
                    storedHash.toLowerCase().getBytes(StandardCharsets.US_ASCII));
        }
        try {
            return BCRYPT.matches(rawPassword, storedHash);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    public static boolean needsUpgrade(String storedHash) {
        return StringUtils.hasText(storedHash) && isLegacyMd5(storedHash);
    }

    private static boolean isLegacyMd5(String storedHash) {
        return LEGACY_MD5.matcher(storedHash).matches();
    }
}
