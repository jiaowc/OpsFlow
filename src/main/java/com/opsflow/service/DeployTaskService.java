package com.opsflow.service;

import com.opsflow.api.dto.DeployTaskDTO;

import java.util.List;

/**
 * 上线任务：创建、协作编辑、锁定、提审/发布、查询、删除。
 * <p>
 * 实现见 {@code com.opsflow.service.impl.DeployTaskServiceImpl}（门面）与
 * {@code com.opsflow.service.impl.deploy} 包。审批通过后的 CD 执行见 {@link DeployTaskCdService}。
 * </p>
 */
public interface DeployTaskService {

    /** 创建上线任务，进入协作编辑态（未锁定、未审批） */
    DeployTaskDTO createTask(DeployTaskDTO request, DeployTaskOperator operator);

    /** 上线任务列表，按创建时间倒序；projectId 为空时按操作人可见范围过滤 */
    List<DeployTaskDTO> listTasks(String status, Long projectId, DeployTaskOperator operator);

    /** 任务详情；不存在时返回 {@code null} */
    DeployTaskDTO getTask(Long id, DeployTaskOperator operator);

    /** 更新任务：创建人可改元信息，每人只能增删改自己的模块 */
    DeployTaskDTO updateTask(Long id, DeployTaskDTO request, DeployTaskOperator operator);

    DeployTaskDTO lockTask(Long id, DeployTaskOperator operator);

    DeployTaskDTO unlockTask(Long id, DeployTaskOperator operator);

    DeployTaskDTO submitApproval(Long id, DeployTaskOperator operator);

    DeployTaskDTO publishTask(Long id, DeployTaskOperator operator);

    /** 删除任务及其关联的 CD Job、阶段、审批记录 */
    boolean deleteTask(Long id);
}
