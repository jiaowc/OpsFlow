package com.opsflow.service;

import com.opsflow.dao.model.BuildJob;

/**
 * 流水线结果通知：站内信发给触发人；外部通道由 {@link com.opsflow.spi.JobNotifyExtension} 提供。
 */
public interface PipelineNotifyService {

    /**
     * 构建任务到达终态（SUCCESS / FAILED）后调用。异步发送，立即返回，任何失败都不会抛给调用方。
     *
     * @param job 已写好终态、错误信息、结束时间的构建任务
     */
    void notifyJobFinished(BuildJob job);

    /**
     * 流水线里的「通知」步骤：把自定义内容同步交给外部通道扩展发送。
     *
     * @return 给步骤日志用的结果说明
     */
    String sendStepMessage(Long buildJobId, String message);
}
