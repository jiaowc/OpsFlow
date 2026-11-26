package com.opsflow.integration.ldap;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import javax.naming.AuthenticationException;
import javax.naming.CommunicationException;
import javax.naming.Context;
import javax.naming.NamingEnumeration;
import javax.naming.NamingException;
import javax.naming.PartialResultException;
import javax.naming.ServiceUnavailableException;
import javax.naming.SizeLimitExceededException;
import javax.naming.directory.Attribute;
import javax.naming.directory.Attributes;
import javax.naming.directory.SearchControls;
import javax.naming.directory.SearchResult;
import javax.naming.ldap.InitialLdapContext;
import javax.naming.ldap.LdapContext;
import javax.naming.ldap.StartTlsRequest;
import javax.naming.ldap.StartTlsResponse;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Hashtable;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 基于 JDK 自带 JNDI 的 LDAP 认证（不引入第三方库）。
 * <p>流程：用绑定账号（或匿名）搜索用户，必须恰好命中 1 个；再用该用户 DN 和登录密码做一次简单绑定，成功即认证通过。
 * <p>安全约束：
 * <ul>
 *   <li>空密码直接拒绝（很多服务器把空密码绑定当匿名绑定并返回成功）；</li>
 *   <li>用户名只作为 JNDI 过滤参数 {0} 传入，由 JNDI 按 RFC 4515 转义，不做字符串拼接；</li>
 *   <li>连接和读取都有超时；每次认证都用新连接，不复用、不进连接池；</li>
 *   <li>异常消息和日志里不出现任何密码。</li>
 * </ul>
 */
@Slf4j
@Component
public class LdapAuthenticator {

    /** 与 user.username 列长度一致 */
    public static final int MAX_USERNAME_LENGTH = 50;

    /**
     * 认证登录用户
     */
    public LdapUserInfo authenticate(LdapSettings settings, String username, String password) throws LdapAuthException {
        if (password == null || password.isEmpty()) {
            throw new LdapAuthException(LdapAuthException.Reason.BAD_CREDENTIALS, "密码不能为空");
        }
        String name = checkUsername(username);
        requireValid(settings);

        LdapUserInfo user = findUser(settings, name);
        bind(settings, user.getDn(), password, true).closeQuietly();
        return user;
    }

    /**
     * 测试连接：用绑定账号（或匿名）连上并读取 Base DN。
     *
     * @return 结果说明
     */
    public String testConnection(LdapSettings settings) throws LdapAuthException {
        requireValid(settings);
        Session session = bindService(settings);
        try {
            session.ctx.getAttributes(settings.getBaseDn(), new String[]{"objectClass"});
            return "连接成功：" + (StringUtils.hasText(settings.getBindDn()) ? "绑定账号认证通过" : "匿名连接成功")
                    + "，可以读取 Base DN" + (settings.isStartTls() ? "（已启用 StartTLS）" : "");
        } catch (NamingException e) {
            throw translate(e, "读取 Base DN 失败");
        } finally {
            session.closeQuietly();
        }
    }

    /**
     * 按用户名搜索用户（不验证密码）
     */
    public LdapUserInfo findUser(LdapSettings settings, String username) throws LdapAuthException {
        String name = checkUsername(username);
        requireValid(settings);
        Session session = bindService(settings);
        try {
            SearchControls controls = new SearchControls();
            controls.setSearchScope(SearchControls.SUBTREE_SCOPE);
            controls.setCountLimit(2);
            controls.setTimeLimit(settings.getReadTimeoutMs());
            controls.setReturningAttributes(returningAttributes(settings));
            List<SearchResult> results = new ArrayList<>();
            NamingEnumeration<SearchResult> it = session.ctx.search(
                    settings.getBaseDn(), settings.getUserFilter(), new Object[]{name}, controls);
            try {
                while (it.hasMore()) {
                    results.add(it.next());
                }
            } catch (SizeLimitExceededException e) {
                results.add(null);
            } catch (PartialResultException e) {
                // AD 的引用（referral）被忽略时会在末尾抛这个，已收到的结果有效
            } finally {
                it.close();
            }
            if (results.isEmpty()) {
                throw new LdapAuthException(LdapAuthException.Reason.BAD_CREDENTIALS, "LDAP 中没有找到该用户");
            }
            if (results.size() > 1) {
                throw new LdapAuthException(LdapAuthException.Reason.CONFIG,
                        "用户过滤条件匹配到多个条目，请收紧过滤条件");
            }
            SearchResult r = results.get(0);
            Attributes attrs = r.getAttributes();
            String uid = attr(attrs, settings.getUsernameAttribute());
            return new LdapUserInfo(r.getNameInNamespace(),
                    StringUtils.hasText(uid) ? uid : name,
                    attr(attrs, settings.getNameAttribute()),
                    attr(attrs, settings.getEmailAttribute()));
        } catch (NamingException e) {
            throw translate(e, "搜索用户失败");
        } finally {
            session.closeQuietly();
        }
    }

    private Session bindService(LdapSettings settings) throws LdapAuthException {
        if (StringUtils.hasText(settings.getBindDn())) {
            return bind(settings, settings.getBindDn(), settings.getBindPassword(), false);
        }
        return bind(settings, null, null, false);
    }

    /**
     * 新建连接并绑定。dn 为空表示匿名。
     *
     * @param userBind true 表示在验证登录用户的密码，认证失败归为「用户名或密码错误」
     */
    private Session bind(LdapSettings settings, String dn, String password, boolean userBind) throws LdapAuthException {
        boolean anonymous = !StringUtils.hasText(dn);
        if (!anonymous && (password == null || password.isEmpty())) {
            // 防止「有 DN、空密码」被服务器当作匿名绑定放行
            throw new LdapAuthException(userBind ? LdapAuthException.Reason.BAD_CREDENTIALS
                    : LdapAuthException.Reason.CONFIG, userBind ? "密码不能为空" : "绑定密码不能为空");
        }
        Hashtable<String, Object> env = baseEnv(settings);
        if (!settings.isStartTls()) {
            if (anonymous) {
                env.put(Context.SECURITY_AUTHENTICATION, "none");
            } else {
                env.put(Context.SECURITY_AUTHENTICATION, "simple");
                env.put(Context.SECURITY_PRINCIPAL, dn);
                env.put(Context.SECURITY_CREDENTIALS, password);
            }
            try {
                return new Session(new InitialLdapContext(env, null), null);
            } catch (NamingException e) {
                throw translateBind(e, userBind);
            }
        }

        // StartTLS：先以匿名身份建立明文连接，升级为 TLS 后再在同一连接上绑定，密码只走加密通道
        env.put(Context.SECURITY_AUTHENTICATION, "none");
        LdapContext ctx;
        try {
            ctx = new InitialLdapContext(env, null);
        } catch (NamingException e) {
            throw translate(e, "连接 LDAP 服务器失败");
        }
        StartTlsResponse tls;
        try {
            tls = (StartTlsResponse) ctx.extendedOperation(new StartTlsRequest());
            if (settings.isSkipCertVerify()) {
                tls.setHostnameVerifier((host, session) -> true);
                tls.negotiate(TrustAllSslSocketFactory.instance());
            } else {
                tls.negotiate();
            }
        } catch (NamingException | IOException e) {
            closeQuietly(ctx, null);
            throw new LdapAuthException(LdapAuthException.Reason.UNAVAILABLE,
                    "StartTLS 协商失败：" + safeMessage(e), e);
        }
        Session session = new Session(ctx, tls);
        if (anonymous) {
            return session;
        }
        try {
            ctx.addToEnvironment(Context.SECURITY_AUTHENTICATION, "simple");
            ctx.addToEnvironment(Context.SECURITY_PRINCIPAL, dn);
            ctx.addToEnvironment(Context.SECURITY_CREDENTIALS, password);
            // 修改认证信息后，JNDI 在下一次操作前于当前（已加密的）连接上重新绑定
            ctx.getAttributes("", new String[]{"1.1"});
            return session;
        } catch (NamingException e) {
            session.closeQuietly();
            throw translateBind(e, userBind);
        }
    }

    private Hashtable<String, Object> baseEnv(LdapSettings settings) {
        Hashtable<String, Object> env = new Hashtable<>();
        env.put(Context.INITIAL_CONTEXT_FACTORY, "com.sun.jndi.ldap.LdapCtxFactory");
        env.put(Context.PROVIDER_URL, settings.getUrl().trim());
        env.put(Context.REFERRAL, "ignore");
        env.put("com.sun.jndi.ldap.connect.timeout", String.valueOf(settings.getConnectTimeoutMs()));
        env.put("com.sun.jndi.ldap.read.timeout", String.valueOf(settings.getReadTimeoutMs()));
        env.put("com.sun.jndi.ldap.connect.pool", "false");
        if (settings.isLdaps() && settings.isSkipCertVerify()) {
            env.put("java.naming.ldap.factory.socket", TrustAllSslSocketFactory.class.getName());
        }
        return env;
    }

    private static String checkUsername(String username) throws LdapAuthException {
        String name = username == null ? "" : username.trim();
        if (name.isEmpty()) {
            throw new LdapAuthException(LdapAuthException.Reason.BAD_CREDENTIALS, "用户名不能为空");
        }
        if (name.length() > MAX_USERNAME_LENGTH) {
            throw new LdapAuthException(LdapAuthException.Reason.BAD_CREDENTIALS, "用户名过长");
        }
        for (int i = 0; i < name.length(); i++) {
            if (Character.isISOControl(name.charAt(i))) {
                throw new LdapAuthException(LdapAuthException.Reason.BAD_CREDENTIALS, "用户名含非法字符");
            }
        }
        return name;
    }

    private static void requireValid(LdapSettings settings) throws LdapAuthException {
        if (settings == null) {
            throw new LdapAuthException(LdapAuthException.Reason.CONFIG, "未配置 LDAP");
        }
        String err = settings.validate();
        if (err != null) {
            throw new LdapAuthException(LdapAuthException.Reason.CONFIG, err);
        }
    }

    private static String[] returningAttributes(LdapSettings s) {
        Set<String> set = new LinkedHashSet<>();
        for (String a : new String[]{s.getUsernameAttribute(), s.getNameAttribute(), s.getEmailAttribute()}) {
            if (StringUtils.hasText(a)) {
                set.add(a.trim());
            }
        }
        return set.toArray(new String[0]);
    }

    private static String attr(Attributes attrs, String name) throws NamingException {
        if (attrs == null || !StringUtils.hasText(name)) {
            return null;
        }
        Attribute a = attrs.get(name.trim());
        if (a == null || a.size() == 0) {
            return null;
        }
        Object v = a.get();
        return v instanceof String ? ((String) v).trim() : null;
    }

    private static LdapAuthException translateBind(NamingException e, boolean userBind) {
        if (e instanceof AuthenticationException) {
            return userBind
                    ? new LdapAuthException(LdapAuthException.Reason.BAD_CREDENTIALS, "LDAP 密码错误")
                    : new LdapAuthException(LdapAuthException.Reason.CONFIG, "绑定账号认证失败，请检查绑定 DN 和密码");
        }
        return translate(e, "连接 LDAP 服务器失败");
    }

    private static LdapAuthException translate(NamingException e, String what) {
        if (e instanceof CommunicationException || e instanceof ServiceUnavailableException
                || isTimeout(e)) {
            return new LdapAuthException(LdapAuthException.Reason.UNAVAILABLE,
                    what + "：服务器不可达或超时（" + safeMessage(e) + "）", e);
        }
        if (e instanceof AuthenticationException) {
            return new LdapAuthException(LdapAuthException.Reason.CONFIG, "绑定账号认证失败，请检查绑定 DN 和密码", e);
        }
        return new LdapAuthException(LdapAuthException.Reason.CONFIG, what + "：" + safeMessage(e), e);
    }

    private static boolean isTimeout(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof java.net.SocketTimeoutException || t instanceof javax.naming.TimeLimitExceededException) {
                return true;
            }
            String m = t.getMessage();
            if (m != null && m.toLowerCase().contains("timed out")) {
                return true;
            }
        }
        return false;
    }

    /** JNDI 的异常消息里不含密码；这里只取简短说明，并限制长度 */
    private static String safeMessage(Throwable e) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String m = e.getMessage() != null ? e.getMessage() : root.getClass().getSimpleName();
        if (root != e && root.getMessage() != null && !m.contains(root.getMessage())) {
            m = m + "; " + root.getMessage();
        }
        return m.length() > 300 ? m.substring(0, 300) + "..." : m;
    }

    private static void closeQuietly(LdapContext ctx, StartTlsResponse tls) {
        if (tls != null) {
            try {
                tls.close();
            } catch (Exception ignored) {
                // ignore
            }
        }
        if (ctx != null) {
            try {
                ctx.close();
            } catch (Exception ignored) {
                // ignore
            }
        }
    }

    private static final class Session {
        final LdapContext ctx;
        final StartTlsResponse tls;

        Session(LdapContext ctx, StartTlsResponse tls) {
            this.ctx = ctx;
            this.tls = tls;
        }

        void closeQuietly() {
            LdapAuthenticator.closeQuietly(ctx, tls);
        }
    }
}
