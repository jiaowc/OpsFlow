package com.opsflow.integration.pipeline.step.impl;

import com.opsflow.integration.pipeline.PipelineExecutionContext;
import com.opsflow.integration.pipeline.StageLogHelper;
import com.opsflow.integration.pipeline.step.StepExecutor;
import com.opsflow.integration.pipeline.step.StepExecutionResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import com.opsflow.service.PipelineNotifyService;

import java.util.HashMap;
import java.util.Map;

/**
 * 通知步骤执行器：把步骤参数里的 message 发到当前项目匹配的外部通知通道（通道由扩展提供并配置）。
 * <p>通知是尽力而为：没有可用通道、未开通集成通知或发送失败都只写进步骤日志，步骤仍算成功，
 * 不让一条通知拖垮整条流水线。构建/发布的成功失败结果通知不依赖本步骤，由流水线结束时统一发送。</p>
 */
@Slf4j
@Component
public class NotifyStepExecutor implements StepExecutor {

    @Autowired(required = false)
    private PipelineNotifyService pipelineNotifyService;

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
            String outcome;
            if (pipelineNotifyService == null) {
                outcome = "通知服务不可用，仅记录日志";
            } else {
                try {
                    outcome = pipelineNotifyService.sendStepMessage(context.getBuildJobId(), message);
                } catch (Exception e) {
                    log.warn("通知步骤发送失败 jobId={}", context.getBuildJobId(), e);
                    outcome = "发送失败: " + e.getMessage();
                }
            }
            StageLogHelper.appendKv(logBuilder, context, "结果", outcome);
            log.info("执行通知步骤 [{}]: {}", notifyType, message);
            result.setLog(StageLogHelper.finalLog(context, logBuilder));
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
