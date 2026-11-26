package com.opsflow.integration.ldap;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.opsflow.common.util.PasswordHasher;
import com.opsflow.dao.mapper.RoleMapper;
import com.opsflow.dao.mapper.UserMapper;
import com.opsflow.dao.mapper.UserRoleMapper;
import com.opsflow.dao.model.Role;
import com.opsflow.dao.model.User;
import com.opsflow.dao.model.UserRole;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;

/**
 * 登录页的 LDAP 认证与首次登录建号。
 * <p>规则（2026-11-19 用户确认）：
 * <ul>
 *   <li>本地已有来源不是 ldap 的同名账号：只验本地密码，不走 LDAP（防止 LDAP 中的同名账号接管本地账号）；</li>
 *   <li>来源是 ldap 的账号，或本地没有该账号且 LDAP 已启用：走 LDAP；</li>
 *   <li>首次登录自动建号，来源 ldap，不保存 LDAP 密码，按配置赋予默认角色（默认只读用户 VIEWER）。</li>
 * </ul>
 * 账号是否被禁用由调用方在认证通过后统一检查。
 */
@Slf4j
@Service
public class LdapLoginService {

    public static final String SOURCE_LDAP = "ldap";

    private static final SecureRandom RANDOM = new SecureRandom();

    @Autowired
    private LdapConfigService ldapConfigService;

    @Autowired
    private LdapAuthenticator ldapAuthenticator;

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private RoleMapper roleMapper;

    @Autowired
    private UserRoleMapper userRoleMapper;

    public enum Status {
        SUCCESS,
        /** 用户名或密码错误：计入登录失败次数 */
        BAD_CREDENTIALS,
        /** LDAP 服务器不可用：不计入失败次数 */
        UNAVAILABLE,
        /** LDAP 未启用或配置有误：不计入失败次数 */
        NOT_AVAILABLE
    }

    public static final class Outcome {
        private final Status status;
        private final User user;
        private final String message;

        private Outcome(Status status, User user, String message) {
            this.status = status;
            this.user = user;
            this.message = message;
        }

        public Status getStatus() {
            return status;
        }

        public User getUser() {
            return user;
        }

        public String getMessage() {
            return message;
        }

        public boolean isSuccess() {
            return status == Status.SUCCESS;
        }

        public boolean countsAsFailure() {
            return status == Status.BAD_CREDENTIALS;
        }
    }

    /**
     * 这次登录是否走 LDAP
     *
     * @param localUser 本地同名账号，没有为 null
     */
    public boolean shouldUseLdap(User localUser) {
        if (localUser != null) {
            return SOURCE_LDAP.equals(localUser.getSource());
        }
        return ldapConfigService.load().isEnabled();
    }

    /**
     * LDAP 认证；本地没有账号时认证通过后自动建号。
     */
    public Outcome login(String username, String password, User localUser) {
        LdapSettings settings = ldapConfigService.load();
        if (!settings.isEnabled()) {
            return new Outcome(Status.NOT_AVAILABLE, null, "LDAP 登录未启用，请联系管理员");
        }
        LdapUserInfo info;
        try {
            info = ldapAuthenticator.authenticate(settings, username, password);
        } catch (LdapAuthException e) {
            switch (e.getReason()) {
                case BAD_CREDENTIALS:
                    return new Outcome(Status.BAD_CREDENTIALS, null, "用户名或密码错误");
                case UNAVAILABLE:
                    log.warn("LDAP 登录失败（服务器不可用）: user={}, {}", username, e.getMessage());
                    return new Outcome(Status.UNAVAILABLE, null, "LDAP 服务暂不可用，请稍后再试");
                default:
                    log.warn("LDAP 登录失败（配置问题）: user={}, {}", username, e.getMessage());
                    return new Outcome(Status.NOT_AVAILABLE, null, "LDAP 登录配置有误，请联系管理员");
            }
        }
        User user = localUser != null ? syncProfile(localUser, info) : provision(username, info, settings);
        return new Outcome(Status.SUCCESS, user, null);
    }

    /** 首次登录建号 */
    private User provision(String typedUsername, LdapUserInfo info, LdapSettings settings) {
        String name = typedUsername.trim();
        if (StringUtils.hasText(info.getUsername()) && info.getUsername().equalsIgnoreCase(name)) {
            name = info.getUsername();
        }
        User user = new User();
        user.setUsername(name);
        // password 列非空：存一个随机值的哈希，任何密码都匹配不上；不保存 LDAP 密码
        byte[] random = new byte[32];
        RANDOM.nextBytes(random);
        user.setPassword(PasswordHasher.hash(Base64.getEncoder().encodeToString(random)));
        user.setRealName(limit(info.getRealName(), 100));
        user.setEmail(limit(info.getEmail(), 100));
        user.setStatus(1);
        user.setSource(SOURCE_LDAP);
        user.setCreateTime(LocalDateTime.now());
        user.setUpdateTime(LocalDateTime.now());
        try {
            userMapper.insert(user);
        } catch (DuplicateKeyException e) {
            // 并发的首次登录已建号
            User existing = userMapper.selectOne(new QueryWrapper<User>().eq("username", name).last("LIMIT 1"));
            if (existing != null) {
                return existing;
            }
            throw e;
        }
        assignDefaultRole(user, settings.getDefaultRole());
        log.info("LDAP 用户首次登录，已自动创建本地账号: username={}, defaultRole={}", name,
                StringUtils.hasText(settings.getDefaultRole()) ? settings.getDefaultRole() : "(无)");
        return user;
    }

    private void assignDefaultRole(User user, String roleCode) {
        if (!StringUtils.hasText(roleCode)) {
            return;
        }
        Role role = roleMapper.selectOne(new QueryWrapper<Role>().eq("code", roleCode.trim()).last("LIMIT 1"));
        if (role == null || (role.getStatus() != null && role.getStatus() == 0)) {
            log.warn("LDAP 默认角色 {} 不存在或已停用，用户 {} 未分配角色", roleCode, user.getUsername());
            return;
        }
        UserRole ur = new UserRole();
        ur.setUserId(user.getId());
        ur.setRoleId(role.getId());
        ur.setCreateTime(LocalDateTime.now());
        userRoleMapper.insert(ur);
    }

    /** 已有 LDAP 账号：姓名、邮箱以 LDAP 为准（LDAP 里为空时保留本地值） */
    private User syncProfile(User user, LdapUserInfo info) {
        String realName = limit(info.getRealName(), 100);
        String email = limit(info.getEmail(), 100);
        boolean changed = false;
        User patch = new User();
        patch.setId(user.getId());
        if (StringUtils.hasText(realName) && !realName.equals(user.getRealName())) {
            patch.setRealName(realName);
            user.setRealName(realName);
            changed = true;
        }
        if (StringUtils.hasText(email) && !email.equals(user.getEmail())) {
            patch.setEmail(email);
            user.setEmail(email);
            changed = true;
        }
        if (changed) {
            userMapper.updateById(patch);
        }
        return user;
    }

    private static String limit(String v, int max) {
        if (!StringUtils.hasText(v)) {
            return null;
        }
        String t = v.trim();
        return t.length() > max ? t.substring(0, max) : t;
    }
}
