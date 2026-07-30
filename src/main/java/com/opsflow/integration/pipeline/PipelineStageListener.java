package com.opsflow.integration.pipeline;

/**
 * 流水线阶段执行回调（用于持久化阶段状态与实时日志）
 */
public interface PipelineStageListener {

    void onStageStart(int stepOrder, String stepType, String stepName);

    /**
     * 执行过程中追加日志片段（可多次调用，用于实时查看）
     */
    void onStageLog(int stepOrder, String chunk);

    void onStageComplete(int stepOrder, boolean success, String log, String errorMessage, long durationMs);

    /**
     * 跳过步骤（如回滚任务跳过 CI 阶段）
     */
    default void onStageSkipped(int stepOrder, String reason) {
        onStageComplete(stepOrder, true, reason, null, 0L);
    }
}
