package com.opsflow.integration.ldap;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LDAP 认证：对着内嵌 LDAP 服务器跑真实的绑定和搜索。
 */
class LdapAuthenticatorTest {

    private static EmbeddedLdap ldap;
    private final LdapAuthenticator authenticator = new LdapAuthenticator();

    @BeforeAll
    static void start() throws Exception {
        ldap = new EmbeddedLdap();
    }

    @AfterAll
    static void stop() throws Exception {
        ldap.close();
    }

    private LdapAuthException.Reason failure(LdapSettings s, String user, String password) {
        return assertThrows(LdapAuthException.class, () -> authenticator.authenticate(s, user, password)).getReason();
    }

    @Test
    void validCredentialsReturnUserAttributes() throws Exception {
        LdapUserInfo u = authenticator.authenticate(ldap.settings(), "alice", EmbeddedLdap.ALICE_PASSWORD);

        assertEquals("uid=alice,ou=people,dc=example,dc=com", u.getDn().toLowerCase());
        assertEquals("alice", u.getUsername());
        assertEquals("Alice Liu", u.getRealName());
        assertEquals("alice@example.com", u.getEmail());
    }

    @Test
    void wrongPasswordIsBadCredentials() {
        assertEquals(LdapAuthException.Reason.BAD_CREDENTIALS, failure(ldap.settings(), "alice", "wrong"));
    }

    @Test
    void unknownUserIsBadCredentials() {
        assertEquals(LdapAuthException.Reason.BAD_CREDENTIALS, failure(ldap.settings(), "nobody", "x"));
    }

    @Test
    void emptyPasswordIsRejectedBeforeContactingServer() {
        // 内嵌服务器和很多真实服务器一样：有 DN、空密码的绑定会被当成匿名绑定并返回成功
        ldap.clearAccessLog();
        assertEquals(LdapAuthException.Reason.BAD_CREDENTIALS, failure(ldap.settings(), "alice", ""));
        assertEquals(LdapAuthException.Reason.BAD_CREDENTIALS, failure(ldap.settings(), "alice", null));
        assertTrue(ldap.events().isEmpty(), "不应向服务器发起任何绑定: " + ldap.events());
    }

    @Test
    void filterInjectionDoesNotMatchOtherUsers() {
        // 不转义的话 (uid=*) 会命中 alice 等用户，用 alice 的密码就能登录
        for (String evil : new String[]{"*", "al*", "*)(uid=*", "alice)(|(uid=*", "*)(objectClass=*", "\\2a", "ali\u0000ce", "ali\nce"}) {
            LdapAuthException e = assertThrows(LdapAuthException.class,
                    () -> authenticator.authenticate(ldap.settings(), evil, EmbeddedLdap.ALICE_PASSWORD), evil);
            assertEquals(LdapAuthException.Reason.BAD_CREDENTIALS, e.getReason(), evil);
        }
    }

    @Test
    void usernameWithFilterSpecialCharactersStillWorks() throws Exception {
        LdapUserInfo u = authenticator.authenticate(ldap.settings(), EmbeddedLdap.BOB_UID, EmbeddedLdap.BOB_PASSWORD);
        assertEquals(EmbeddedLdap.BOB_UID, u.getUsername());
    }

    @Test
    void overlongUsernameIsRejected() {
        String longName = "a".repeat(LdapAuthenticator.MAX_USERNAME_LENGTH + 1);
        assertEquals(LdapAuthException.Reason.BAD_CREDENTIALS, failure(ldap.settings(), longName, "x"));
    }

    @Test
    void filterMatchingSeveralEntriesIsConfigError() {
        assertEquals(LdapAuthException.Reason.CONFIG, failure(ldap.settings(), "dup", "dup-pass"));
    }

    @Test
    void wrongBindPasswordIsConfigError() {
        LdapSettings s = ldap.settings();
        s.setBindPassword("wrong");
        LdapAuthException e = assertThrows(LdapAuthException.class,
                () -> authenticator.authenticate(s, "alice", EmbeddedLdap.ALICE_PASSWORD));
        assertEquals(LdapAuthException.Reason.CONFIG, e.getReason());
        assertTrue(e.getMessage().contains("绑定账号"));
        assertFalse(e.getMessage().contains("wrong"));
    }

    @Test
    void bindDnWithoutPasswordIsRejected() {
        LdapSettings s = ldap.settings();
        s.setBindPassword(null);
        assertEquals(LdapAuthException.Reason.CONFIG, failure(s, "alice", EmbeddedLdap.ALICE_PASSWORD));
    }

    @Test
    void anonymousSearchIsUsedWhenNoBindDn() {
        // 内嵌服务器要求认证后才能搜索，所以匿名搜索失败 → 配置问题
        LdapSettings s = ldap.settings();
        s.setBindDn(null);
        s.setBindPassword(null);
        assertEquals(LdapAuthException.Reason.CONFIG, failure(s, "alice", EmbeddedLdap.ALICE_PASSWORD));
    }

    @Test
    void unreachableServerIsUnavailable() throws Exception {
        int port;
        try (ServerSocket s = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            port = s.getLocalPort();
        }
        LdapSettings s = ldap.settings();
        s.setUrl("ldap://127.0.0.1:" + port);
        assertEquals(LdapAuthException.Reason.UNAVAILABLE, failure(s, "alice", EmbeddedLdap.ALICE_PASSWORD));
    }

    @Test
    void silentServerTimesOutQuickly() throws Exception {
        // 端口能连上但从不回应：靠读取超时返回
        try (ServerSocket silent = new ServerSocket(0, 5, InetAddress.getLoopbackAddress())) {
            LdapSettings s = ldap.settings();
            s.setUrl("ldap://127.0.0.1:" + silent.getLocalPort());
            s.setConnectTimeoutMs(500);
            s.setReadTimeoutMs(800);
            long start = System.nanoTime();
            assertEquals(LdapAuthException.Reason.UNAVAILABLE, failure(s, "alice", EmbeddedLdap.ALICE_PASSWORD));
            long ms = (System.nanoTime() - start) / 1_000_000;
            assertTrue(ms < 5000, "应在超时后很快返回，实际 " + ms + "ms");
        }
    }

    @Test
    void ldapsWithSelfSignedCertificateIsRejectedByDefault() {
        LdapSettings s = ldap.settings();
        s.setUrl(ldap.ldapsUrl());
        assertEquals(LdapAuthException.Reason.UNAVAILABLE, failure(s, "alice", EmbeddedLdap.ALICE_PASSWORD));
    }

    @Test
    void ldapsWorksWhenCertificateCheckIsSkipped() throws Exception {
        LdapSettings s = ldap.settings();
        s.setUrl(ldap.ldapsUrl());
        s.setSkipCertVerify(true);
        assertEquals("alice", authenticator.authenticate(s, "alice", EmbeddedLdap.ALICE_PASSWORD).getUsername());
    }

    @Test
    void startTlsWithSelfSignedCertificateIsRejectedByDefault() {
        LdapSettings s = ldap.settings();
        s.setStartTls(true);
        assertEquals(LdapAuthException.Reason.UNAVAILABLE, failure(s, "alice", EmbeddedLdap.ALICE_PASSWORD));
    }

    @Test
    void startTlsNeverSendsPasswordsInPlainText() throws Exception {
        try (RecordingTcpProxy proxy = new RecordingTcpProxy(ldap.ldapPort())) {
            LdapSettings s = ldap.settings();
            s.setUrl("ldap://localhost:" + proxy.port());
            s.setStartTls(true);
            s.setSkipCertVerify(true);

            assertEquals("alice", authenticator.authenticate(s, "alice", EmbeddedLdap.ALICE_PASSWORD).getUsername());

            String wire = proxy.clientBytes();
            assertTrue(wire.contains(EmbeddedLdap.startTlsOid()), "应先发 StartTLS 请求");
            assertFalse(wire.contains(EmbeddedLdap.ALICE_PASSWORD), "登录密码以明文出现在网络上");
            assertFalse(wire.contains(EmbeddedLdap.READER_PASSWORD), "绑定密码以明文出现在网络上");
        }
    }

    @Test
    void plainLdapDoesSendPasswordsInPlainText() throws Exception {
        // 对照组：说明上面的抓包检查确实能发现明文密码
        try (RecordingTcpProxy proxy = new RecordingTcpProxy(ldap.ldapPort())) {
            LdapSettings s = ldap.settings();
            s.setUrl("ldap://localhost:" + proxy.port());

            authenticator.authenticate(s, "alice", EmbeddedLdap.ALICE_PASSWORD);

            String wire = proxy.clientBytes();
            assertFalse(wire.contains(EmbeddedLdap.startTlsOid()));
            assertTrue(wire.contains(EmbeddedLdap.ALICE_PASSWORD));
            assertTrue(wire.contains(EmbeddedLdap.READER_PASSWORD));
        }
    }

    @Test
    void ldapsNeverSendsPasswordsInPlainText() throws Exception {
        try (RecordingTcpProxy proxy = new RecordingTcpProxy(ldap.ldapsPort())) {
            LdapSettings s = ldap.settings();
            s.setUrl("ldaps://localhost:" + proxy.port());
            s.setSkipCertVerify(true);

            authenticator.authenticate(s, "alice", EmbeddedLdap.ALICE_PASSWORD);

            String wire = proxy.clientBytes();
            assertFalse(wire.isEmpty());
            assertFalse(wire.contains(EmbeddedLdap.ALICE_PASSWORD));
            assertFalse(wire.contains(EmbeddedLdap.READER_PASSWORD));
        }
    }

    @Test
    void testConnectionReportsBindAndBaseDn() throws Exception {
        String msg = authenticator.testConnection(ldap.settings());
        assertTrue(msg.contains("连接成功"), msg);
    }

    @Test
    void invalidSettingsAreConfigErrors() {
        LdapSettings s = ldap.settings();
        s.setUrl("http://example.com");
        assertEquals(LdapAuthException.Reason.CONFIG, failure(s, "alice", "x"));
        s = ldap.settings();
        s.setUserFilter("(uid=alice)");
        assertEquals(LdapAuthException.Reason.CONFIG, failure(s, "alice", "x"));
        s = ldap.settings();
        s.setUrl(ldap.ldapsUrl());
        s.setStartTls(true);
        assertEquals(LdapAuthException.Reason.CONFIG, failure(s, "alice", "x"));
    }
}
