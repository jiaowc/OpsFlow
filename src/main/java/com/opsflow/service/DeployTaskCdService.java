package com.opsflow.service;

import com.opsflow.dao.model.DeployTask;

/**
 * 上线任务审批通过后的 CD（持续部署）执行服务。
 * <p>
 * 由 {@link com.opsflow.service.impl.ApprovalServiceImpl} 在审批终态（通过或无审批流）后调用；
 * 为每个上线模块创建 {@link com.opsflow.dao.model.BuildJob} 并异步执行 CD 流水线，
 * 全部模块的构建任务结束后汇总回写 {@link com.opsflow.dao.model.DeployTask} 的 taskStatus。
 * </p>
 */
public interface DeployTaskCdService {

    /**
     * 审批通过后调度上线任务 CD：必要时创建各模块 BuildJob，并启动当前应执行的那一条。
     * <p>幂等：若已有运行中 job 或无待启动 job 则跳过。</p>
     *
     * @param taskId 上线任务 ID
     */
    void scheduleApprovedTask(Long taskId);

    /**
     * 审批通过后触发 CD（兼容旧调用，内部转 {@link #scheduleApprovedTask(Long)}）。
     *
     * @param task 上线任务（至少需包含 id）
     */
    void startCdAfterApproved(DeployTask task);

    /**
     * 判断 CD job 是否允许手动重试（须为上线任务中第一条非成功且已失败的模块）。
     */
    boolean isRetryableCdJob(Long buildJobId);

    /**
     * CD job 不可重试时的原因说明（供前端展示）。
     */
    String getCdJobBlockedReason(Long buildJobId);

    /**
     * 单个 CD 构建任务结束后，汇总该上线任务下所有模块的执行结果并更新任务状态。
     * <p>任一模块失败则任务为 failed；全部成功则为 success；仍有进行中则为 deploying。</p>
     *
     * @param buildJobId 已结束的构建任务 ID
     */
    void onBuildJobFinished(Long buildJobId);

    /**
     * 重试失败的 CD 构建任务（重置阶段并重新执行）。
     *
     * @param buildJobId CD BuildJob ID
     * @return 构建响应
     */
    com.opsflow.api.dto.BuildResponse retryCdJob(Long buildJobId);
}
