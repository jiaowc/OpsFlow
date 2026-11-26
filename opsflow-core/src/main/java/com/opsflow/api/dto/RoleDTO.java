package com.opsflow.api.dto;

import lombok.Data;
import java.util.List;

/**
 * 角色DTO
 */
@Data
public class RoleDTO {
    
    private Long id;
    
    private String name;
    
    private String code;
    
    private String description;
    
    private Integer status;
    
    /**
     * 角色权限列表
     */
    private List<PermissionDTO> permissions;
    
    /**
     * 权限ID列表（用于创建/更新）
     */
    private List<Long> permissionIds;
}


