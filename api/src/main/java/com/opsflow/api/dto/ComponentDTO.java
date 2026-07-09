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

    /**
     * 关联钥匙串 ID
     */
    private Long credentialId;

    /**
     * 关联钥匙串名称（展示用）
     */
    private String credentialName;
    
    private String description;
    
    private Integer status;
}


