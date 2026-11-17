package com.opsflow.service;

import lombok.Getter;

/**
 * 上线任务的当前操作人：身份 + 与上线任务相关的权限。
 * <p>
 * 由 Web 层从登录会话构造后传给 {@link DeployTaskService}，业务层不再直接接触 HttpSession。
 * </p>
 */
@Getter
public final class DeployTaskOperator {

    private final String username;
    private final Long userId;
    /** deploy:create */
    private final boolean canCreate;
    /** deploy:unlock */
    private final boolean canUnlock;
    /** deploy:delete */
    private final boolean canDelete;
    /** deploy:approve：审批人可跨项目查看上线任务 */
    private final boolean canApprove;

    public DeployTaskOperator(String username, Long userId,
                              boolean canCreate, boolean canUnlock, boolean canDelete, boolean canApprove) {
        this.username = username;
        this.userId = userId;
        this.canCreate = canCreate;
        this.canUnlock = canUnlock;
        this.canDelete = canDelete;
        this.canApprove = canApprove;
    }
}
