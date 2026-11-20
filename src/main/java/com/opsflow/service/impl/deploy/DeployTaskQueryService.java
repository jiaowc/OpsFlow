package com.opsflow.service.impl.deploy;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.opsflow.dao.mapper.DeployTaskMapper;
import com.opsflow.dao.model.DeployTask;
import com.opsflow.service.DeployTaskOperator;
import com.opsflow.service.ProjectAccessService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

/**
 * 上线任务的只读查询：列表（按项目可见范围过滤）与详情。
 */
@Component
public class DeployTaskQueryService {

    @Autowired
    private DeployTaskMapper deployTaskMapper;

    @Autowired
    private ProjectAccessService projectAccessService;

    @Autowired
    private DeployTaskAccessGuard deployTaskAccessGuard;

    /**
     * 查询上线任务列表。
     *
     * @param status 可选，按任务状态过滤：pending / deploying / success / failed 等
     * @param projectId 可选，指定项目；为空时按操作人可见范围过滤
     * @param operator 当前操作人
     * @return 任务列表，按创建时间倒序
     */
    public List<DeployTask> list(String status, Long projectId, DeployTaskOperator operator) {
        QueryWrapper<DeployTask> wrapper = new QueryWrapper<>();
        if (projectId != null) {
            deployTaskAccessGuard.assertProjectTasksViewable(projectId, operator);
            wrapper.eq("project_id", projectId);
        } else if (!deployTaskAccessGuard.canViewAllTasks(operator)) {
            Set<Long> projectIds = projectAccessService.accessibleProjectIds();
            if (projectIds != null) {
                wrapper.and(w -> {
                    w.isNull("project_id");
                    if (!projectIds.isEmpty()) {
                        w.or().in("project_id", projectIds);
                    }
                });
            }
        }
        if (status != null && !status.isEmpty()) {
            wrapper.eq("task_status", status);
        }
        wrapper.orderByDesc("create_time");
        return deployTaskMapper.selectList(wrapper);
    }

    /**
     * 查询单个上线任务。
     *
     * @return 任务实体；不存在时返回 {@code null}
     */
    public DeployTask get(Long id, DeployTaskOperator operator) {
        DeployTask task = deployTaskMapper.selectById(id);
        if (task == null) {
            return null;
        }
        if (task.getProjectId() != null) {
            deployTaskAccessGuard.assertProjectTasksViewable(task.getProjectId(), operator);
        }
        return task;
    }
}
