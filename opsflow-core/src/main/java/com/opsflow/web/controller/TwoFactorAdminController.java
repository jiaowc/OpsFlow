package com.opsflow.web.controller;

import com.opsflow.common.exception.BusinessException;
import com.opsflow.dao.mapper.UserMapper;
import com.opsflow.dao.model.User;
import com.opsflow.service.TwoFactorService;
import com.opsflow.web.security.RequiresPermission;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/** 两步验证的管理接口：安全设置页的统计、用户管理里重置他人的绑定 */
@RestController
public class TwoFactorAdminController {

    @Autowired
    private TwoFactorService twoFactorService;

    @Autowired
    private UserMapper userMapper;

    /** {@code {policy, enrolled, eligible}}：eligible 是启用中、来源为本地或 LDAP 的账号数 */
    @RequiresPermission("system:config")
    @GetMapping("/api/security/two-factor/summary")
    public Map<String, Object> summary() {
        return twoFactorService.summary();
    }

    /** 作废该用户的验证器绑定和全部恢复码；全员强制时该用户下次登录要重新绑定 */
    @RequiresPermission("user:manage")
    @PostMapping("/api/user/{id}/two-factor/reset")
    public Map<String, Object> reset(@PathVariable Long id) {
        User user = userMapper.selectById(id);
        if (user == null) {
            throw new BusinessException("用户不存在");
        }
        twoFactorService.remove(id);
        Map<String, Object> out = new HashMap<>();
        out.put("success", true);
        out.put("username", user.getUsername());
        return out;
    }
}
