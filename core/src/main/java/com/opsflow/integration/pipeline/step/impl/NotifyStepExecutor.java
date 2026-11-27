package com.opsflow.integration.pipeline.step.impl;

import com.opsflow.integration.pipeline.PipelineExecutionContext;
import com.opsflow.integration.pipeline.StageLogHelper;
import com.opsflow.integration.pipeline.step.StepExecutor;
import com.opsflow.integration.pipeline.step.StepExecutionResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import com.opsflow.service.PipelineNotifyService;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 通知步骤执行器：把步骤参数里的 message 发到外部通知通道（通道由扩展提供并配置）。
 * <p>步骤参数 {@code channelIds}（逗号分隔的通道 id）指定了通道时只发这些通道，没指定时按通道规则匹配。</p>
 * <p>通知是尽力而为：没有可用通道、未开通集成通知或发送失败都只写进步骤日志，步骤仍算成功，
 * 不让一条通知拖垮整条流水线。构建/发布的成功失败结果通知不依赖本步骤，由流水线结束时统一发送。</p>
 */
@Slf4j
@Component
public class NotifyStepExecutor implements StepExecutor {

    static final String DEFAULT_MESSAGE = "Pipeline执行完成";

    @Autowired(required = false)
    private PipelineNotifyService pipelineNotifyService;

    /** 解析逗号分隔的通道 id：去空白、去重、保持顺序；不是正整数的片段放进 invalid */
    static List<Long> parseChannelIds(String raw, List<String> invalid) {
        Set<Long> ids = new LinkedHashSet<>();
        if (raw != null) {
            for (String part : raw.split(",")) {
                String token = part.trim();
                if (token.isEmpty()) {
                    continue;
                }
                try {
                    long id = Long.parseLong(token);
                    if (id > 0) {
                        ids.add(id);
                        continue;
                    }
                } catch (NumberFormatException ignored) {
                    // 下面统一记为无效
                }
                if (invalid != null) {
                    invalid.add(token);
                }
            }
        }
        return new ArrayList<>(ids);
    }

    @Override
    public StepExecutionResult execute(String stepType, Map<String, String> stepParams, PipelineExecutionContext context) {
        StepExecutionResult result = new StepExecutionResult();
        result.setSuccess(true);
        result.setOutputData(new HashMap<>());

        try {
            String notifyType = stepParams.getOrDefault("notifyType", "log");
            String rawMessage = stepParams.get("message");
            // 老数据没有消息内容时用默认文案
            String message = rawMessage != null && !rawMessage.trim().isEmpty() ? rawMessage : DEFAULT_MESSAGE;
            if (pipelineNotifyService != null) {
                Map<String, String> runtime = new HashMap<>();
                String image = stepParams.get("imageFullName");
                runtime.put("image", image != null && !image.trim().isEmpty() ? image : stepParams.get("image"));
                try {
                    String rendered = pipelineNotifyService.renderStepMessage(context.getBuildJobId(), message, runtime);
                    if (rendered != null && !rendered.trim().isEmpty()) {
                        message = rendered;
                    }
                } catch (Exception e) {
                    log.warn("通知步骤替换变量失败，按原文发送 jobId={}", context.getBuildJobId(), e);
                }
            }
            List<String> invalidIds = new ArrayList<>();
            List<Long> channelIds = parseChannelIds(stepParams.get("channelIds"), invalidIds);
            String status = stepParams.getOrDefault("status", "SUCCESS");

            StringBuilder logBuilder = StageLogHelper.start(context, "发送通知");
            StageLogHelper.appendKv(logBuilder, context, "通知类型", notifyType);
            StageLogHelper.appendKv(logBuilder, context, "状态", status);
            StageLogHelper.appendKv(logBuilder, context, "消息", message);
            // 社区版没有通道，企业版没选通道时按规则匹配，这两种情况都不写这一行，免得误以为有通道可选
            if (!channelIds.isEmpty()) {
                StageLogHelper.appendKv(logBuilder, context, "指定通道", channelIds.toString());
            }
            if (!invalidIds.isEmpty()) {
                StageLogHelper.appendKv(logBuilder, context, "忽略无效通道 id", String.join(", ", invalidIds));
            }
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
                    outcome = pipelineNotifyService.sendStepMessage(context.getBuildJobId(), message, channelIds);
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
