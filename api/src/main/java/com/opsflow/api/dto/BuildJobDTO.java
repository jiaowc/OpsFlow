package com.opsflow.api.dto;

import lombok.Data;
import java.time.LocalDateTime;

/**
 * 构建任务DTO
 */
@Data
public class BuildJobDTO {
    
    private Long id;
    
    private String jobNumber;
    
    private Long serviceId;
    
    private String serviceName;
    
    private Long envId;
    
    private String envName;
    
    private String branch;
    
    private String jenkinsNode;
    
    private Boolean autoDeploy;
    
    private String status; // pending, building, success, failed
    
    private String jenkinsJobUrl;
    
    private Integer jenkinsBuildNumber;
    
    private LocalDateTime createTime;
    
    private LocalDateTime updateTime;
    
    private LocalDateTime startTime;
    
    private LocalDateTime endTime;
}


