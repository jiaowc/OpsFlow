package com.opsflow.integration.pipeline.step.impl;

import com.opsflow.integration.pipeline.PipelineExecutionContext;
import com.opsflow.integration.pipeline.step.StepExecutor;
import com.opsflow.integration.pipeline.step.StepExecutionResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * 通知步骤执行器（支持飞书、钉钉、企业微信等）
 * 暂时仅记录日志，后续可以集成具体的通知服务
 */
@Slf4j
@Component
public class NotifyStepExecutor implements StepExecutor {
    
    @Override
    public StepExecutionResult execute(String stepType, Map<String, String> stepParams, PipelineExecutionContext context) {
        StepExecutionResult result = new StepExecutionResult();
        result.setSuccess(true);
        result.setOutputData(new HashMap<>());
        
        try {
            String notifyType = stepParams.getOrDefault("notifyType", "log");
            String message = stepParams.getOrDefault("message", "Pipeline执行完成");
            // String status = stepParams.getOrDefault("status", "SUCCESS");
            
            log.info("执行通知步骤 [{}]: {}", notifyType, message);
            
            // TODO: 根据notifyType调用对应的通知服务
            // - feishu: 调用飞书API
            // - dingtalk: 调用钉钉API
            // - wechatwork: 调用企业微信API
            // - email: 发送邮件
            
            result.setSuccess(true);
            
        } catch (Exception e) {
            log.error("通知步骤执行失败", e);
            result.setSuccess(false);
            result.setErrorMessage("通知步骤执行失败: " + e.getMessage());
        }
        
        return result;
    }
    
    @Override
    public boolean supports(String stepType) {
        return "notify".equalsIgnoreCase(stepType);
    }
}

