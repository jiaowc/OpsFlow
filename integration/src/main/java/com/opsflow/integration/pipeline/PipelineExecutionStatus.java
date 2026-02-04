package com.opsflow.integration.pipeline;

import lombok.Data;

/**
 * Pipeline执行状态
 */
@Data
public class PipelineExecutionStatus {
    
    /**
     * 执行ID
     */
    private String executionId;
    
    /**
     * 状态：PENDING, RUNNING, SUCCESS, FAILED, CANCELLED
     */
    private String status;
    
    /**
     * 当前步骤
     */
    private String currentStep;
    
    /**
     * 进度（0-100）
     */
    private Integer progress;
    
    /**
     * 开始时间
     */
    private Long startTime;
    
    /**
     * 结束时间
     */
    private Long endTime;
}


