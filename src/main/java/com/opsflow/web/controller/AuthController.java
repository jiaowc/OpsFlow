package com.opsflow.web.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.opsflow.dao.mapper.UserMapper;
import com.opsflow.dao.model.User;
import com.opsflow.integration.feishu.FeishuSSO;
import com.opsflow.integration.feishu.FeishuSsoConfig;
import com.opsflow.service.FeishuUserResolveService;
import com.opsflow.service.PermissionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.util.DigestUtils;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

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

    /**
     * 登录：优先校验 user 表（密码 MD5，与用户管理一致）；无匹配时兼容 admin/admin。
     */
    @PostMapping("/login")
    public Map<String, Object> login(@RequestBody Map<String, String> loginRequest, HttpSession session) {
        String username = loginRequest.get("username");
        String password = loginRequest.get("password");

        Map<String, Object> result = new HashMap<>();

        if (!StringUtils.hasText(username) || !StringUtils.hasText(password)) {
            result.put("success", false);
            result.put("message", "用户名和密码不能为空");
            return result;
        }

        String trimmedUsername = username.trim();
        User user = userMapper.selectOne(
                new QueryWrapper<User>().eq("username", trimmedUsername).last("LIMIT 1"));

        if (user != null) {
            if (user.getStatus() != null && user.getStatus() == 0) {
                result.put("success", false);
                result.put("message", "用户已禁用");
                return result;
            }
            String inputHash = DigestUtils.md5DigestAsHex(password.getBytes());
            if (inputHash.equalsIgnoreCase(user.getPassword())) {
                bindSession(session, user.getUsername(), user.getId(),
                        permissionService.listRoleCodesByUserId(user.getId()),
                        permissionService.listPermissionCodesByUserId(user.getId()));

                result.put("success", true);
                result.put("token", session.getId());
                result.put("username", user.getUsername());
                result.put("roles", session.getAttribute("roles"));
                result.put("permissions", session.getAttribute("permissions"));
                result.put("message", "登录成功");
                return result;
            }
            result.put("success", false);
            result.put("message", "用户名或密码错误");
            return result;
        }

        // 兼容：未入库的 admin 账号（开发/初始环境）→ 超管全权限
        if ("admin".equals(trimmedUsername) && "admin".equals(password)) {
            bindSession(session, trimmedUsername, null,
                    Collections.singletonList("ADMIN"),
                    Collections.singletonList(PermissionService.WILDCARD));

            result.put("success", true);
            result.put("token", session.getId());
            result.put("username", trimmedUsername);
            result.put("roles", session.getAttribute("roles"));
            result.put("permissions", session.getAttribute("permissions"));
            result.put("message", "登录成功");
            return result;
        }

        result.put("success", false);
        result.put("message", "用户名或密码错误");
        return result;
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

        if (authenticated != null && authenticated && username != null) {
            ensureSessionPermissions(session);
            result.put("username", username);
            result.put("authenticated", true);
            result.put("userId", session.getAttribute("userId"));
            result.put("roles", session.getAttribute("roles"));
            result.put("permissions", session.getAttribute("permissions"));
        } else {
            result.put("authenticated", false);
        }

        return result;
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
        if (userId != null) {
            session.setAttribute("userId", userId);
        } else {
            session.removeAttribute("userId");
        }
        session.setAttribute("roles", roles != null ? roles : Collections.emptyList());
        session.setAttribute("permissions", permissions != null ? permissions : Collections.emptyList());
    }

    @SuppressWarnings("unchecked")
    private void ensureSessionPermissions(HttpSession session) {
        if (session.getAttribute("permissions") != null && session.getAttribute("roles") != null) {
            return;
        }
        Long userId = (Long) session.getAttribute("userId");
        if (userId != null) {
            session.setAttribute("roles", permissionService.listRoleCodesByUserId(userId));
            session.setAttribute("permissions", permissionService.listPermissionCodesByUserId(userId));
            return;
        }
        String username = (String) session.getAttribute("user");
        if ("admin".equals(username)) {
            session.setAttribute("roles", Collections.singletonList("ADMIN"));
            session.setAttribute("permissions", Collections.singletonList(PermissionService.WILDCARD));
        } else {
            session.setAttribute("roles", Collections.emptyList());
            session.setAttribute("permissions", Collections.emptyList());
        }
    }
}
