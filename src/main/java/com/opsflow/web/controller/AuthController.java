package com.opsflow.web.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.opsflow.common.util.PasswordHasher;
import com.opsflow.common.util.PasswordPolicy;
import com.opsflow.dao.mapper.UserMapper;
import com.opsflow.dao.model.User;
import com.opsflow.integration.feishu.FeishuSSO;
import com.opsflow.integration.feishu.FeishuSsoConfig;
import com.opsflow.service.FeishuUserResolveService;
import com.opsflow.service.PermissionService;
import com.opsflow.web.security.LoginAttemptGuard;
import com.opsflow.web.security.SessionPermissionResolver;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 认证控制器（本地登录 + 飞书 SSO 浏览器回跳）
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private static final String FEISHU_SSO_STATE_KEY = "FEISHU_SSO_STATE";

    /** session 标记：本地用户使用弱口令登录，必须先改密（AuthInterceptor 据此拦截其他 API） */
    public static final String MUST_CHANGE_PASSWORD_KEY = "mustChangePassword";

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private PermissionService permissionService;

    @Autowired
    private FeishuSsoConfig feishuSsoConfig;

    @Autowired
    private FeishuSSO feishuSSO;

    @Autowired
    private FeishuUserResolveService feishuUserResolveService;

    @Autowired
    private LoginAttemptGuard loginAttemptGuard;

    @Autowired
    private SessionPermissionResolver sessionPermissionResolver;

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

        if (user == null || !PasswordHasher.matches(password, user.getPassword())) {
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
        loginAttemptGuard.recordSuccess(trimmedUsername);

        if (PasswordHasher.needsUpgrade(user.getPassword())) {
            User upgrade = new User();
            upgrade.setId(user.getId());
            upgrade.setPassword(PasswordHasher.hash(password));
            userMapper.updateById(upgrade);
        }

        HttpSession session = rotateSession(request);
        bindSession(session, user.getUsername(), user.getId(),
                permissionService.listRoleCodesByUserId(user.getId()),
                permissionService.listPermissionCodesByUserId(user.getId()));
        boolean mustChangePassword = isLocalUser(user) && PasswordPolicy.isWeak(user.getUsername(), password);
        if (mustChangePassword) {
            session.setAttribute(MUST_CHANGE_PASSWORD_KEY, Boolean.TRUE);
        }

        result.put("success", true);
        result.put(MUST_CHANGE_PASSWORD_KEY, mustChangePassword);
        result.put("token", session.getId());
        result.put("username", user.getUsername());
        result.put("roles", session.getAttribute("roles"));
        result.put("permissions", session.getAttribute("permissions"));
        result.put("message", "登录成功");
        return result;
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
     * 飞书 SSO 是否可用（登录页展示按钮）
     */
    @GetMapping("/feishu/status")
    public Map<String, Object> feishuStatus() {
        Map<String, Object> result = new HashMap<>();
        boolean enabled = feishuSsoConfig.isEnabled();
        boolean ready = feishuSSO.isReady();
        result.put("enabled", enabled);
        result.put("ready", ready);
        result.put("configured", StringUtils.hasText(feishuSsoConfig.getAppId())
                && StringUtils.hasText(feishuSsoConfig.getAppSecret())
                && StringUtils.hasText(feishuSsoConfig.getRedirectUri()));
        return result;
    }

    /**
     * 发起飞书登录：浏览器跳转到飞书授权页（出网），授权后回跳到本系统 redirect_uri。
     */
    @GetMapping("/feishu/login")
    public void feishuLogin(HttpSession session, HttpServletResponse response) throws IOException {
        if (!feishuSsoConfig.isEnabled()) {
            redirectLoginError(response, "飞书登录未启用，请在系统设置中开启");
            return;
        }
        if (!feishuSSO.isReady()) {
            redirectLoginError(response, "飞书登录未配置完整（AppId / AppSecret / 回调地址）");
            return;
        }
        String state = UUID.randomUUID().toString().replace("-", "");
        session.setAttribute(FEISHU_SSO_STATE_KEY, state);
        String url = feishuSSO.getSSOUrl(state);
        if (!StringUtils.hasText(url)) {
            redirectLoginError(response, "生成飞书授权地址失败");
            return;
        }
        response.sendRedirect(url);
    }

    /**
     * 飞书授权回调（浏览器回跳，非飞书服务器入站推送）。
     * 要求本地用户已绑定飞书 ID（用户管理中配置）。
     */
    @GetMapping("/feishu/callback")
    public void feishuCallback(@RequestParam(required = false) String code,
                               @RequestParam(required = false) String state,
                               @RequestParam(required = false) String error,
                               @RequestParam(value = "error_description", required = false) String errorDescription,
                               HttpServletRequest request,
                               HttpSession session,
                               HttpServletResponse response) throws IOException {
        if (StringUtils.hasText(error)) {
            redirectLoginError(response, StringUtils.hasText(errorDescription) ? errorDescription : ("飞书授权失败: " + error));
            return;
        }
        Object expectedState = session.getAttribute(FEISHU_SSO_STATE_KEY);
        session.removeAttribute(FEISHU_SSO_STATE_KEY);
        if (!StringUtils.hasText(state) || expectedState == null || !state.equals(String.valueOf(expectedState))) {
            redirectLoginError(response, "登录状态校验失败，请重试");
            return;
        }
        if (!StringUtils.hasText(code)) {
            redirectLoginError(response, "未收到飞书授权码");
            return;
        }

        FeishuSSO.FeishuUserInfo feishuUser = feishuSSO.getUserInfoByCode(code.trim());
        if (feishuUser == null) {
            redirectLoginError(response, "获取飞书用户信息失败，请检查 SSO 应用配置与权限");
            return;
        }

        String username = resolveLocalUsername(feishuUser);
        if (!StringUtils.hasText(username)) {
            redirectLoginError(response,
                    "该飞书账号尚未绑定本地用户。请管理员在「用户管理」中填写飞书用户 ID 后再登录");
            return;
        }

        User user = userMapper.selectOne(
                new QueryWrapper<User>().eq("username", username.trim()).last("LIMIT 1"));
        if (user == null) {
            redirectLoginError(response, "绑定的本地用户不存在: " + username);
            return;
        }
        if (user.getStatus() != null && user.getStatus() == 0) {
            redirectLoginError(response, "用户已禁用");
            return;
        }

        feishuUserResolveService.enrichBindingFromSso(
                user.getUsername(),
                feishuUser.getUserId(),
                feishuUser.getOpenId(),
                feishuUser.getUnionId(),
                feishuUser.getMobile(),
                feishuUser.getEmail()
        );

        request.changeSessionId();
        bindSession(session, user.getUsername(), user.getId(),
                permissionService.listRoleCodesByUserId(user.getId()),
                permissionService.listPermissionCodesByUserId(user.getId()));

        response.sendRedirect("/statistics");
    }

    private String resolveLocalUsername(FeishuSSO.FeishuUserInfo feishuUser) {
        String username = feishuUserResolveService.resolveUsernameByFeishuId(feishuUser.getUserId());
        if (!StringUtils.hasText(username)) {
            username = feishuUserResolveService.resolveUsernameByFeishuId(feishuUser.getOpenId());
        }
        if (!StringUtils.hasText(username)) {
            username = feishuUserResolveService.resolveUsernameByFeishuId(feishuUser.getUnionId());
        }
        return username;
    }

    private void redirectLoginError(HttpServletResponse response, String message) throws IOException {
        String encoded = URLEncoder.encode(message != null ? message : "飞书登录失败", StandardCharsets.UTF_8.name());
        response.sendRedirect("/?sso_error=" + encoded);
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
            result.put("message", "仅本地用户可修改密码，飞书/LDAP 用户请在对应系统中修改");
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
        session.setAttribute("user", username);
        session.setAttribute("authenticated", true);
        session.setAttribute("userId", userId);
        sessionPermissionResolver.bind(session, roles, permissions);
    }
}
