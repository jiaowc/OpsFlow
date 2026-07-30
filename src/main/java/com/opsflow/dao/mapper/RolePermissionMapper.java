package com.opsflow.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.opsflow.dao.model.RolePermission;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 角色权限关联Mapper
 */
@Mapper
public interface RolePermissionMapper extends BaseMapper<RolePermission> {
    
    /**
     * 根据角色ID删除所有权限关联
     */
    void deleteByRoleId(@Param("roleId") Long roleId);
    
    /**
     * 根据权限ID删除所有角色关联
     */
    void deleteByPermissionId(@Param("permissionId") Long permissionId);
    
    /**
     * 根据角色ID查询权限ID列表
     */
    List<Long> selectPermissionIdsByRoleId(@Param("roleId") Long roleId);
}


