package com.opsflow.integration.ldap;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * LDAP 认证通过的用户信息（不含密码）
 */
@Data
@AllArgsConstructor
public class LdapUserInfo {

    private String dn;

    /** 用户名属性的值（如 uid） */
    private String username;

    private String realName;

    private String email;
}
