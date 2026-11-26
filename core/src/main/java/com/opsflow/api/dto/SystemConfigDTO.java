package com.opsflow.api.dto;

import lombok.Data;

/**
 * 系统配置DTO
 */
@Data
public class SystemConfigDTO {
    
    private Long id;
    
    private String configType;
    
    private String configKey;
    
    private String configValue;
    
    private String description;
}


