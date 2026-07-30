package com.opsflow.integration.pipeline.step.impl;

import com.opsflow.integration.pipeline.PipelineExecutionContext;
import com.opsflow.integration.pipeline.StageLogHelper;
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
            String status = stepParams.getOrDefault("status", "SUCCESS");

            StringBuilder logBuilder = StageLogHelper.start(context, "发送通知");
            StageLogHelper.appendKv(logBuilder, context, "通知类型", notifyType);
            StageLogHelper.appendKv(logBuilder, context, "状态", status);
            StageLogHelper.appendKv(logBuilder, context, "消息", message);
            if (context.getService() != null) {
                StageLogHelper.appendKv(logBuilder, context, "服务", context.getService().getCode());
            }
            if (context.getEnvironment() != null) {
                StageLogHelper.appendKv(logBuilder, context, "环境", context.getEnvironment().getName());
            }
            StageLogHelper.appendLine(logBuilder, "说明: 当前为日志记录模式，尚未对接外部通知通道");
            StageLogHelper.emitLine(context, "说明: 当前为日志记录模式，尚未对接外部通知通道");

            log.info("执行通知步骤 [{}]: {}", notifyType, message);
            result.setLog(logBuilder.toString());
            result.setSuccess(true);

        } catch (Exception e) {
            log.error("通知步骤执行失败", e);
            result.setSuccess(false);
            result.setErrorMessage("通知步骤执行失败: " + e.getMessage());
            StageLogHelper.emitLine(context, "[异常] " + e.getMessage());
        }

        return result;
    }

    @Override
    public boolean supports(String stepType) {
        return "notify".equalsIgnoreCase(stepType);
    }
}
