package com.opsflow.common.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 密码哈希：新密码用 BCrypt，历史 MD5 仍能登录并被标记为需要升级。
 */
class PasswordHasherTest {

    /** "admin" 的无盐 MD5，历史数据的存储形态 */
    private static final String MD5_OF_ADMIN = "21232f297a57a5a743894a0e4a801fc3";

    @Test
    void hashesWithBcryptAndVerifies() {
        String hash = PasswordHasher.hash("S3cret-Pass");
        assertTrue(hash.startsWith("$2"), "新密码必须是 BCrypt");
        assertTrue(PasswordHasher.matches("S3cret-Pass", hash));
        assertFalse(PasswordHasher.matches("s3cret-pass", hash), "区分大小写");
        assertFalse(PasswordHasher.needsUpgrade(hash));
    }

    @Test
    void sameSecretHashesDifferentlyEachTime() {
        assertNotEquals(PasswordHasher.hash("S3cret-Pass"), PasswordHasher.hash("S3cret-Pass"), "BCrypt 带随机盐");
    }

    @Test
    void legacyMd5StillMatchesAndIsFlaggedForUpgrade() {
        assertTrue(PasswordHasher.matches("admin", MD5_OF_ADMIN));
        assertTrue(PasswordHasher.matches("admin", MD5_OF_ADMIN.toUpperCase()), "历史数据里的大写十六进制也能匹配");
        assertFalse(PasswordHasher.matches("Admin", MD5_OF_ADMIN));
        assertTrue(PasswordHasher.needsUpgrade(MD5_OF_ADMIN));
    }

    @Test
    void rejectsMissingOrMalformedInput() {
        assertFalse(PasswordHasher.matches(null, MD5_OF_ADMIN));
        assertFalse(PasswordHasher.matches("admin", null));
        assertFalse(PasswordHasher.matches("admin", "  "));
        assertFalse(PasswordHasher.matches("admin", "not-a-hash"), "既不是 MD5 也不是 BCrypt 的值不能通过");
        assertFalse(PasswordHasher.matches("admin", "admin"), "明文存储的值不能当作匹配");
        assertFalse(PasswordHasher.needsUpgrade(null));
        assertFalse(PasswordHasher.needsUpgrade(""));
    }
}
