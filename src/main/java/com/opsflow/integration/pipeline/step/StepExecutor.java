package com.opsflow.integration.pipeline.step;

import com.opsflow.integration.pipeline.PipelineExecutionContext;

/**
 * Pipeline步骤执行器接口
 */
public interface StepExecutor {
    
    /**
     * 执行步骤
     * 
     * @param stepType 步骤类型
     * @param stepParams 步骤参数
     * @param context 执行上下文
     * @return 执行结果
     */
    StepExecutionResult execute(String stepType, java.util.Map<String, String> stepParams, PipelineExecutionContext context);
    
    /**
     * 是否支持该步骤类型
     * 
     * @param stepType 步骤类型
     * @return 是否支持
     */
    boolean supports(String stepType);
}


