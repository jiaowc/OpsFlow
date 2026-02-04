package com.opsflow.integration.pipeline.step;

import lombok.Data;

/**
 * 步骤执行结果
 */
@Data
public class StepExecutionResult {
    
    /**
     * 是否成功
     */
    private Boolean success;
    
    /**
     * 错误消息
     */
    private String errorMessage;
    
    /**
     * 输出数据（传递给后续步骤）
     */
    private java.util.Map<String, String> outputData;
    
    /**
     * 执行日志
     */
    private String log;
}


