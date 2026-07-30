package com.opsflow.web.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.opsflow.api.dto.UserDTO;
import com.opsflow.api.dto.RoleDTO;
import com.opsflow.common.exception.BusinessException;
import com.opsflow.dao.mapper.*;
import com.opsflow.dao.model.*;
import com.opsflow.service.FeishuUserResolveService;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.util.DigestUtils;
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

    @Autowired
    private UserFeishuMapper userFeishuMapper;

    @Autowired
    private FeishuUserResolveService feishuUserResolveService;

    /**
     * 获取用户列表
     */
    @GetMapping("/list")
    public List<UserDTO> listUsers() {
        QueryWrapper<User> wrapper = new QueryWrapper<>();
        wrapper.orderByDesc("create_time");
        
        List<User> users = userMapper.selectList(wrapper);
        return users.stream().map(this::getUserDTO).collect(Collectors.toList());
    }

    /**
     * 获取用户详情
     */
    @GetMapping("/{id}")
    public UserDTO getUser(@PathVariable Long id) {
        User user = userMapper.selectById(id);
        if (user == null) {
            return null;
        }
        return getUserDTO(user);
    }

    /**
     * 创建用户
     */
    @PostMapping("/create")
    public UserDTO createUser(@RequestBody UserDTO request) {
        // 检查用户名是否已存在
        QueryWrapper<User> wrapper = new QueryWrapper<>();
        wrapper.eq("username", request.getUsername());
        if (userMapper.selectCount(wrapper) > 0) {
            throw new BusinessException("用户名已存在");
        }
        
        User user = new User();
        BeanUtils.copyProperties(request, user, "id", "roles", "roleIds", "feishuUserId", "source");
        
        // 加密密码（简单MD5，实际应该使用BCrypt等更安全的方式）
        if (request.getPassword() != null && !request.getPassword().isEmpty()) {
            user.setPassword(DigestUtils.md5DigestAsHex(request.getPassword().getBytes()));
        } else {
            throw new BusinessException("密码不能为空");
        }
        
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
        bindFeishuIfPresent(user, request);
        
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
        
        // 检查用户名是否已被其他用户使用
        if (request.getUsername() != null && !request.getUsername().equals(user.getUsername())) {
            QueryWrapper<User> wrapper = new QueryWrapper<>();
            wrapper.eq("username", request.getUsername());
            wrapper.ne("id", id);
            if (userMapper.selectCount(wrapper) > 0) {
                throw new BusinessException("用户名已存在");
            }
        }
        
        BeanUtils.copyProperties(request, user, "id", "password", "roles", "roleIds", "feishuUserId", "source");
        
        // 如果提供了新密码，则更新
        if (request.getPassword() != null && !request.getPassword().isEmpty()) {
            user.setPassword(DigestUtils.md5DigestAsHex(request.getPassword().getBytes()));
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
        bindFeishuIfPresent(user, request);
        
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
        
        // 删除用户角色关联
        userRoleMapper.deleteByUserId(id);
        
        // 删除用户
        userMapper.deleteById(id);
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

    private void bindFeishuIfPresent(User user, UserDTO request) {
        if (request.getFeishuUserId() == null) {
            return;
        }
        String feishuUserId = StringUtils.hasText(request.getFeishuUserId())
                ? request.getFeishuUserId().trim() : null;
        feishuUserResolveService.bindFeishuUser(
                user.getId(),
                user.getUsername(),
                feishuUserId,
                user.getPhone(),
                user.getEmail()
        );
    }

    /**
     * 转换为UserDTO
     */
    private UserDTO getUserDTO(User user) {
        UserDTO dto = new UserDTO();
        BeanUtils.copyProperties(user, dto);
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

        UserFeishu binding = userFeishuMapper.selectOne(
                new QueryWrapper<UserFeishu>().eq("username", user.getUsername()).last("LIMIT 1"));
        if (binding != null) {
            dto.setFeishuUserId(binding.getFeishuUserId());
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

