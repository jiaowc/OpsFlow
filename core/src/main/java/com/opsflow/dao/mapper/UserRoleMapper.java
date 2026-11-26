package com.opsflow.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.opsflow.dao.model.UserRole;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 用户角色关联Mapper
 */
@Mapper
public interface UserRoleMapper extends BaseMapper<UserRole> {
    
    /**
     * 根据用户ID删除所有角色关联
     */
    void deleteByUserId(@Param("userId") Long userId);
    
    /**
     * 根据角色ID删除所有用户关联
     */
    void deleteByRoleId(@Param("roleId") Long roleId);
    
    /**
     * 根据用户ID查询角色ID列表
     */
    List<Long> selectRoleIdsByUserId(@Param("userId") Long userId);

    /**
     * 根据用户ID查询启用角色的 code 列表
     */
    List<String> selectRoleCodesByUserId(@Param("userId") Long userId);

    /**
     * 根据用户ID查询启用权限的 code 列表（经 user_role → role_permission）
     */
    List<String> selectPermissionCodesByUserId(@Param("userId") Long userId);
}


