package com.opsflow.api.dto;

import lombok.Data;

/**
 * 环境DTO
 */
@Data
public class EnvDTO {
    
    private Long id;
    
    private String name;

    private Long clusterId;
    
    private String k8sCluster;

    private String clusterServer;
    
    private String k8sNamespace;
    
    private Integer status;
}


