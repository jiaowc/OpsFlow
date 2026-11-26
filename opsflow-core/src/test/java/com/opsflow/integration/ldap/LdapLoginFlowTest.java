package com.opsflow.integration.ldap;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.opsflow.common.crypto.SecretCipher;
import com.opsflow.common.util.PasswordHasher;
import com.opsflow.dao.mapper.RoleMapper;
import com.opsflow.dao.mapper.UserMapper;
import com.opsflow.dao.mapper.UserRoleMapper;
import com.opsflow.dao.model.Role;
import com.opsflow.dao.model.User;
import com.opsflow.dao.model.UserRole;
import com.opsflow.service.PermissionService;
import com.opsflow.service.SystemConfigService;
import com.opsflow.web.controller.AuthController;
import com.opsflow.web.security.LoginSessionBinder;
import com.opsflow.web.security.LoginAttemptGuard;
import com.opsflow.web.security.SessionPermissionResolver;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 登录页走 LDAP 的完整流程：AuthController.login + LdapLoginService + 内嵌 LDAP 服务器。
 * 用户表、角色表用内存替身。
 */
class LdapLoginFlowTest {

    private static EmbeddedLdap ldap;

    private AuthController controller;
    private LdapConfigService configService;
    private InMemoryConfig config;
    private final List<User> users = new ArrayList<>();
    private final AtomicLong ids = new AtomicLong(100);
    private UserRoleMapper userRoleMapper;
    private RoleMapper roleMapper;
    private LoginAttemptGuard guard;

    @BeforeAll
    static void start() throws Exception {
        ldap = new EmbeddedLdap();
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        ReflectionTestUtils.invokeMethod(SecretCipher.class, "useKeyForTest", (Object) key);
    }

    @AfterAll
    static void stop() throws Exception {
        ReflectionTestUtils.invokeMethod(SecretCipher.class, "useKeyForTest", (Object) null);
        ldap.close();
    }

    @BeforeEach
    void setUp() {
        config = new InMemoryConfig();
        configService = new LdapConfigService();
        ReflectionTestUtils.setField(configService, "systemConfigService", config);

        UserMapper userMapper = mock(UserMapper.class);
        when(userMapper.selectOne(any())).thenAnswer(inv -> findUser(inv.getArgument(0)));
        when(userMapper.insert(any(User.class))).thenAnswer(inv -> {
            User u = inv.getArgument(0);
            u.setId(ids.incrementAndGet());
            users.add(u);
            return 1;
        });
        when(userMapper.updateById(any(User.class))).thenReturn(1);

        roleMapper = mock(RoleMapper.class);
        Role viewer = new Role();
        viewer.setId(4L);
        viewer.setCode("VIEWER");
        viewer.setStatus(1);
        when(roleMapper.selectOne(any())).thenReturn(viewer);
        userRoleMapper = mock(UserRoleMapper.class);

        LdapLoginService loginService = new LdapLoginService();
        ReflectionTestUtils.setField(loginService, "ldapConfigService", configService);
        ReflectionTestUtils.setField(loginService, "ldapAuthenticator", new LdapAuthenticator());
        ReflectionTestUtils.setField(loginService, "userMapper", userMapper);
        ReflectionTestUtils.setField(loginService, "roleMapper", roleMapper);
        ReflectionTestUtils.setField(loginService, "userRoleMapper", userRoleMapper);

        PermissionService permissionService = mock(PermissionService.class);
        when(permissionService.listRoleCodesByUserId(anyLong())).thenReturn(Collections.emptyList());
        when(permissionService.listPermissionCodesByUserId(anyLong())).thenReturn(Collections.emptyList());
        guard = new LoginAttemptGuard();

        controller = new AuthController();
        ReflectionTestUtils.setField(controller, "userMapper", userMapper);
        ReflectionTestUtils.setField(controller, "permissionService", permissionService);
        ReflectionTestUtils.setField(controller, "loginAttemptGuard", guard);
        ReflectionTestUtils.setField(controller, "sessionPermissionResolver", mock(SessionPermissionResolver.class));
        LoginSessionBinder binder = new LoginSessionBinder();
        ReflectionTestUtils.setField(binder, "sessionPermissionResolver", mock(SessionPermissionResolver.class));
        ReflectionTestUtils.setField(controller, "loginSessionBinder", binder);
        // 两步验证策略默认关闭，不影响这里的 LDAP 登录流程
        ReflectionTestUtils.setField(controller, "twoFactorService", new com.opsflow.service.impl.TwoFactorTestSupport().service);
        ReflectionTestUtils.setField(controller, "ldapLoginService", loginService);

        enableLdap(ldap.ldapUrl());
    }

    private void enableLdap(String url) {
        Map<String, Object> form = new HashMap<>();
        form.put("enabled", true);
        form.put("url", url);
        form.put("bindDn", EmbeddedLdap.READER_DN);
        form.put("bindPassword", EmbeddedLdap.READER_PASSWORD);
        form.put("baseDn", EmbeddedLdap.BASE_DN);
        form.put("userFilter", "(uid={0})");
        form.put("defaultRole", "VIEWER");
        form.put("connectTimeoutMs", 1000);
        form.put("readTimeoutMs", 1000);
        configService.save(form);
    }

    private User findUser(Wrapper<User> wrapper) {
        // QueryWrapper 的条件里只有 username = ?，按参数值查（不区分大小写，同 MySQL 默认排序规则）
        com.baomidou.mybatisplus.core.conditions.AbstractWrapper<?, ?, ?> w =
                (com.baomidou.mybatisplus.core.conditions.AbstractWrapper<?, ?, ?>) wrapper;
        w.getSqlSegment();
        Object v = w.getParamNameValuePairs().values().iterator().next();
        String name = String.valueOf(v).toLowerCase(Locale.ROOT);
        return users.stream().filter(u -> u.getUsername().toLowerCase(Locale.ROOT).equals(name)).findFirst().orElse(null);
    }

    private User localUser(String username, String password, String source, int status) {
        User u = new User();
        u.setId(ids.incrementAndGet());
        u.setUsername(username);
        u.setPassword(PasswordHasher.hash(password));
        u.setSource(source);
        u.setStatus(status);
        users.add(u);
        return u;
    }

    private Map<String, Object> login(String username, String password) {
        Map<String, String> body = new HashMap<>();
        body.put("username", username);
        body.put("password", password);
        MockHttpServletRequest request = new MockHttpServletRequest();
        return controller.login(body, request);
    }

    @Test
    void firstLoginCreatesLdapUserWithDefaultRole() {
        Map<String, Object> r = login("alice", EmbeddedLdap.ALICE_PASSWORD);

        assertEquals(true, r.get("success"), String.valueOf(r));
        assertEquals("alice", r.get("username"));
        assertEquals(false, r.get("mustChangePassword"));
        assertEquals(1, users.size());
        User created = users.get(0);
        assertEquals("ldap", created.getSource());
        assertEquals("Alice Liu", created.getRealName());
        assertEquals("alice@example.com", created.getEmail());
        assertEquals(1, created.getStatus());
        // 不保存 LDAP 密码：本地哈希对 LDAP 密码不匹配
        assertFalse(PasswordHasher.matches(EmbeddedLdap.ALICE_PASSWORD, created.getPassword()));
        ArgumentCaptor<UserRole> role = ArgumentCaptor.forClass(UserRole.class);
        verify(userRoleMapper).insert(role.capture());
        assertEquals(created.getId(), role.getValue().getUserId());
        assertEquals(4L, role.getValue().getRoleId());
    }

    @Test
    void secondLoginReusesTheSameUser() {
        assertEquals(true, login("alice", EmbeddedLdap.ALICE_PASSWORD).get("success"));
        assertEquals(true, login("ALICE", EmbeddedLdap.ALICE_PASSWORD).get("success"));

        assertEquals(1, users.size());
        verify(userRoleMapper, times(1)).insert(any(UserRole.class));
    }

    @Test
    void emptyDefaultRoleAssignsNoRole() {
        Map<String, Object> form = new HashMap<>();
        form.put("defaultRole", "");
        configService.save(form);

        assertEquals(true, login("alice", EmbeddedLdap.ALICE_PASSWORD).get("success"));
        verify(userRoleMapper, never()).insert(any(UserRole.class));
    }

    @Test
    void wrongPasswordFailsAndCountsTowardsLockout() {
        for (int i = 0; i < 5; i++) {
            Map<String, Object> r = login("alice", "wrong");
            assertEquals(false, r.get("success"));
            assertEquals("用户名或密码错误", r.get("message"));
        }
        // 第 6 次即使密码正确也被锁
        Map<String, Object> locked = login("alice", EmbeddedLdap.ALICE_PASSWORD);
        assertEquals(false, locked.get("success"));
        assertTrue(String.valueOf(locked.get("message")).contains("次数过多"), String.valueOf(locked));
        assertTrue(users.isEmpty());
    }

    @Test
    void unknownUserFailsWithSameMessage() {
        Map<String, Object> r = login("nobody", "whatever");
        assertEquals(false, r.get("success"));
        assertEquals("用户名或密码错误", r.get("message"));
        assertTrue(users.isEmpty());
    }

    @Test
    void emptyPasswordIsRejected() {
        assertEquals(false, login("alice", "").get("success"));
        assertEquals(false, login("alice", "   ").get("success"));
        assertTrue(users.isEmpty());
    }

    @Test
    void injectionCharactersDoNotLogInAsSomeoneElse() {
        for (String evil : new String[]{"*", "al*", "*)(uid=*", "alice)(|(uid=*"}) {
            Map<String, Object> r = login(evil, EmbeddedLdap.ALICE_PASSWORD);
            assertEquals(false, r.get("success"), evil);
        }
        assertTrue(users.isEmpty());
    }

    @Test
    void disabledLdapUserCannotLogInEvenWithCorrectPassword() {
        localUser("alice", "unused-local", "ldap", 0);

        Map<String, Object> r = login("alice", EmbeddedLdap.ALICE_PASSWORD);

        assertEquals(false, r.get("success"));
        assertEquals("用户已禁用", r.get("message"));
    }

    @Test
    void localAccountWithSameNameIsNotTakenOverByLdap() {
        localUser("alice", "local-pass", "local", 1);

        Map<String, Object> viaLdapPassword = login("alice", EmbeddedLdap.ALICE_PASSWORD);
        assertEquals(false, viaLdapPassword.get("success"), "LDAP 密码不能登录同名本地账号");

        Map<String, Object> viaLocal = login("alice", "local-pass");
        assertEquals(true, viaLocal.get("success"));
        assertEquals(1, users.size());
    }

    @Test
    void unreachableLdapDoesNotAffectLocalLogin() throws Exception {
        int port;
        try (ServerSocket s = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            port = s.getLocalPort();
        }
        enableLdap("ldap://127.0.0.1:" + port);
        localUser("admin", "Admin-pass-123", "local", 1);

        long start = System.nanoTime();
        assertEquals(true, login("admin", "Admin-pass-123").get("success"));
        assertTrue((System.nanoTime() - start) / 1_000_000 < 1000, "本地登录不应等 LDAP");

        Map<String, Object> ldapUser = login("alice", EmbeddedLdap.ALICE_PASSWORD);
        assertEquals(false, ldapUser.get("success"));
        assertTrue(String.valueOf(ldapUser.get("message")).contains("暂不可用"));
        // 服务器不可用不计入失败次数
        assertEquals(0, guard.lockedSeconds("alice"));
        for (int i = 0; i < 6; i++) {
            login("alice", EmbeddedLdap.ALICE_PASSWORD);
        }
        assertEquals(0, guard.lockedSeconds("alice"));
    }

    @Test
    void ldapDisabledFallsBackToLocalOnly() {
        Map<String, Object> form = new HashMap<>();
        form.put("enabled", false);
        configService.save(form);

        Map<String, Object> r = login("alice", EmbeddedLdap.ALICE_PASSWORD);
        assertEquals(false, r.get("success"));
        assertEquals("用户名或密码错误", r.get("message"));

        localUser("bob", "unused", "ldap", 1);
        Map<String, Object> ldapUser = login("bob", "x");
        assertEquals(false, ldapUser.get("success"));
        assertTrue(String.valueOf(ldapUser.get("message")).contains("未启用"));
    }

    @Test
    void ldapUserCannotUseStoredRandomHashLocally() {
        // 管理员把 LDAP 用户来源改成 local 也不会意外暴露可用密码：本地哈希是随机值
        assertEquals(true, login("alice", EmbeddedLdap.ALICE_PASSWORD).get("success"));
        users.get(0).setSource("local");
        assertEquals(false, login("alice", EmbeddedLdap.ALICE_PASSWORD).get("success"));
    }

    @Test
    void bindPasswordIsEncryptedAtRestAndMaskedInView() {
        String stored = config.get("ldap", "bindPassword");
        assertTrue(SecretCipher.isEncrypted(stored), stored);
        assertFalse(stored.contains(EmbeddedLdap.READER_PASSWORD));

        Map<String, Object> view = configService.view();
        assertEquals(LdapConfigService.PASSWORD_MASK, view.get("bindPassword"));
        assertEquals(true, view.get("hasBindPassword"));
        assertFalse(view.toString().contains(EmbeddedLdap.READER_PASSWORD));

        // 回传占位或留空都表示不修改
        Map<String, Object> form = new HashMap<>();
        form.put("bindPassword", LdapConfigService.PASSWORD_MASK);
        configService.save(form);
        assertEquals(EmbeddedLdap.READER_PASSWORD, configService.load().getBindPassword());
        form.put("bindPassword", "");
        configService.save(form);
        assertEquals(EmbeddedLdap.READER_PASSWORD, configService.load().getBindPassword());

        form.put("clearBindPassword", true);
        form.put("enabled", false);
        configService.save(form);
        assertNull(configService.load().getBindPassword());
        assertEquals(false, configService.view().get("hasBindPassword"));
    }

    @Test
    void enablingWithIncompleteSettingsIsRejected() {
        Map<String, Object> form = new HashMap<>();
        form.put("url", "http://not-ldap");
        org.junit.jupiter.api.Assertions.assertThrows(com.opsflow.common.exception.BusinessException.class,
                () -> configService.save(form));
        assertEquals(ldap.ldapUrl(), configService.load().getUrl(), "失败时不应写入");
    }

    /** system_config 的内存替身 */
    private static class InMemoryConfig implements SystemConfigService {
        private final Map<String, Map<String, String>> values = new HashMap<>();

        @Override
        public String get(String type, String key) {
            return values.getOrDefault(type, Collections.emptyMap()).get(key);
        }

        @Override
        public String get(String type, String key, String def) {
            String v = get(type, key);
            return v != null ? v : def;
        }

        @Override
        public boolean getBoolean(String type, String key, boolean def) {
            String v = get(type, key);
            return v != null ? Boolean.parseBoolean(v) : def;
        }

        @Override
        public Map<String, String> getByType(String type) {
            return new HashMap<>(values.getOrDefault(type, Collections.emptyMap()));
        }

        @Override
        public void save(String type, String key, String value, String description) {
            values.computeIfAbsent(type, k -> new HashMap<>()).put(key, value);
        }

        @Override
        public void saveBatch(String type, Map<String, String> batch) {
            values.computeIfAbsent(type, k -> new HashMap<>()).putAll(batch);
        }
    }
}
