package com.opsflow.service;

import com.opsflow.dao.model.User;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 两步验证（TOTP 动态码 + 一次性恢复码）。只作用于账号密码登录和 LDAP 登录；扩展提供的单点登录不要求动态码。
 * <p>这里只管数据和校验；登录第二步、绑定流程的 session 状态由控制器负责。</p>
 */
public interface TwoFactorService {

    /** 策略存在系统配置里 */
    String CONFIG_TYPE = "security";
    String POLICY_KEY = "twoFactorPolicy";

    /** 每次生成的恢复码个数 */
    int RECOVERY_CODE_COUNT = 10;

    enum Policy {
        /** 登录只需密码；已有绑定保留 */
        OFF("off"),
        /** 用户自愿绑定，绑定后登录要动态码 */
        OPTIONAL("optional"),
        /** 所有可使用的账号都必须绑定 */
        REQUIRED("required");

        private final String code;

        Policy(String code) {
            this.code = code;
        }

        public String code() {
            return code;
        }

        /** 空值或不认识的值按关闭处理 */
        public static Policy parse(String value) {
            Policy p = fromCode(value);
            return p != null ? p : OFF;
        }

        /** 不认识返回 null */
        public static Policy fromCode(String value) {
            if (value == null) {
                return null;
            }
            String v = value.trim().toLowerCase(Locale.ROOT);
            for (Policy p : values()) {
                if (p.code.equals(v)) {
                    return p;
                }
            }
            return null;
        }
    }

    /** 校验结果 */
    enum Verification {
        /** 动态码正确 */
        CODE_OK,
        /** 恢复码正确，已作废 */
        RECOVERY_OK,
        CODE_INVALID,
        RECOVERY_INVALID,
        /** 没有绑定 */
        NOT_ENROLLED;

        public boolean isOk() {
            return this == CODE_OK || this == RECOVERY_OK;
        }
    }

    Policy policy();

    /** 账号来源为本地或 LDAP 才能使用两步验证 */
    boolean isEligible(User user);

    boolean isEnrolled(Long userId);

    int recoveryCodesLeft(Long userId);

    LocalDateTime confirmedTime(Long userId);

    /** 新的 Base32 密钥（不落库，由调用方暂存在 session） */
    String newSecret();

    /** 验证器 App 扫码用的 otpauth 地址 */
    String otpauthUri(String account, String secret);

    /**
     * 用动态码确认绑定：成功后写库（覆盖旧绑定、作废旧恢复码），返回新的恢复码明文；动态码不对返回 null。
     */
    List<String> confirmEnroll(Long userId, String secret, String code);

    /** 校验动态码（同一时间窗只能用一次）或恢复码（用后作废），两个都给时只看恢复码 */
    Verification verify(Long userId, String code, String recoveryCode);

    /** 作废旧恢复码，返回新的明文 */
    List<String> regenerateRecoveryCodes(Long userId);

    /** 删除绑定和全部恢复码（本人解绑、管理员重置、删除用户共用） */
    void remove(Long userId);

    /** 给定用户里已绑定的 */
    Set<Long> enrolledAmong(Collection<Long> userIds);

    /** {@code {policy, enrolled, eligible}}：可使用的账号数（启用、来源本地或 LDAP）和其中已绑定的人数 */
    Map<String, Object> summary();
}
