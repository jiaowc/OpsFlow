package com.opsflow.integration.ldap;

import com.unboundid.ldap.listener.InMemoryDirectoryServer;
import com.unboundid.ldap.listener.InMemoryDirectoryServerConfig;
import com.unboundid.ldap.listener.InMemoryListenerConfig;
import com.unboundid.ldap.listener.interceptor.InMemoryInterceptedExtendedRequest;
import com.unboundid.ldap.listener.interceptor.InMemoryInterceptedSimpleBindRequest;
import com.unboundid.ldap.listener.interceptor.InMemoryOperationInterceptor;
import com.unboundid.ldap.sdk.OperationType;
import com.unboundid.util.ssl.KeyStoreKeyManager;
import com.unboundid.util.ssl.SSLUtil;
import com.unboundid.util.ssl.TrustAllTrustManager;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.logging.Handler;
import java.util.logging.LogRecord;

/**
 * 测试用内嵌 LDAP 服务器（UnboundID，仅 test 作用域）。
 * <p>三个监听：明文 ldap（支持 StartTLS）、ldaps；证书是测试时用 keytool 现场生成的自签证书，用完删除。
 * <p>目录内容：
 * <pre>
 * dc=example,dc=com
 *   cn=reader          绑定账号，密码 reader-pass
 *   ou=people
 *     uid=alice        Alice Liu / alice@example.com，密码 alice-pass
 *     uid=bob(dev)*    含过滤条件特殊字符的合法用户名，密码 bob-pass
 *   ou=a / ou=b
 *     uid=dup          两个同名条目，用来测「匹配到多个」
 * </pre>
 * 搜索要求先认证（匿名不能搜），便于验证绑定账号确实生效。
 */
final class EmbeddedLdap implements AutoCloseable {

    static final String BASE_DN = "dc=example,dc=com";
    static final String PEOPLE_DN = "ou=people," + BASE_DN;
    static final String READER_DN = "cn=reader," + BASE_DN;
    static final String READER_PASSWORD = "reader-pass";
    static final String ALICE_PASSWORD = "alice-pass";
    static final String BOB_UID = "bob(dev)*";
    static final String BOB_PASSWORD = "bob-pass";

    private static final String STARTTLS_OID = "1.3.6.1.4.1.1466.20037";

    final InMemoryDirectoryServer server;
    private final Path keystoreDir;
    private final List<String> accessLog = Collections.synchronizedList(new ArrayList<>());
    /** 服务器端看到的「StartTLS」和「简单绑定」，格式 STARTTLS conn=N / BIND conn=N dn=... */
    private final List<String> events = Collections.synchronizedList(new ArrayList<>());

    EmbeddedLdap() throws Exception {
        keystoreDir = Files.createTempDirectory("opsflow-ldap-test");
        File keystore = keystoreDir.resolve("server.p12").toFile();
        generateSelfSignedKeystore(keystore);

        SSLUtil serverSsl = new SSLUtil(
                new KeyStoreKeyManager(keystore, "changeit".toCharArray(), "PKCS12", "server"),
                new TrustAllTrustManager());
        SSLUtil clientSsl = new SSLUtil(new TrustAllTrustManager());

        InMemoryDirectoryServerConfig cfg = new InMemoryDirectoryServerConfig(BASE_DN);
        cfg.setSchema(null);
        cfg.setAuthenticationRequiredOperationTypes(EnumSet.of(OperationType.SEARCH, OperationType.COMPARE));
        cfg.setListenerConfigs(
                InMemoryListenerConfig.createLDAPConfig("ldap", null, 0, serverSsl.createSSLSocketFactory()),
                InMemoryListenerConfig.createLDAPSConfig("ldaps", null, 0,
                        serverSsl.createSSLServerSocketFactory(), clientSsl.createSSLSocketFactory()));
        cfg.setAccessLogHandler(new Handler() {
            @Override
            public void publish(LogRecord record) {
                accessLog.add(record.getMessage());
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        });

        cfg.addInMemoryOperationInterceptor(new InMemoryOperationInterceptor() {
            @Override
            public void processExtendedRequest(InMemoryInterceptedExtendedRequest request) {
                if (STARTTLS_OID.equals(request.getRequest().getOID())) {
                    events.add("STARTTLS conn=" + request.getConnectionID());
                }
            }

            @Override
            public void processSimpleBindRequest(InMemoryInterceptedSimpleBindRequest request) {
                events.add("BIND conn=" + request.getConnectionID() + " dn=" + request.getRequest().getBindDN());
            }
        });

        server = new InMemoryDirectoryServer(cfg);
        server.add("dn: " + BASE_DN, "objectClass: top", "objectClass: domain", "dc: example");
        server.add("dn: " + READER_DN, "objectClass: person", "cn: reader", "sn: reader",
                "userPassword: " + READER_PASSWORD);
        server.add("dn: " + PEOPLE_DN, "objectClass: organizationalUnit", "ou: people");
        server.add("dn: uid=alice," + PEOPLE_DN, "objectClass: inetOrgPerson", "uid: alice",
                "cn: Alice Liu", "sn: Liu", "mail: alice@example.com", "userPassword: " + ALICE_PASSWORD);
        server.add("dn: uid=bob\\28dev\\29\\2A," + PEOPLE_DN, "objectClass: inetOrgPerson", "uid: " + BOB_UID,
                "cn: Bob", "sn: B", "userPassword: " + BOB_PASSWORD);
        server.add("dn: ou=a," + BASE_DN, "objectClass: organizationalUnit", "ou: a");
        server.add("dn: ou=b," + BASE_DN, "objectClass: organizationalUnit", "ou: b");
        server.add("dn: uid=dup,ou=a," + BASE_DN, "objectClass: inetOrgPerson", "uid: dup", "cn: Dup A", "sn: A",
                "userPassword: dup-pass");
        server.add("dn: uid=dup,ou=b," + BASE_DN, "objectClass: inetOrgPerson", "uid: dup", "cn: Dup B", "sn: B",
                "userPassword: dup-pass");
        server.startListening();
    }

    int ldapPort() {
        return server.getListenPort("ldap");
    }

    int ldapsPort() {
        return server.getListenPort("ldaps");
    }

    String ldapUrl() {
        return "ldap://localhost:" + ldapPort();
    }

    String ldapsUrl() {
        return "ldaps://localhost:" + ldapsPort();
    }

    /** 可用的基础配置：明文 ldap、绑定账号 reader、搜索 ou=people */
    LdapSettings settings() {
        LdapSettings s = new LdapSettings();
        s.setEnabled(true);
        s.setUrl(ldapUrl());
        s.setBindDn(READER_DN);
        s.setBindPassword(READER_PASSWORD);
        s.setBaseDn(BASE_DN);
        s.setUserFilter("(uid={0})");
        s.setConnectTimeoutMs(2000);
        s.setReadTimeoutMs(2000);
        return s;
    }

    List<String> accessLog() {
        synchronized (accessLog) {
            return new ArrayList<>(accessLog);
        }
    }

    void clearAccessLog() {
        accessLog.clear();
        events.clear();
    }

    List<String> events() {
        synchronized (events) {
            return new ArrayList<>(events);
        }
    }

    static String startTlsOid() {
        return STARTTLS_OID;
    }

    @Override
    public void close() throws Exception {
        server.shutDown(true);
        File[] files = keystoreDir.toFile().listFiles();
        if (files != null) {
            for (File f : files) {
                Files.deleteIfExists(f.toPath());
            }
        }
        Files.deleteIfExists(keystoreDir);
    }

    private static void generateSelfSignedKeystore(File keystore) throws Exception {
        String keytool = System.getProperty("java.home") + File.separator + "bin" + File.separator + "keytool";
        Process p = new ProcessBuilder(keytool, "-genkeypair", "-alias", "server", "-keyalg", "RSA",
                "-keysize", "2048", "-dname", "CN=localhost", "-ext", "SAN=dns:localhost,ip:127.0.0.1",
                "-validity", "2", "-keystore", keystore.getAbsolutePath(), "-storetype", "PKCS12",
                "-storepass", "changeit", "-keypass", "changeit")
                .redirectErrorStream(true).start();
        byte[] out = p.getInputStream().readAllBytes();
        if (!p.waitFor(60, TimeUnit.SECONDS) || p.exitValue() != 0) {
            throw new IllegalStateException("keytool 生成测试证书失败: " + new String(out));
        }
    }
}
