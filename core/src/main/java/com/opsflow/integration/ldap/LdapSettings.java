package com.opsflow.integration.ldap;

import lombok.Data;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * LDAP 登录配置（system_config 中 config_type = ldap）。
 * <p>bindPassword 在库里是 SecretCipher 密文，这里保存解密后的明文，只在内存中使用，不返回给前端、不写日志。
 */
@Data
public class LdapSettings {

    public static final String CONFIG_TYPE = "ldap";

    public static final String DEFAULT_USER_FILTER = "(uid={0})";
    public static final String DEFAULT_ROLE = "VIEWER";
    public static final int DEFAULT_TIMEOUT_MS = 5000;
    static final int MIN_TIMEOUT_MS = 500;
    static final int MAX_TIMEOUT_MS = 60000;

    private boolean enabled;

    /** ldap://host:389 或 ldaps://host:636 */
    private String url;

    /** 在 ldap:// 连接上先做 StartTLS 再认证 */
    private boolean startTls;

    /** 跳过证书校验（含主机名校验）。有中间人风险，默认关闭 */
    private boolean skipCertVerify;

    /** 用于搜索用户的绑定账号 DN；留空表示匿名搜索 */
    private String bindDn;

    private String bindPassword;

    /** 搜索用户的起点 */
    private String baseDn;

    /** 用户过滤条件，{0} 为登录用户名（由 JNDI 按 RFC 4515 转义后代入） */
    private String userFilter = DEFAULT_USER_FILTER;

    private String usernameAttribute = "uid";

    private String nameAttribute = "cn";

    private String emailAttribute = "mail";

    /** 首次登录自动建号时赋予的角色编码；留空表示不分配角色 */
    private String defaultRole = DEFAULT_ROLE;

    private int connectTimeoutMs = DEFAULT_TIMEOUT_MS;

    private int readTimeoutMs = DEFAULT_TIMEOUT_MS;

    public static LdapSettings fromMap(Map<String, String> values) {
        LdapSettings s = new LdapSettings();
        if (values == null) {
            return s;
        }
        s.enabled = Boolean.parseBoolean(trim(values.get("enabled")));
        s.url = trim(values.get("url"));
        s.startTls = Boolean.parseBoolean(trim(values.get("startTls")));
        s.skipCertVerify = Boolean.parseBoolean(trim(values.get("skipCertVerify")));
        s.bindDn = trim(values.get("bindDn"));
        s.bindPassword = values.get("bindPassword");
        s.baseDn = trim(values.get("baseDn"));
        s.userFilter = orDefault(values.get("userFilter"), DEFAULT_USER_FILTER);
        s.usernameAttribute = orDefault(values.get("usernameAttribute"), "uid");
        s.nameAttribute = orDefault(values.get("nameAttribute"), "cn");
        s.emailAttribute = orDefault(values.get("emailAttribute"), "mail");
        s.defaultRole = values.containsKey("defaultRole") ? trim(values.get("defaultRole")) : DEFAULT_ROLE;
        s.connectTimeoutMs = parseTimeout(values.get("connectTimeoutMs"));
        s.readTimeoutMs = parseTimeout(values.get("readTimeoutMs"));
        return s;
    }

    /** 不含 bindPassword */
    public Map<String, String> toMapWithoutPassword() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("enabled", String.valueOf(enabled));
        m.put("url", nullToEmpty(url));
        m.put("startTls", String.valueOf(startTls));
        m.put("skipCertVerify", String.valueOf(skipCertVerify));
        m.put("bindDn", nullToEmpty(bindDn));
        m.put("baseDn", nullToEmpty(baseDn));
        m.put("userFilter", nullToEmpty(userFilter));
        m.put("usernameAttribute", nullToEmpty(usernameAttribute));
        m.put("nameAttribute", nullToEmpty(nameAttribute));
        m.put("emailAttribute", nullToEmpty(emailAttribute));
        m.put("defaultRole", nullToEmpty(defaultRole));
        m.put("connectTimeoutMs", String.valueOf(connectTimeoutMs));
        m.put("readTimeoutMs", String.valueOf(readTimeoutMs));
        return m;
    }

    /**
     * 校验能否用于连接；返回错误说明，没问题返回 null
     */
    public String validate() {
        if (!StringUtils.hasText(url)) {
            return "请填写 LDAP 服务器地址";
        }
        String lower = url.trim().toLowerCase();
        if (!lower.startsWith("ldap://") && !lower.startsWith("ldaps://")) {
            return "LDAP 服务器地址必须以 ldap:// 或 ldaps:// 开头";
        }
        if (url.trim().contains(" ")) {
            return "LDAP 服务器地址只能填一个，不能含空格";
        }
        if (startTls && lower.startsWith("ldaps://")) {
            return "ldaps:// 已经是加密连接，不能再开启 StartTLS";
        }
        if (!StringUtils.hasText(baseDn)) {
            return "请填写用户搜索的 Base DN";
        }
        if (!StringUtils.hasText(userFilter) || !userFilter.contains("{0}")) {
            return "用户过滤条件必须包含 {0}（代表登录用户名），例如 (uid={0})";
        }
        if (!userFilter.trim().startsWith("(") || !userFilter.trim().endsWith(")")) {
            return "用户过滤条件必须用括号包起来，例如 (uid={0})";
        }
        if (StringUtils.hasText(bindDn) && !StringUtils.hasText(bindPassword)) {
            return "填写了绑定账号 DN 时必须填写绑定密码";
        }
        if (!StringUtils.hasText(usernameAttribute)) {
            return "请填写用户名属性";
        }
        return null;
    }

    public boolean isLdaps() {
        return url != null && url.trim().toLowerCase().startsWith("ldaps://");
    }

    /** 不带密码的描述，便于日志 */
    @Override
    public String toString() {
        return "LdapSettings{enabled=" + enabled + ", url=" + url + ", startTls=" + startTls
                + ", skipCertVerify=" + skipCertVerify + ", bindDn=" + bindDn + ", baseDn=" + baseDn
                + ", userFilter=" + userFilter + "}";
    }

    private static int parseTimeout(String v) {
        try {
            int n = Integer.parseInt(trim(v));
            return Math.max(MIN_TIMEOUT_MS, Math.min(MAX_TIMEOUT_MS, n));
        } catch (Exception e) {
            return DEFAULT_TIMEOUT_MS;
        }
    }

    private static String orDefault(String v, String def) {
        String t = trim(v);
        return StringUtils.hasText(t) ? t : def;
    }

    private static String trim(String v) {
        return v == null ? null : v.trim();
    }

    private static String nullToEmpty(String v) {
        return v == null ? "" : v;
    }
}
