package com.opsflow.web.security;

import javax.servlet.http.HttpSession;

/**
 * 两步验证在 session 里的状态：登录第二步的待验证用户、绑定流程里还没确认的密钥、强制绑定标记等。
 */
public final class TwoFactorSession {

    /** 强制绑定：会话已登录，但 /api/auth/ 以外的接口一律 403，直到完成绑定 */
    public static final String MUST_ENROLL = "mustEnrollTwoFactor";

    /** 登录第二步：密码已验证、等待动态码的用户 */
    public static final String PENDING_USER_ID = "twoFactorPendingUserId";
    public static final String PENDING_USERNAME = "twoFactorPendingUsername";
    public static final String PENDING_AT = "twoFactorPendingAt";
    public static final String PENDING_ATTEMPTS = "twoFactorPendingAttempts";
    /** 第一步时算出的"弱口令必须改密"，第二步通过后带进会话 */
    public static final String PENDING_MUST_CHANGE_PASSWORD = "twoFactorPendingMustChangePassword";

    /** 绑定流程：还没确认的密钥，确认后才落库 */
    public static final String ENROLL_SECRET = "twoFactorEnrollSecret";
    public static final String ENROLL_AT = "twoFactorEnrollAt";
    /** 已用当前动态码验证过身份，允许用新密钥覆盖旧绑定（更换验证器） */
    public static final String ENROLL_REPLACE = "twoFactorEnrollReplace";

    /** 已登录用户在管理操作里输错动态码的次数 */
    public static final String MANAGE_FAILURES = "twoFactorManageFailures";

    /** 用恢复码登录后的一次性提示，/api/auth/user 读一次后清除 */
    public static final String RECOVERY_USED = "twoFactorRecoveryUsed";

    /** 登录第二步的有效期 */
    public static final long PENDING_TTL_MILLIS = 5 * 60 * 1000L;
    /** 绑定流程里未确认密钥的有效期 */
    public static final long ENROLL_TTL_MILLIS = 10 * 60 * 1000L;
    /** 第二步、管理操作的最多尝试次数 */
    public static final int MAX_ATTEMPTS = 5;

    private TwoFactorSession() {
    }

    public static void clearPending(HttpSession session) {
        if (session == null) {
            return;
        }
        session.removeAttribute(PENDING_USER_ID);
        session.removeAttribute(PENDING_USERNAME);
        session.removeAttribute(PENDING_AT);
        session.removeAttribute(PENDING_ATTEMPTS);
        session.removeAttribute(PENDING_MUST_CHANGE_PASSWORD);
    }

    public static void clearEnroll(HttpSession session) {
        session.removeAttribute(ENROLL_SECRET);
        session.removeAttribute(ENROLL_AT);
        session.removeAttribute(ENROLL_REPLACE);
    }
}
