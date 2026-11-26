package com.opsflow.web.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.opsflow.common.exception.BusinessException;
import com.opsflow.dao.mapper.RoleMapper;
import com.opsflow.dao.model.Role;
import com.opsflow.integration.ldap.LdapAuthException;
import com.opsflow.integration.ldap.LdapAuthenticator;
import com.opsflow.integration.ldap.LdapConfigService;
import com.opsflow.integration.ldap.LdapSettings;
import com.opsflow.integration.ldap.LdapUserInfo;
import com.opsflow.web.security.RequiresPermission;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * LDAP 登录配置（社区版）。绑定密码只写不读。
 */
@RestController
@RequestMapping("/api/ldap")
@RequiresPermission("system:config")
public class LdapController {

    @Autowired
    private LdapConfigService ldapConfigService;

    @Autowired
    private LdapAuthenticator ldapAuthenticator;

    @Autowired
    private RoleMapper roleMapper;

    @GetMapping("/config")
    public Map<String, Object> getConfig() {
        return ldapConfigService.view();
    }

    @PutMapping("/config")
    public Map<String, Object> saveConfig(@RequestBody Map<String, Object> body) {
        Object role = body != null ? body.get("defaultRole") : null;
        if (role != null && StringUtils.hasText(String.valueOf(role))) {
            String code = String.valueOf(role).trim();
            Long n = roleMapper.selectCount(new QueryWrapper<Role>().eq("code", code));
            if (n == null || n == 0) {
                throw new BusinessException("默认角色不存在: " + code);
            }
        }
        ldapConfigService.save(body);
        return ldapConfigService.view();
    }

    /**
     * 测试连接：用表单里的配置（未保存也可以；绑定密码留空则用已保存的）连接并读取 Base DN。
     * 填了 testUsername 时再搜索该用户；同时填了 testPassword 时再验证该用户的密码。不建号、不登录。
     */
    @PostMapping("/test")
    public Map<String, Object> test(@RequestBody(required = false) Map<String, Object> body) {
        LdapSettings settings = ldapConfigService.merge(body);
        Map<String, Object> result = new LinkedHashMap<>();
        String testUser = body != null && body.get("testUsername") != null
                ? String.valueOf(body.get("testUsername")).trim() : "";
        String testPassword = body != null && body.get("testPassword") != null
                ? String.valueOf(body.get("testPassword")) : "";
        try {
            String message = ldapAuthenticator.testConnection(settings);
            if (StringUtils.hasText(testUser)) {
                LdapUserInfo user = StringUtils.hasText(testPassword)
                        ? ldapAuthenticator.authenticate(settings, testUser, testPassword)
                        : ldapAuthenticator.findUser(settings, testUser);
                Map<String, Object> u = new LinkedHashMap<>();
                u.put("dn", user.getDn());
                u.put("username", user.getUsername());
                u.put("realName", user.getRealName());
                u.put("email", user.getEmail());
                result.put("user", u);
                message = message + (StringUtils.hasText(testPassword)
                        ? "；用户 " + testUser + " 密码验证通过" : "；找到用户 " + testUser);
            }
            result.put("success", true);
            result.put("message", message);
        } catch (LdapAuthException e) {
            result.put("success", false);
            result.put("reason", e.getReason().name());
            result.put("message", e.getReason() == LdapAuthException.Reason.BAD_CREDENTIALS && StringUtils.hasText(testUser)
                    ? "连接正常，但测试用户 " + testUser + "：" + e.getMessage()
                    : e.getMessage());
        }
        return result;
    }
}
