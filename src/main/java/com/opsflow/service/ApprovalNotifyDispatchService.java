package com.opsflow.service;

import com.opsflow.dao.model.ApprovalRecord;
import com.opsflow.dao.model.DeployTask;

/**
 * 按任务通知渠道分发审批提醒（站内信 / 飞书，可组合）
 */
public interface ApprovalNotifyDispatchService {

    /**
     * 新待审步骤产生时，按任务配置的通知渠道发送提醒。
     *
     * @param record 当前待审记录
     * @param task   关联上线任务（含 notifyChannels 配置）
     */
    void notifyPending(ApprovalRecord record, DeployTask task);

    /**
     * 审批通过后更新通知状态（站内信已读、飞书卡片刷新等）。
     *
     * @param record   已处理的审批记录
     * @param task     关联上线任务
     * @param operator 审批操作人用户名
     */
    void notifyApproved(ApprovalRecord record, DeployTask task, String operator);

    /**
     * 审批拒绝后更新通知状态。
     *
     * @param record   已拒绝的审批记录
     * @param task     关联上线任务
     * @param operator 拒绝操作人用户名
     */
    void notifyRejected(ApprovalRecord record, DeployTask task, String operator);
}
