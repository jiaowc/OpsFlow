package com.opsflow.service;

import com.opsflow.api.dto.DeployModuleItemDTO;
import com.opsflow.api.dto.DeployTaskDTO;
import com.opsflow.dao.model.DeployTask;

import java.util.List;

/**
 * 上线任务协作编辑：锁定 / 解锁 / 发布，以及按模块归属合并编辑。
 */
public interface DeployTaskEditService {

    boolean isLocked(DeployTask task);

    boolean isCreator(DeployTask task, String username, Long userId);

    boolean canUnlock(DeployTask task, String username, Long userId, boolean hasUnlockPerm);

    void assertEditable(DeployTask task);

    /**
     * 合并模块列表：仅允许改删本人模块；他人模块必须原样保留；新增模块归属当前用户。
     */
    List<DeployModuleItemDTO> mergeModulesByOwnership(
            DeployTask task,
            List<DeployModuleItemDTO> requested,
            List<String> requestedImages,
            String username,
            Long userId);

    DeployTask lock(Long taskId, String username, Long userId, boolean hasUnlockPerm);

    DeployTask unlock(Long taskId, String username, Long userId, boolean hasUnlockPerm);

    /** 已锁定后提交审批（不触发 CD） */
    DeployTask submitApproval(Long taskId, String username, Long userId, boolean hasUnlockPerm);

    /** 审批通过后手动发布，启动 CD */
    DeployTask publish(Long taskId, String username, Long userId, boolean hasUnlockPerm);

    void fillActionFlags(DeployTaskDTO dto, DeployTask task, String username, Long userId,
                         boolean hasCreate, boolean hasUnlock);
}
