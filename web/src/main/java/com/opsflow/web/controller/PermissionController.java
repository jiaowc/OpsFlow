package com.opsflow.web.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.opsflow.api.dto.PermissionDTO;
import com.opsflow.common.exception.BusinessException;
import com.opsflow.dao.mapper.PermissionMapper;
import com.opsflow.dao.model.Permission;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 权限管理控制器
 */
@RestController
@RequestMapping("/api/permission")
public class PermissionController {

    @Autowired
    private PermissionMapper permissionMapper;

    /**
     * 获取权限列表
     */
    @GetMapping("/list")
    public List<PermissionDTO> listPermissions() {
        QueryWrapper<Permission> wrapper = new QueryWrapper<>();
        wrapper.orderByAsc("parent_id", "id");
        
        List<Permission> permissions = permissionMapper.selectList(wrapper);
        return permissions.stream().map(this::getPermissionDTO).collect(Collectors.toList());
    }

    /**
     * 获取权限树（按父子关系组织）
     */
    @GetMapping("/tree")
    public List<PermissionDTO> getPermissionTree() {
        QueryWrapper<Permission> wrapper = new QueryWrapper<>();
        wrapper.eq("status", 1);
        wrapper.orderByAsc("parent_id", "id");
        
        List<Permission> permissions = permissionMapper.selectList(wrapper);
        List<PermissionDTO> allDTOs = permissions.stream().map(this::getPermissionDTO).collect(Collectors.toList());
        
        // 构建权限树
        return buildPermissionTree(allDTOs, 0L);
    }

    /**
     * 获取权限详情
     */
    @GetMapping("/{id}")
    public PermissionDTO getPermission(@PathVariable Long id) {
        Permission permission = permissionMapper.selectById(id);
        if (permission == null) {
            return null;
        }
        return getPermissionDTO(permission);
    }

    /**
     * 创建权限
     */
    @PostMapping("/create")
    public PermissionDTO createPermission(@RequestBody PermissionDTO request) {
        // 检查权限代码是否已存在
        QueryWrapper<Permission> wrapper = new QueryWrapper<>();
        wrapper.eq("code", request.getCode());
        if (permissionMapper.selectCount(wrapper) > 0) {
            throw new BusinessException("权限代码已存在");
        }
        
        Permission permission = new Permission();
        BeanUtils.copyProperties(request, permission, "id", "children");
        
        if (permission.getParentId() == null) {
            permission.setParentId(0L);
        }
        
        permission.setStatus(1);
        permission.setCreateTime(LocalDateTime.now());
        permission.setUpdateTime(LocalDateTime.now());
        
        permissionMapper.insert(permission);
        
        return getPermissionDTO(permission);
    }

    /**
     * 更新权限
     */
    @PutMapping("/{id}")
    public PermissionDTO updatePermission(@PathVariable Long id, @RequestBody PermissionDTO request) {
        Permission permission = permissionMapper.selectById(id);
        if (permission == null) {
            throw new BusinessException("权限不存在");
        }
        
        // 检查权限代码是否已被其他权限使用
        if (request.getCode() != null && !request.getCode().equals(permission.getCode())) {
            QueryWrapper<Permission> wrapper = new QueryWrapper<>();
            wrapper.eq("code", request.getCode());
            wrapper.ne("id", id);
            if (permissionMapper.selectCount(wrapper) > 0) {
                throw new BusinessException("权限代码已存在");
            }
        }
        
        BeanUtils.copyProperties(request, permission, "id", "children");
        
        if (permission.getParentId() == null) {
            permission.setParentId(0L);
        }
        
        permission.setUpdateTime(LocalDateTime.now());
        permissionMapper.updateById(permission);
        
        return getPermissionDTO(permission);
    }

    /**
     * 删除权限
     */
    @DeleteMapping("/{id}")
    public void deletePermission(@PathVariable Long id) {
        Permission permission = permissionMapper.selectById(id);
        if (permission == null) {
            throw new BusinessException("权限不存在");
        }
        
        // 检查是否有子权限
        QueryWrapper<Permission> wrapper = new QueryWrapper<>();
        wrapper.eq("parent_id", id);
        if (permissionMapper.selectCount(wrapper) > 0) {
            throw new BusinessException("该权限下存在子权限，无法删除");
        }
        
        // 删除角色权限关联（由RolePermissionMapper处理）
        // rolePermissionMapper.deleteByPermissionId(id);
        
        // 删除权限
        permissionMapper.deleteById(id);
    }

    /**
     * 构建权限树
     */
    private List<PermissionDTO> buildPermissionTree(List<PermissionDTO> allDTOs, Long parentId) {
        return allDTOs.stream()
            .filter(dto -> parentId.equals(dto.getParentId() == null ? 0L : dto.getParentId()))
            .peek(dto -> {
                List<PermissionDTO> children = buildPermissionTree(allDTOs, dto.getId());
                if (!children.isEmpty()) {
                    dto.setChildren(children);
                }
            })
            .collect(Collectors.toList());
    }

    /**
     * 转换为PermissionDTO
     */
    private PermissionDTO getPermissionDTO(Permission permission) {
        PermissionDTO dto = new PermissionDTO();
        BeanUtils.copyProperties(permission, dto);
        return dto;
    }
}


