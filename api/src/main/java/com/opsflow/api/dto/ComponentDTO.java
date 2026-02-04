package com.opsflow.api.dto;

import lombok.Data;
import java.util.Map;

/**
 * 组件DTO
 */
@Data
public class ComponentDTO {
    
    private Long id;
    
    private String name;
    
    private String type;
    
    private String url;
    
    private String authType;
    
    /**
     * 认证配置（Map格式）
     */
    private Map<String, String> authConfig;
    
    private String description;
    
    private Integer status;
}


