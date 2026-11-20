package com.opsflow.service.impl.deploy;

import com.opsflow.common.exception.BusinessException;
import com.opsflow.dao.mapper.DeployTaskMapper;
import com.opsflow.dao.model.DeployTask;
import com.opsflow.service.DeployTaskOperator;
import com.opsflow.service.ProjectAccessService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 上线任务的项目级访问控制：谁能看、谁能操作。
 */
@Component
public class DeployTaskAccessGuard {

    @Autowired
    private DeployTaskMapper deployTaskMapper;

    @Autowired
    private ProjectAccessService projectAccessService;

    /**
     * 平台管理员与审批人（deploy:approve）可跨项目查看上线任务
     */
    public boolean canViewAllTasks(DeployTaskOperator operator) {
        return projectAccessService.isPlatformAdmin() || operator.isCanApprove();
    }

    public void assertProjectTasksViewable(Long projectId, DeployTaskOperator operator) {
        if (!canViewAllTasks(operator)) {
            projectAccessService.assertCanViewProject(projectId);
        }
    }

    public void assertTaskOperable(Long taskId) {
        DeployTask task = deployTaskMapper.selectById(taskId);
        if (task == null) {
            throw new BusinessException("任务不存在");
        }
        assertTaskOperable(task);
    }

    /**
     * 历史任务（project_id 为空）不做项目校验
     */
    public void assertTaskOperable(DeployTask task) {
        if (task.getProjectId() != null) {
            projectAccessService.assertCanOperateProject(task.getProjectId());
        }
    }
}
