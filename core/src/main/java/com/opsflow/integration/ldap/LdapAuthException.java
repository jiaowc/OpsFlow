package com.opsflow.integration.ldap;

/**
 * LDAP 认证失败。消息不含任何密码，可以直接展示给管理员或写日志。
 */
public class LdapAuthException extends Exception {

    public enum Reason {
        /** 用户不存在或密码错误（对登录用户统一提示，避免暴露账号是否存在） */
        BAD_CREDENTIALS,
        /** 服务器连不上、超时、TLS 握手失败等 */
        UNAVAILABLE,
        /** 配置不完整或绑定账号认证失败等需要管理员处理的问题 */
        CONFIG
    }

    private final Reason reason;

    public LdapAuthException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public LdapAuthException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public Reason getReason() {
        return reason;
    }
}
