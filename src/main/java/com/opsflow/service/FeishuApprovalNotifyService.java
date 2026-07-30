package com.opsflow.service;

import com.alibaba.fastjson.JSONObject;
import com.opsflow.dao.model.ApprovalRecord;
import com.opsflow.dao.model.DeployTask;

/**
 * 飞书审批卡片通知
 */
public interface FeishuApprovalNotifyService {

    /**
     * 向当前 pending 审批人推送交互卡片
     */
    void notifyPendingApproval(ApprovalRecord record, DeployTask task);

    /**
     * 按最终状态刷新卡片（同意/拒绝/取消）
     */
    void refreshCard(ApprovalRecord record, DeployTask task, String finalStatus, String operatorName);

    /**
     * 构建审批待办卡片
     */
    JSONObject buildPendingCard(ApprovalRecord record, DeployTask task);

    /**
     * 构建审批结果卡片（无按钮）
     */
    JSONObject buildResultCard(ApprovalRecord record, DeployTask task, String finalStatus, String operatorName);
}
