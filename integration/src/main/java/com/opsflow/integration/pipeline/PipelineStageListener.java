package com.opsflow.integration.pipeline;

/**
 * 流水线阶段执行回调（用于持久化阶段状态）
 */
public interface PipelineStageListener {

    void onStageStart(int stepOrder, String stepType, String stepName);

    void onStageComplete(int stepOrder, boolean success, String log, String errorMessage, long durationMs);
}
