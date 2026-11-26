package com.opsflow.web.security;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.opsflow.common.exception.BusinessException;
import com.opsflow.dao.mapper.RoleMapper;
import com.opsflow.dao.mapper.UserMapper;
import com.opsflow.dao.mapper.UserRoleMapper;
import com.opsflow.dao.model.Role;
import com.opsflow.dao.model.User;
import com.opsflow.dao.model.UserRole;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 保证系统始终至少有一个可用的管理员：内置 ADMIN 角色不可删除/禁用，
 * 最后一个启用的管理员账号不可被删除、禁用或摘掉 ADMIN 角色。
 */
@Component
public class AdminGuard {

    /** 内置管理员角色代码 */
    public static final String ADMIN_ROLE_CODE = "ADMIN";

    @Autowired
    private RoleMapper roleMapper;

    @Autowired
    private UserRoleMapper userRoleMapper;

    @Autowired
    private UserMapper userMapper;

    public boolean isAdminRole(Role role) {
        return role != null && ADMIN_ROLE_CODE.equalsIgnoreCase(role.getCode());
    }

    /** 内置 ADMIN 角色 ID；不存在时返回 null */
    public Long adminRoleId() {
        Role role = roleMapper.selectOne(new QueryWrapper<Role>().eq("code", ADMIN_ROLE_CODE).last("LIMIT 1"));
        return role != null ? role.getId() : null;
    }

    /** 该用户当前是否为启用的管理员 */
    public boolean isActiveAdmin(Long userId) {
        return userId != null && activeAdminUserIds().contains(userId);
    }

    /**
     * 用户即将被删除、禁用或失去 ADMIN 角色时调用：若它是最后一个启用的管理员则拒绝。
     */
    public void assertNotLastActiveAdmin(Long userId, String action) {
        Set<Long> admins = activeAdminUserIds();
        if (admins.contains(userId) && admins.size() <= 1) {
            throw new BusinessException("不能" + action + "最后一个启用的管理员账号");
        }
    }

    /** 角色 ID 列表中是否包含内置 ADMIN 角色 */
    public boolean containsAdminRole(Collection<Long> roleIds) {
        Long adminRoleId = adminRoleId();
        return adminRoleId != null && roleIds != null && roleIds.contains(adminRoleId);
    }

    private Set<Long> activeAdminUserIds() {
        Long adminRoleId = adminRoleId();
        if (adminRoleId == null) {
            return java.util.Collections.emptySet();
        }
        List<Long> userIds = userRoleMapper.selectList(new QueryWrapper<UserRole>().eq("role_id", adminRoleId))
                .stream().map(UserRole::getUserId).distinct().collect(Collectors.toList());
        if (userIds.isEmpty()) {
            return java.util.Collections.emptySet();
        }
        return userMapper.selectList(new QueryWrapper<User>().in("id", userIds).eq("status", 1))
                .stream().map(User::getId).collect(Collectors.toSet());
    }
}
