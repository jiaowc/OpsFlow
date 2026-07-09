package com.opsflow.integration.pipeline;

import com.opsflow.api.dto.PipelineDTO;

/**
 * Pipeline 执行器接口
 */
public interface PipelineExecutor {
    
    /**
     * 执行Pipeline
     * 
     * @param pipeline Pipeline模板
     * @param context 执行上下文（包含服务信息、环境信息、参数等）
     * @return 执行结果
     */
    PipelineExecutionResult execute(PipelineDTO pipeline, PipelineExecutionContext context);
    
    /**
     * 取消执行
     * 
     * @param executionId 执行ID
     */
    void cancel(String executionId);
    
    /**
     * 查询执行状态
     * 
     * @param executionId 执行ID
     * @return 执行状态
     */
    PipelineExecutionStatus getStatus(String executionId);
}

