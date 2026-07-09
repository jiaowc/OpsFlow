package com.opsflow.api.dto;

import lombok.Data;
import java.time.LocalDateTime;

/**
 * 构建响应DTO
 */
@Data
public class BuildResponse {
    
    /**
     * 任务ID
     */
    private Long jobId;
    
    /**
     * 任务编号
     */
    private String jobNumber;

    /**
     * 任务名称
     */
    private String taskName;
    
    /**
     * 状态
     */
    private String status;
    
    /**
     * 镜像标签
     */
    private String imageTag;
    
    /**
     * 构建日志URL
     */
    private String buildLogUrl;
    
    /**
     * 错误信息
     */
    private String errorMessage;
    
    /**
     * 开始时间
     */
    private LocalDateTime startTime;
    
    /**
     * 结束时间
     */
    private LocalDateTime endTime;
}



