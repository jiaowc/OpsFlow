package com.opsflow.api.dto;

import lombok.Data;
import java.util.List;

/**
 * 权限DTO
 */
@Data
public class PermissionDTO {
    
    private Long id;
    
    private String name;
    
    private String code;
    
    private String resource;
    
    private String method;
    
    private String description;
    
    private Long parentId;
    
    private Integer status;
    
    /**
     * 子权限列表（用于权限树）
     */
    private List<PermissionDTO> children;
}


