package com.opsflow.integration.pipeline;

import lombok.Data;

/**
 * Pipeline执行结果
 */
@Data
public class PipelineExecutionResult {
    
    /**
     * 执行ID
     */
    private String executionId;
    
    /**
     * 是否成功
     */
    private Boolean success;
    
    /**
     * 错误消息
     */
    private String errorMessage;
    
    /**
     * 镜像标签（如果构建了镜像）
     */
    private String imageTag;
    
    /**
     * 镜像完整名称（如果构建了镜像）
     */
    private String imageFullName;
    
    /**
     * 构建日志URL
     */
    private String logUrl;
}


