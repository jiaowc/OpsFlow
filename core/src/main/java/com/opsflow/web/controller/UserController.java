package com.opsflow.web.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.opsflow.api.dto.UserDTO;
import com.opsflow.api.dto.RoleDTO;
import com.opsflow.common.exception.BusinessException;
import com.opsflow.common.util.RequiredFields;
import com.opsflow.dao.mapper.*;
import com.opsflow.dao.model.*;
import com.opsflow.spi.UserAccountExtension;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import com.opsflow.common.util.PasswordHasher;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;
import com.opsflow.web.security.RequiresPermission;

/**
 * 用户管理控制器
 */
@RequiresPermission("user:manage")
@RestController
@RequestMapping("/api/user")
public class UserController {

    @Autowired
    private UserMapper userMapper;
    
    @Autowired
    private RoleMapper roleMapper;
    
    @Autowired
    private UserRoleMapper userRoleMapper;

    /** 扩展字段（如飞书账号绑定）的保存和展示，社区版为空 */
    @Autowired(required = false)
    private List<UserAccountExtension> accountExtensions = new java.util.ArrayList<>();

    @Autowired
    private com.opsflow.web.security.AdminGuard adminGuard;

    @Autowired
    private ProjectMemberMapper projectMemberMapper;

    @Autowired
    private ProjectMapper projectMapper;

    @Autowired
    private com.opsflow.service.TwoFactorService twoFactorService;

    @Autowired
    private com.opsflow.service.ExternalIdentityService externalIdentityService;

    /**
     * 获取用户列表
     */
    @GetMapping("/list")
    public List<UserDTO> listUsers() {
        QueryWrapper<User> wrapper = new QueryWrapper<>();
        wrapper.orderByDesc("create_time");
        
        List<User> users = userMapper.selectList(wrapper);
        java.util.Set<Long> enrolled = twoFactorService.enrolledAmong(
                users.stream().map(User::getId).collect(Collectors.toList()));
        return users.stream().map(user -> {
            UserDTO dto = getUserDTO(user);
            dto.setTwoFactorEnabled(enrolled.contains(user.getId()));
            return dto;
        }).collect(Collectors.toList());
    }

    /**
     * 下拉选项：仅返回启用用户的 id / 用户名 / 姓名（审批流选择审批人用）
     */
    @RequiresPermission({"user:manage", "approval:manage"})
    @GetMapping("/options")
    public List<java.util.Map<String, Object>> userOptions() {
        return userMapper.selectList(new QueryWrapper<User>().eq("status", 1).orderByAsc("username"))
                .stream().map(u -> {
                    java.util.Map<String, Object> item = new java.util.LinkedHashMap<>();
                    item.put("id", u.getId());
                    item.put("username", u.getUsername());
                    item.put("realName", u.getRealName());
                    item.put("status", u.getStatus());
                    return item;
                }).collect(Collectors.toList());
    }

    /**
     * 获取用户详情
     */
    @GetMapping("/{id}")
    public UserDTO getUser(@PathVariable Long id) {
        User user = userMapper.selectById(id);
        if (user == null) {
            throw new BusinessException("用户不存在");
        }
        UserDTO dto = getUserDTO(user);
        dto.setTwoFactorEnabled(twoFactorService.isEnrolled(id));
        return dto;
    }

    /**
     * 创建用户
     */
    @PostMapping("/create")
    public UserDTO createUser(@RequestBody UserDTO request) {
        String username = RequiredFields.requireText(request.getUsername(), "用户名");
        // 密码不 trim，只拒绝空和纯空白
        RequiredFields.requireSecret(request.getPassword(), "密码");
        // 检查用户名是否已存在
        QueryWrapper<User> wrapper = new QueryWrapper<>();
        wrapper.eq("username", username);
        if (userMapper.selectCount(wrapper) > 0) {
            throw new BusinessException("用户名已存在");
        }
        
        User user = new User();
        BeanUtils.copyProperties(request, user, "id", "roles", "roleIds", "extra", "source");
        user.setUsername(username);
        
        // BCrypt 加密密码
        user.setPassword(PasswordHasher.hash(request.getPassword()));
        
        user.setStatus(1);
        if (!StringUtils.hasText(request.getSource())) {
            user.setSource("local");
        } else {
            user.setSource(normalizeUserSource(request.getSource()));
        }
        user.setCreateTime(LocalDateTime.now());
        user.setUpdateTime(LocalDateTime.now());
        
        userMapper.insert(user);
        
        // 保存用户角色关联
        if (request.getRoleIds() != null && !request.getRoleIds().isEmpty()) {
            saveUserRoles(user.getId(), request.getRoleIds());
        }
        afterSave(user, request);
        
        return getUserDTO(user);
    }

    /**
     * 更新用户
     */
    @PutMapping("/{id}")
    public UserDTO updateUser(@PathVariable Long id, @RequestBody UserDTO request) {
        User user = userMapper.selectById(id);
        if (user == null) {
            throw new BusinessException("用户不存在");
        }
        
        // 用户名没带表示不改，带了空值拒绝
        String username = RequiredFields.optionalText(request.getUsername(), "用户名");
        // 检查用户名是否已被其他用户使用
        if (username != null && !username.equals(user.getUsername())) {
            QueryWrapper<User> wrapper = new QueryWrapper<>();
            wrapper.eq("username", username);
            wrapper.ne("id", id);
            if (userMapper.selectCount(wrapper) > 0) {
                throw new BusinessException("用户名已存在");
            }
        }
        
        boolean disabling = request.getStatus() != null && request.getStatus() != 1
                && (user.getStatus() == null || user.getStatus() == 1);
        boolean losingAdminRole = request.getRoleIds() != null
                && adminGuard.isActiveAdmin(id) && !adminGuard.containsAdminRole(request.getRoleIds());
        if (disabling || losingAdminRole) {
            if (id.equals(currentUserId())) {
                throw new BusinessException(disabling ? "不能禁用自己的账号" : "不能移除自己的管理员角色");
            }
            adminGuard.assertNotLastActiveAdmin(id, disabling ? "禁用" : "移除");
        }

        BeanUtils.copyProperties(request, user, "id", "password", "roles", "roleIds", "extra", "source", "username");
        if (username != null) {
            user.setUsername(username);
        }
        
        // 如果提供了新密码，则更新（留空或只有空白表示不改）
        if (StringUtils.hasText(request.getPassword())) {
            user.setPassword(PasswordHasher.hash(request.getPassword()));
        }
        // 允许管理员纠正来源（飞书/LDAP 同步用户）
        if (StringUtils.hasText(request.getSource())) {
            user.setSource(normalizeUserSource(request.getSource()));
        }
        
        user.setUpdateTime(LocalDateTime.now());
        userMapper.updateById(user);
        
        // 更新用户角色关联
        if (request.getRoleIds() != null) {
            userRoleMapper.deleteByUserId(id);
            if (!request.getRoleIds().isEmpty()) {
                saveUserRoles(id, request.getRoleIds());
            }
        }
        afterSave(user, request);
        
        return getUserDTO(user);
    }

    /**
     * 删除用户
     */
    @DeleteMapping("/{id}")
    public void deleteUser(@PathVariable Long id) {
        User user = userMapper.selectById(id);
        if (user == null) {
            throw new BusinessException("用户不存在");
        }
        if (id.equals(currentUserId())) {
            throw new BusinessException("不能删除自己的账号");
        }
        adminGuard.assertNotLastActiveAdmin(id, "删除");

        // 清理项目成员与项目负责人引用，避免留下悬空的用户 ID
        projectMemberMapper.delete(new QueryWrapper<ProjectMember>().eq("user_id", id));
        projectMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<Project>()
                .eq("owner_user_id", id).set("owner_user_id", null));
        
        // 删除用户角色关联
        userRoleMapper.deleteByUserId(id);

        // 两步验证绑定、恢复码和外部身份对应关系
        twoFactorService.remove(id);
        externalIdentityService.removeAll(id);
        
        // 删除用户
        userMapper.deleteById(id);
    }

    private Long currentUserId() {
        org.springframework.web.context.request.RequestAttributes attrs =
                org.springframework.web.context.request.RequestContextHolder.getRequestAttributes();
        if (!(attrs instanceof org.springframework.web.context.request.ServletRequestAttributes)) {
            return null;
        }
        javax.servlet.http.HttpSession session =
                ((org.springframework.web.context.request.ServletRequestAttributes) attrs).getRequest().getSession(false);
        Object userId = session != null ? session.getAttribute("userId") : null;
        return userId instanceof Long ? (Long) userId : null;
    }

    /**
     * 保存用户角色关联
     */
    private void saveUserRoles(Long userId, List<Long> roleIds) {
        for (Long roleId : roleIds) {
            UserRole userRole = new UserRole();
            userRole.setUserId(userId);
            userRole.setRoleId(roleId);
            userRole.setCreateTime(LocalDateTime.now());
            userRoleMapper.insert(userRole);
        }
    }

    private void afterSave(User user, UserDTO request) {
        for (UserAccountExtension extension : accountExtensions) {
            extension.afterSave(user, request);
        }
    }

    /**
     * 转换为UserDTO
     */
    private UserDTO getUserDTO(User user) {
        UserDTO dto = new UserDTO();
        BeanUtils.copyProperties(user, dto, "password");
        if (!StringUtils.hasText(dto.getSource())) {
            dto.setSource("local");
        }
        
        // 查询用户角色
        List<Long> roleIds = userRoleMapper.selectRoleIdsByUserId(user.getId());
        if (roleIds != null && !roleIds.isEmpty()) {
            List<Role> roles = roleMapper.selectBatchIds(roleIds);
            List<RoleDTO> roleDTOs = roles.stream().map(role -> {
                RoleDTO roleDTO = new RoleDTO();
                BeanUtils.copyProperties(role, roleDTO);
                return roleDTO;
            }).collect(Collectors.toList());
            dto.setRoles(roleDTOs);
            dto.setRoleIds(roleIds);
        }

        for (UserAccountExtension extension : accountExtensions) {
            extension.decorate(user, dto);
        }
        
        return dto;
    }

    private String normalizeUserSource(String source) {
        String s = source.trim().toLowerCase();
        if ("feishu".equals(s) || "ldap".equals(s) || "local".equals(s)) {
            return s;
        }
        throw new BusinessException("用户来源无效，仅支持 local / feishu / ldap");
    }
}

