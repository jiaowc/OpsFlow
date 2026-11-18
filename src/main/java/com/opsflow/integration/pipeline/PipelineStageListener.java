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

    /**
     * 步骤仍在执行时的周期性心跳（与是否有日志输出无关），
     * 用于刷新阶段的更新时间，避免长时间无输出的步骤被误判为僵死任务
     */
    default void onStageHeartbeat(int stepOrder) {
    }

    void onStageComplete(int stepOrder, boolean success, String log, String errorMessage, long durationMs);

    /**
     * 跳过步骤（如回滚任务跳过 CI 阶段）
     */
    default void onStageSkipped(int stepOrder, String reason) {
        onStageComplete(stepOrder, true, reason, null, 0L);
    }
}
