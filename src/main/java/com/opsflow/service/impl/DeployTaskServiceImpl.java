package com.opsflow.service.impl;

import com.opsflow.api.dto.DeployTaskDTO;
import com.opsflow.dao.model.DeployTask;
import com.opsflow.license.LicenseFeatures;
import com.opsflow.service.DeployTaskEditService;
import com.opsflow.service.DeployTaskOperator;
import com.opsflow.service.DeployTaskService;
import com.opsflow.service.LicenseService;
import com.opsflow.service.impl.deploy.DeployTaskAccessGuard;
import com.opsflow.service.impl.deploy.DeployTaskCommandService;
import com.opsflow.service.impl.deploy.DeployTaskQueryService;
import com.opsflow.service.impl.deploy.DeployTaskViewAssembler;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 上线任务服务门面：对外接口 {@link DeployTaskService}，具体实现分布在
 * {@code com.opsflow.service.impl.deploy} 包的各个 Bean 中：
 * <ul>
 *   <li>{@link DeployTaskCommandService}：创建（含校验链）、更新、删除；</li>
 *   <li>{@link DeployTaskQueryService}：列表与详情；</li>
 *   <li>{@link DeployTaskViewAssembler}：实体 → 展示 DTO；</li>
 *   <li>{@link DeployTaskAccessGuard}：项目级访问控制；</li>
 *   <li>{@link DeployTaskEditService}：锁定 / 解锁 / 提审 / 发布及操作标志。</li>
 * </ul>
 * 本类只负责编排调用顺序，不含业务规则。
 */
@Service
public class DeployTaskServiceImpl implements DeployTaskService {

    @Autowired
    private DeployTaskCommandService commandService;

    @Autowired
    private DeployTaskQueryService queryService;

    @Autowired
    private DeployTaskViewAssembler viewAssembler;

    @Autowired
    private DeployTaskAccessGuard accessGuard;

    @Autowired
    private DeployTaskEditService deployTaskEditService;

    @Autowired
    private LicenseService licenseService;

    @Override
    public DeployTaskDTO createTask(DeployTaskDTO request, DeployTaskOperator operator) {
        return viewAssembler.toDTO(commandService.create(request, operator), operator);
    }

    @Override
    public List<DeployTaskDTO> listTasks(String status, Long projectId, DeployTaskOperator operator) {
        return viewAssembler.toDTOs(queryService.list(status, projectId, operator), operator);
    }

    @Override
    public DeployTaskDTO getTask(Long id, DeployTaskOperator operator) {
        DeployTask task = queryService.get(id, operator);
        return task == null ? null : viewAssembler.toDTO(task, operator);
    }

    @Override
    public DeployTaskDTO updateTask(Long id, DeployTaskDTO request, DeployTaskOperator operator) {
        return viewAssembler.toDTO(commandService.update(id, request, operator), operator);
    }

    @Override
    public DeployTaskDTO lockTask(Long id, DeployTaskOperator operator) {
        accessGuard.assertTaskOperable(id);
        DeployTask task = deployTaskEditService.lock(id, operator.getUsername(), operator.getUserId(),
                operator.isCanUnlock());
        return viewAssembler.toDTO(task, operator);
    }

    @Override
    public DeployTaskDTO unlockTask(Long id, DeployTaskOperator operator) {
        accessGuard.assertTaskOperable(id);
        DeployTask task = deployTaskEditService.unlock(id, operator.getUsername(), operator.getUserId(),
                operator.isCanUnlock());
        return viewAssembler.toDTO(task, operator);
    }

    @Override
    public DeployTaskDTO submitApproval(Long id, DeployTaskOperator operator) {
        accessGuard.assertTaskOperable(id);
        licenseService.requireFeature(LicenseFeatures.DEPLOY_APPROVAL);
        DeployTask task = deployTaskEditService.submitApproval(id, operator.getUsername(), operator.getUserId(),
                operator.isCanUnlock());
        return viewAssembler.toDTO(task, operator);
    }

    @Override
    public DeployTaskDTO publishTask(Long id, DeployTaskOperator operator) {
        accessGuard.assertTaskOperable(id);
        licenseService.requireFeature(LicenseFeatures.DEPLOY_APPROVAL);
        DeployTask task = deployTaskEditService.publish(id, operator.getUsername(), operator.getUserId(),
                operator.isCanUnlock());
        return viewAssembler.toDTO(task, operator);
    }

    @Override
    public boolean deleteTask(Long id) {
        return commandService.delete(id);
    }
}
