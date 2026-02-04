package com.opsflow.api.dto;

import lombok.Data;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 上线任务DTO
 */
@Data
public class DeployTaskDTO {
    
    private Long id;
    
    private String taskNumber;
    
    /**
     * 任务名称
     */
    private String taskName;
    
    /**
     * 上线模块列表，格式：服务名称:版本号（镜像名称:tag）
     */
    private List<String> deployModules;
    
    /**
     * 部署环境ID列表
     */
    private List<Long> deployEnvIds;
    
    /**
     * 部署环境名称列表
     */
    private List<String> deployEnvNames;
    
    private Long approvalFlowId;
    
    private String approvalFlowName;
    
    /**
     * Pipeline模板ID
     */
    private Long pipelineTemplateId;
    
    /**
     * Pipeline参数（键值对，将传递给Pipeline执行）
     */
    private java.util.Map<String, String> pipelineParameters;
    
    private String approvalStatus;
    
    private String taskStatus;
    
    private String description;
    
    private Long creatorId;
    
    private String creatorName;
    
    private LocalDateTime createTime;
    
    private LocalDateTime updateTime;
    
    private LocalDateTime deployTime;
}

