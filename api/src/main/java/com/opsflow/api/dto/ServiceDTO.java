package com.opsflow.api.dto;

import lombok.Data;

/**
 * 服务DTO
 */
@Data
public class ServiceDTO {
    
    private Long id;
    
    private String name;
    
    private String code;
    
    private String gitRepo;
    
    private String defaultBranch;
    
    private String k8sDeployment;
    
    private String k8sNamespace;
    
    private String dockerfilePath;
    
    private String buildCommand;
    
    private Integer status;
}


