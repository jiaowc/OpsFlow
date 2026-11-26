package com.opsflow.web.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.opsflow.common.util.PasswordHasher;
import com.opsflow.common.util.PasswordPolicy;
import com.opsflow.dao.mapper.UserMapper;
import com.opsflow.dao.model.User;
import com.opsflow.integration.ldap.LdapLoginService;
import com.opsflow.service.PermissionService;
import com.opsflow.service.TwoFactorService;
import com.opsflow.web.security.LoginAttemptGuard;
import com.opsflow.web.security.LoginSessionBinder;
import com.opsflow.web.security.SessionPermissionResolver;
import com.opsflow.web.security.TwoFactorSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 认证控制器（本地 / LDAP 登录；第三方登录由扩展提供）。
 * <p>两步验证只拦在这里：密码（或 LDAP）验证通过后，按策略要求动态码（{@code step: two_factor}，第二步走
 * {@code POST /api/auth/two-factor/verify}），或登录后强制绑定。扩展的单点登录直接调用
 * {@link LoginSessionBinder}，不要求动态码。</p>
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    /** session 标记：本地用户使用弱口令登录，必须先改密（AuthInterceptor 据此拦截其他 API） */
    public static final String MUST_CHANGE_PASSWORD_KEY = "mustChangePassword";

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private PermissionService permissionService;

    @Autowired
    private LdapLoginService ldapLoginService;

    @Autowired
    private LoginAttemptGuard loginAttemptGuard;

    @Autowired
    private SessionPermissionResolver sessionPermissionResolver;

    @Autowired
    private LoginSessionBinder loginSessionBinder;

    @Autowired
    private TwoFactorService twoFactorService;

    /** 测试里替换成固定时间 */
    private java.util.function.LongSupplier clock = System::currentTimeMillis;

    /**
     * 登录：校验 user 表密码（BCrypt，兼容历史 MD5 并在登录成功后自动升级）。
     */
    @PostMapping("/login")
    public Map<String, Object> login(@RequestBody Map<String, String> loginRequest, HttpServletRequest request) {
        String username = loginRequest.get("username");
        String password = loginRequest.get("password");

        Map<String, Object> result = new HashMap<>();

        if (!StringUtils.hasText(username) || !StringUtils.hasText(password)) {
            result.put("success", false);
            result.put("message", "用户名和密码不能为空");
            return result;
        }

        String trimmedUsername = username.trim();
        long lockedSeconds = loginAttemptGuard.lockedSeconds(trimmedUsername);
        if (lockedSeconds > 0) {
            result.put("success", false);
            result.put("message", "登录失败次数过多，请 " + ((lockedSeconds + 59) / 60) + " 分钟后再试");
            return result;
        }

        User user = userMapper.selectOne(
                new QueryWrapper<User>().eq("username", trimmedUsername).last("LIMIT 1"));

        // 本地已有非 ldap 来源的同名账号只验本地密码；来源为 ldap、或本地没有且已启用 LDAP 时走 LDAP
        boolean viaLdap = ldapLoginService.shouldUseLdap(user);
        if (viaLdap) {
            LdapLoginService.Outcome outcome = ldapLoginService.login(trimmedUsername, password, user);
            if (!outcome.isSuccess()) {
                if (outcome.countsAsFailure()) {
                    loginAttemptGuard.recordFailure(trimmedUsername);
                }
                result.put("success", false);
                result.put("message", outcome.getMessage());
                return result;
            }
            user = outcome.getUser();
        } else if (user == null || !PasswordHasher.matches(password, user.getPassword())) {
            loginAttemptGuard.recordFailure(trimmedUsername);
            result.put("success", false);
            result.put("message", "用户名或密码错误");
            return result;
        }
        if (user.getStatus() != null && user.getStatus() == 0) {
            result.put("success", false);
            result.put("message", "用户已禁用");
            return result;
        }

        if (!viaLdap && PasswordHasher.needsUpgrade(user.getPassword())) {
            User upgrade = new User();
            upgrade.setId(user.getId());
            upgrade.setPassword(PasswordHasher.hash(password));
            userMapper.updateById(upgrade);
        }
        boolean mustChangePassword = isLocalUser(user) && PasswordPolicy.isWeak(user.getUsername(), password);

        TwoFactorService.Policy policy = twoFactorService.policy();
        boolean eligible = policy != TwoFactorService.Policy.OFF && twoFactorService.isEligible(user);
        if (eligible && twoFactorService.isEnrolled(user.getId())) {
            // 还差动态码：不绑定会话，也不清零失败次数（第二步通过后才算登录成功）
            HttpSession session = freshSession(request);
            session.setAttribute(TwoFactorSession.PENDING_USER_ID, user.getId());
            session.setAttribute(TwoFactorSession.PENDING_USERNAME, user.getUsername());
            session.setAttribute(TwoFactorSession.PENDING_AT, clock.getAsLong());
            session.setAttribute(TwoFactorSession.PENDING_ATTEMPTS, 0);
            session.setAttribute(TwoFactorSession.PENDING_MUST_CHANGE_PASSWORD, mustChangePassword);
            result.put("success", false);
            result.put("step", "two_factor");
            result.put("username", user.getUsername());
            result.put("message", "请输入动态码");
            return result;
        }

        loginAttemptGuard.recordSuccess(trimmedUsername);
        boolean mustEnroll = eligible && policy == TwoFactorService.Policy.REQUIRED;
        return completeLogin(request, user, mustChangePassword, mustEnroll);
    }

    /**
     * 登录第二步：提交动态码（{@code code}）或恢复码（{@code recoveryCode}）。
     * <p>5 分钟内有效、最多 5 次；超过次数作废待验证状态并计一次登录失败（与账号锁定规则共用计数）。
     * 错误带机器可读的 {@code code}：two_factor.code_invalid / recovery_invalid（带 remainingAttempts）、
     * two_factor.too_many、two_factor.expired。</p>
     */
    @PostMapping("/two-factor/verify")
    public Map<String, Object> verifyTwoFactor(@RequestBody(required = false) Map<String, String> body,
                                               HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        Object pendingId = session != null ? session.getAttribute(TwoFactorSession.PENDING_USER_ID) : null;
        if (!(pendingId instanceof Long)) {
            return twoFactorFail("two_factor.expired", "验证已超时，请重新输入密码登录");
        }
        Object at = session.getAttribute(TwoFactorSession.PENDING_AT);
        if (!(at instanceof Long) || clock.getAsLong() - (Long) at > TwoFactorSession.PENDING_TTL_MILLIS) {
            TwoFactorSession.clearPending(session);
            return twoFactorFail("two_factor.expired", "验证已超时，请重新输入密码登录");
        }
        String username = (String) session.getAttribute(TwoFactorSession.PENDING_USERNAME);
        long lockedSeconds = loginAttemptGuard.lockedSeconds(username);
        if (lockedSeconds > 0) {
            TwoFactorSession.clearPending(session);
            Map<String, Object> locked = new HashMap<>();
            locked.put("success", false);
            locked.put("message", "登录失败次数过多，请 " + ((lockedSeconds + 59) / 60) + " 分钟后再试");
            return locked;
        }
        User user = userMapper.selectById((Long) pendingId);
        if (user == null || (user.getStatus() != null && user.getStatus() == 0)) {
            TwoFactorSession.clearPending(session);
            return twoFactorFail("two_factor.expired", "验证已超时，请重新输入密码登录");
        }

        String code = body != null ? body.get("code") : null;
        String recoveryCode = body != null ? body.get("recoveryCode") : null;
        boolean useRecovery = StringUtils.hasText(recoveryCode);
        if (!useRecovery && !StringUtils.hasText(code)) {
            // 空提交不算一次尝试
            return twoFactorFail("two_factor.code_invalid", "请输入动态码",
                    TwoFactorSession.MAX_ATTEMPTS - attempts(session));
        }
        TwoFactorService.Verification v = twoFactorService.verify(user.getId(), code, recoveryCode);
        if (v == TwoFactorService.Verification.NOT_ENROLLED) {
            // 等待期间绑定被管理员重置：重新走一遍登录
            TwoFactorSession.clearPending(session);
            return twoFactorFail("two_factor.expired", "验证已超时，请重新输入密码登录");
        }
        if (!v.isOk()) {
            int used = attempts(session) + 1;
            if (used >= TwoFactorSession.MAX_ATTEMPTS) {
                TwoFactorSession.clearPending(session);
                loginAttemptGuard.recordFailure(username);
                return twoFactorFail("two_factor.too_many", "尝试次数过多，请重新输入密码登录");
            }
            session.setAttribute(TwoFactorSession.PENDING_ATTEMPTS, used);
            int remaining = TwoFactorSession.MAX_ATTEMPTS - used;
            return useRecovery
                    ? twoFactorFail("two_factor.recovery_invalid", "恢复码不正确或已经用过，还可以再试 " + remaining + " 次", remaining)
                    : twoFactorFail("two_factor.code_invalid", "动态码不正确，还可以再试 " + remaining + " 次", remaining);
        }

        boolean mustChangePassword = Boolean.TRUE.equals(session.getAttribute(TwoFactorSession.PENDING_MUST_CHANGE_PASSWORD));
        TwoFactorSession.clearPending(session);
        loginAttemptGuard.recordSuccess(username);
        Map<String, Object> result = completeLogin(request, user, mustChangePassword, false);
        if (v == TwoFactorService.Verification.RECOVERY_OK) {
            request.getSession().setAttribute(TwoFactorSession.RECOVERY_USED, Boolean.TRUE);
            result.put("recoveryCodeUsed", true);
            result.put("recoveryCodesLeft", twoFactorService.recoveryCodesLeft(user.getId()));
        }
        return result;
    }

    /** 绑定会话并组装登录成功的返回 */
    private Map<String, Object> completeLogin(HttpServletRequest request, User user,
                                              boolean mustChangePassword, boolean mustEnroll) {
        HttpSession session = rotateSession(request);
        bindSession(session, user.getUsername(), user.getId(),
                permissionService.listRoleCodesByUserId(user.getId()),
                permissionService.listPermissionCodesByUserId(user.getId()));
        if (mustChangePassword) {
            session.setAttribute(MUST_CHANGE_PASSWORD_KEY, Boolean.TRUE);
        }
        if (mustEnroll) {
            session.setAttribute(TwoFactorSession.MUST_ENROLL, Boolean.TRUE);
        }

        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put(MUST_CHANGE_PASSWORD_KEY, mustChangePassword);
        result.put(TwoFactorSession.MUST_ENROLL, mustEnroll);
        result.put("token", session.getId());
        result.put("username", user.getUsername());
        result.put("roles", session.getAttribute("roles"));
        result.put("permissions", session.getAttribute("permissions"));
        result.put("message", "登录成功");
        return result;
    }

    private static int attempts(HttpSession session) {
        Object n = session.getAttribute(TwoFactorSession.PENDING_ATTEMPTS);
        return n instanceof Integer ? (Integer) n : 0;
    }

    private static Map<String, Object> twoFactorFail(String code, String message) {
        Map<String, Object> result = new HashMap<>();
        result.put("success", false);
        result.put("code", code);
        result.put("message", message);
        return result;
    }

    private static Map<String, Object> twoFactorFail(String code, String message, int remaining) {
        Map<String, Object> result = twoFactorFail(code, message);
        result.put("remainingAttempts", remaining);
        return result;
    }

    /** 进入第二步前换一个全新的 session：丢掉旧会话里可能残留的登录身份，也防会话固定 */
    private static HttpSession freshSession(HttpServletRequest request) {
        HttpSession old = request.getSession(false);
        if (old != null) {
            old.invalidate();
        }
        return request.getSession(true);
    }

    /**
     * 认证成功后更换 session ID，防止会话固定攻击
     */
    private HttpSession rotateSession(HttpServletRequest request) {
        HttpSession session = request.getSession(true);
        request.changeSessionId();
        return session;
    }

    /**
     * 获取当前用户信息（含角色与权限码）
     */
    @GetMapping("/user")
    public Map<String, Object> getCurrentUser(HttpSession session) {
        Map<String, Object> result = new HashMap<>();

        String username = (String) session.getAttribute("user");
        Boolean authenticated = (Boolean) session.getAttribute("authenticated");

        Object uid = session.getAttribute("userId");
        User user = Boolean.TRUE.equals(authenticated) && username != null && uid instanceof Long
                ? userMapper.selectById((Long) uid) : null;
        if (user != null && user.getStatus() != null && user.getStatus() != 1) {
            user = null;
        }
        if (Boolean.TRUE.equals(authenticated) && uid != null && user == null) {
            // 登录后被禁用或删除：这个接口不经过 AuthInterceptor 的登录校验，要自己作废会话，
            // 否则页面刷新时还会把它当成已登录
            session.invalidate();
            result.put("authenticated", false);
            return result;
        }

        if (user != null) {
            sessionPermissionResolver.resolvePermissions(session);
            result.put("username", username);
            result.put("authenticated", true);
            result.put("userId", uid);
            result.put("roles", session.getAttribute("roles"));
            result.put("permissions", session.getAttribute("permissions"));
            result.put(MUST_CHANGE_PASSWORD_KEY, Boolean.TRUE.equals(session.getAttribute(MUST_CHANGE_PASSWORD_KEY)));
            result.put("localUser", isLocalUser(user));
            result.put("twoFactor", twoFactorInfo(user, session));
        } else {
            result.put("authenticated", false);
        }

        return result;
    }

    /**
     * 修改密码（仅本地用户）：校验旧密码，新密码需 ≥ 8 位且不命中弱口令；成功后清除强制改密标记。
     */
    @PostMapping("/change-password")
    public Map<String, Object> changePassword(@RequestBody Map<String, String> body, HttpSession session) {
        Map<String, Object> result = new HashMap<>();
        result.put("success", false);

        Object userId = session.getAttribute("userId");
        if (!Boolean.TRUE.equals(session.getAttribute("authenticated")) || !(userId instanceof Long)) {
            result.put("message", "未登录，请先登录");
            return result;
        }
        User user = userMapper.selectById((Long) userId);
        if (user == null) {
            result.put("message", "用户不存在");
            return result;
        }
        if (!isLocalUser(user)) {
            result.put("message", "仅本地用户可修改密码，单点登录和 LDAP 用户请在对应系统中修改");
            return result;
        }

        String oldPassword = body != null ? body.get("oldPassword") : null;
        String newPassword = body != null ? body.get("newPassword") : null;
        if (!StringUtils.hasText(oldPassword) || !StringUtils.hasText(newPassword)) {
            result.put("message", "旧密码和新密码不能为空");
            return result;
        }
        if (!PasswordHasher.matches(oldPassword, user.getPassword())) {
            result.put("message", "旧密码不正确");
            return result;
        }
        if (newPassword.length() < PasswordPolicy.MIN_LENGTH) {
            result.put("message", "新密码长度不能少于 " + PasswordPolicy.MIN_LENGTH + " 位");
            return result;
        }
        if (PasswordPolicy.isWeak(user.getUsername(), newPassword)) {
            result.put("message", "新密码过于简单（不能为常见弱口令或与用户名相同）");
            return result;
        }
        if (newPassword.equals(oldPassword)) {
            result.put("message", "新密码不能与旧密码相同");
            return result;
        }

        User update = new User();
        update.setId(user.getId());
        update.setPassword(PasswordHasher.hash(newPassword));
        userMapper.updateById(update);
        session.removeAttribute(MUST_CHANGE_PASSWORD_KEY);

        result.put("success", true);
        result.put("message", "密码修改成功");
        return result;
    }

    /** {@code {policy, enrolled, eligible, mustEnroll, recoveryCodesLeft, recoveryCodeUsed}}；recoveryCodeUsed 是用恢复码登录后的一次性提示 */
    private Map<String, Object> twoFactorInfo(User user, HttpSession session) {
        Map<String, Object> info = new LinkedHashMap<>();
        boolean enrolled = twoFactorService.isEnrolled(user.getId());
        info.put("policy", twoFactorService.policy().code());
        info.put("enrolled", enrolled);
        info.put("eligible", twoFactorService.isEligible(user));
        info.put("mustEnroll", Boolean.TRUE.equals(session.getAttribute(TwoFactorSession.MUST_ENROLL)));
        info.put("recoveryCodesLeft", enrolled ? twoFactorService.recoveryCodesLeft(user.getId()) : 0);
        boolean recoveryUsed = Boolean.TRUE.equals(session.getAttribute(TwoFactorSession.RECOVERY_USED));
        if (recoveryUsed) {
            session.removeAttribute(TwoFactorSession.RECOVERY_USED);
        }
        info.put("recoveryCodeUsed", recoveryUsed);
        return info;
    }

    /** source 为空（历史数据）或 local 视为本地用户 */
    private static boolean isLocalUser(User user) {
        return !StringUtils.hasText(user.getSource()) || "local".equalsIgnoreCase(user.getSource().trim());
    }

    /**
     * 退出登录
     */
    @PostMapping("/logout")
    public Map<String, Object> logout(HttpSession session) {
        session.invalidate();
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("message", "退出成功");
        return result;
    }

    private void bindSession(HttpSession session, String username, Long userId,
                             List<String> roles, List<String> permissions) {
        loginSessionBinder.bind(session, username, userId, roles, permissions);
    }
}
