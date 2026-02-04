package com.opsflow.api.dto;

import lombok.Data;

/**
 * 环境DTO
 */
@Data
public class EnvDTO {
    
    private Long id;
    
    private String name;
    
    private String k8sCluster;
    
    private String k8sNamespace;
    
    private String harborProject;
    
    private String jenkinsJobTemplate;
    
    private Integer status;
}


