package com.opsflow.web.security;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.servlet.http.HttpSession;
import java.util.List;

/**
 * 登录成功后把用户身份和权限写进 session。本地、LDAP 登录和扩展提供的单点登录方式共用；这里不检查两步验证，单点登录不要求动态码。
 */
@Component
public class LoginSessionBinder {

    @Autowired
    private SessionPermissionResolver sessionPermissionResolver;

    public void bind(HttpSession session, String username, Long userId,
                     List<String> roles, List<String> permissions) {
        session.setAttribute("user", username);
        session.setAttribute("authenticated", true);
        session.setAttribute("userId", userId);
        sessionPermissionResolver.bind(session, roles, permissions);
    }
}
