package com.opsflow.web.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.opsflow.api.dto.RoleDTO;
import com.opsflow.api.dto.PermissionDTO;
import com.opsflow.common.exception.BusinessException;
import com.opsflow.dao.mapper.*;
import com.opsflow.dao.model.*;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 角色管理控制器
 */
@RestController
@RequestMapping("/api/role")
public class RoleController {

    @Autowired
    private RoleMapper roleMapper;
    
    @Autowired
    private PermissionMapper permissionMapper;
    
    @Autowired
    private RolePermissionMapper rolePermissionMapper;
    
    @Autowired
    private UserRoleMapper userRoleMapper;

    /**
     * 获取角色列表
     */
    @GetMapping("/list")
    public List<RoleDTO> listRoles() {
        QueryWrapper<Role> wrapper = new QueryWrapper<>();
        wrapper.orderByDesc("create_time");
        
        List<Role> roles = roleMapper.selectList(wrapper);
        return roles.stream().map(this::getRoleDTO).collect(Collectors.toList());
    }

    /**
     * 获取角色详情
     */
    @GetMapping("/{id}")
    public RoleDTO getRole(@PathVariable Long id) {
        Role role = roleMapper.selectById(id);
        if (role == null) {
            return null;
        }
        return getRoleDTO(role);
    }

    /**
     * 创建角色
     */
    @PostMapping("/create")
    public RoleDTO createRole(@RequestBody RoleDTO request) {
        // 检查角色代码是否已存在
        QueryWrapper<Role> wrapper = new QueryWrapper<>();
        wrapper.eq("code", request.getCode());
        if (roleMapper.selectCount(wrapper) > 0) {
            throw new BusinessException("角色代码已存在");
        }
        
        Role role = new Role();
        BeanUtils.copyProperties(request, role, "id", "permissions", "permissionIds");
        
        role.setStatus(1);
        role.setCreateTime(LocalDateTime.now());
        role.setUpdateTime(LocalDateTime.now());
        
        roleMapper.insert(role);
        
        // 保存角色权限关联
        if (request.getPermissionIds() != null && !request.getPermissionIds().isEmpty()) {
            saveRolePermissions(role.getId(), request.getPermissionIds());
        }
        
        return getRoleDTO(role);
    }

    /**
     * 更新角色
     */
    @PutMapping("/{id}")
    public RoleDTO updateRole(@PathVariable Long id, @RequestBody RoleDTO request) {
        Role role = roleMapper.selectById(id);
        if (role == null) {
            throw new BusinessException("角色不存在");
        }
        
        // 检查角色代码是否已被其他角色使用
        if (request.getCode() != null && !request.getCode().equals(role.getCode())) {
            QueryWrapper<Role> wrapper = new QueryWrapper<>();
            wrapper.eq("code", request.getCode());
            wrapper.ne("id", id);
            if (roleMapper.selectCount(wrapper) > 0) {
                throw new BusinessException("角色代码已存在");
            }
        }
        
        BeanUtils.copyProperties(request, role, "id", "permissions", "permissionIds");
        
        role.setUpdateTime(LocalDateTime.now());
        roleMapper.updateById(role);
        
        // 更新角色权限关联
        if (request.getPermissionIds() != null) {
            rolePermissionMapper.deleteByRoleId(id);
            if (!request.getPermissionIds().isEmpty()) {
                saveRolePermissions(id, request.getPermissionIds());
            }
        }
        
        return getRoleDTO(role);
    }

    /**
     * 删除角色
     */
    @DeleteMapping("/{id}")
    public void deleteRole(@PathVariable Long id) {
        Role role = roleMapper.selectById(id);
        if (role == null) {
            throw new BusinessException("角色不存在");
        }
        
        // 删除角色权限关联
        rolePermissionMapper.deleteByRoleId(id);
        
        // 删除用户角色关联
        userRoleMapper.deleteByRoleId(id);
        
        // 删除角色
        roleMapper.deleteById(id);
    }

    /**
     * 保存角色权限关联
     */
    private void saveRolePermissions(Long roleId, List<Long> permissionIds) {
        for (Long permissionId : permissionIds) {
            RolePermission rolePermission = new RolePermission();
            rolePermission.setRoleId(roleId);
            rolePermission.setPermissionId(permissionId);
            rolePermission.setCreateTime(LocalDateTime.now());
            rolePermissionMapper.insert(rolePermission);
        }
    }

    /**
     * 转换为RoleDTO
     */
    private RoleDTO getRoleDTO(Role role) {
        RoleDTO dto = new RoleDTO();
        BeanUtils.copyProperties(role, dto);
        
        // 查询角色权限
        List<Long> permissionIds = rolePermissionMapper.selectPermissionIdsByRoleId(role.getId());
        if (permissionIds != null && !permissionIds.isEmpty()) {
            List<Permission> permissions = permissionMapper.selectBatchIds(permissionIds);
            List<PermissionDTO> permissionDTOs = permissions.stream().map(permission -> {
                PermissionDTO permissionDTO = new PermissionDTO();
                BeanUtils.copyProperties(permission, permissionDTO);
                return permissionDTO;
            }).collect(Collectors.toList());
            dto.setPermissions(permissionDTOs);
            dto.setPermissionIds(permissionIds);
        }
        
        return dto;
    }
}

