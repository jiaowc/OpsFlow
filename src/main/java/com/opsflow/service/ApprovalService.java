package com.opsflow.service;

import com.opsflow.api.dto.ApprovalActionRequest;
import com.opsflow.api.dto.ApprovalRecordDTO;
import com.opsflow.dao.model.DeployTask;

import java.util.List;

/**
 * 上线任务审批服务：按审批流生成记录、查询待办、处理通过/拒绝。
 */
public interface ApprovalService {

    /**
     * 为上线任务启动审批，按审批流步骤生成待审记录并触发通知。
     *
     * @param task 已绑定审批流的上线任务
     */
    void startApproval(DeployTask task);

    /**
     * 查询指定上线任务的全部审批记录（含当前用户是否可操作）。
     *
     * @param taskId          上线任务 ID
     * @param currentUsername 当前登录用户名，用于判断 actionable
     */
    List<ApprovalRecordDTO> listByTask(Long taskId, String currentUsername);

    /**
     * 查询当前用户待处理的审批记录列表。
     *
     * @param currentUsername 当前登录用户名
     */
    List<ApprovalRecordDTO> listMyPending(String currentUsername);

    /**
     * 通过指定审批记录；若当前步骤全部通过则推进任务至下一业务阶段。
     *
     * @param recordId        审批记录 ID
     * @param request         审批意见等附加信息，可为 null
     * @param currentUsername 操作人用户名
     */
    ApprovalRecordDTO approve(Long recordId, ApprovalActionRequest request, String currentUsername);

    /**
     * 拒绝指定审批记录，并将关联上线任务标记为审批拒绝。
     *
     * @param recordId        审批记录 ID
     * @param request         拒绝原因等附加信息，可为 null
     * @param currentUsername 操作人用户名
     */
    ApprovalRecordDTO reject(Long recordId, ApprovalActionRequest request, String currentUsername);
}
