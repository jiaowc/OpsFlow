package com.opsflow.service.impl.deploy;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opsflow.api.dto.DeployModuleItemDTO;
import com.opsflow.api.dto.DeployTaskDTO;
import com.opsflow.common.constant.DeployModes;
import com.opsflow.common.constant.PipelineTypes;
import com.opsflow.common.exception.BusinessException;
import com.opsflow.common.util.DeployModuleJson;
import com.opsflow.dao.mapper.ApprovalFlowMapper;
import com.opsflow.dao.mapper.ApprovalRecordMapper;
import com.opsflow.dao.mapper.BuildJobMapper;
import com.opsflow.dao.mapper.BuildJobStageMapper;
import com.opsflow.dao.mapper.ClusterMapper;
import com.opsflow.dao.mapper.DeployTaskMapper;
import com.opsflow.dao.mapper.EnvMapper;
import com.opsflow.dao.mapper.PipelineMapper;
import com.opsflow.dao.mapper.ProjectMapper;
import com.opsflow.dao.model.ApprovalFlow;
import com.opsflow.dao.model.ApprovalRecord;
import com.opsflow.dao.model.BuildJob;
import com.opsflow.dao.model.Cluster;
import com.opsflow.dao.model.DeployTask;
import com.opsflow.dao.model.Env;
import com.opsflow.dao.model.Pipeline;
import com.opsflow.license.LicenseFeatures;
import com.opsflow.service.ApprovalService;
import com.opsflow.service.DeployImageVerifyService;
import com.opsflow.service.DeployTaskEditService;
import com.opsflow.service.DeployTaskOperator;
import com.opsflow.service.LicenseService;
import com.opsflow.service.ProjectAccessService;
import com.opsflow.service.impl.ApprovalNotifyDispatchServiceImpl;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 上线任务的写操作：创建（含校验链）、更新、删除。
 * 锁定 / 解锁 / 提审 / 发布见 {@link DeployTaskEditService}。
 */
@Component
public class DeployTaskCommandService {

    @Autowired
    private DeployTaskMapper deployTaskMapper;

    @Autowired
    private EnvMapper envMapper;

    @Autowired
    private DeployImageVerifyService deployImageVerifyService;

    @Autowired
    private ClusterMapper clusterMapper;

    @Autowired
    private ApprovalFlowMapper approvalFlowMapper;

    @Autowired
    private ApprovalRecordMapper approvalRecordMapper;

    @Autowired
    private PipelineMapper pipelineMapper;

    @Autowired
    private BuildJobMapper buildJobMapper;

    @Autowired
    private BuildJobStageMapper buildJobStageMapper;

    @Autowired
    private LicenseService licenseService;

    @Autowired
    private DeployTaskEditService deployTaskEditService;

    @Autowired
    private ProjectAccessService projectAccessService;

    @Autowired
    private ProjectMapper projectMapper;

    @Autowired
    private DeployModuleImageResolver deployModuleImageResolver;

    @Autowired
    private DeployTaskAccessGuard deployTaskAccessGuard;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 创建上线任务：完成业务校验、模块镜像规范化并落库，进入协作编辑态（未锁定、未审批）。
     * <p>
     * <b>业务目的</b>：前端任务页的「创建上线」入口；多人可在锁定前各自编辑自己的模块，
     * 创建人锁定后由「发布」调用 {@link ApprovalService#startApproval} 进入审批与 CD。
     * </p>
     * <p>
     * <b>校验链</b>（按顺序，任一失败即抛 {@link BusinessException}）：
     * <ol>
     *   <li>任务名、上线模块列表非空</li>
     *   <li>集群 ID 非空且集群存在、未禁用</li>
     *   <li>k8sNamespace 非空（与集群共同确定部署目标）</li>
     *   <li>审批流 ID 非空且存在、未禁用</li>
     *   <li>CD 流水线模版 ID 非空且存在、启用、类型为 CD（与 CD 服务侧二次校验形成双保险）</li>
     *   <li>通知渠道至少一种（站内信 / 飞书）</li>
     * </ol>
     * </p>
     * <p>
     * <b>环境 ID 兼容</b>：若请求未带 deployEnvIds，按 clusterId + k8sNamespace 查询环境表自动填充，
     * 供 CD 侧解析部署环境时回退匹配使用。
     * </p>
     * <p>
     * <b>模块规范化</b>：{@link DeployModuleImageResolver#normalizeDeployModules} 将相对路径补全为
     * {@code registry/project/service:tag} 后再 JSON 序列化存入 deployModules，
     * 保证库内数据与 CD 执行、列表展示使用同一套完整镜像格式。
     * </p>
     * <p>
     * <b>初始状态</b>：taskStatus=pending，approvalStatus=none，locked=0；不自动发起审批。
     * 展示用 DTO（含模块归属与操作标志）由 {@link DeployTaskViewAssembler#toDTO} 组装。
     * </p>
     *
     * @param request 前端提交的任务配置
     * @param operator 当前操作人，creatorName 取其用户名
     * @return 创建后的任务实体（由调用方组装为展示 DTO）
     */
    public DeployTask create(DeployTaskDTO request, DeployTaskOperator operator) {
        // 上线任务当前强依赖审批流，需 License 开通 deploy_approval
        licenseService.requireFeature(LicenseFeatures.DEPLOY_APPROVAL);
        String username = operator.getUsername();

        if (request.getProjectId() == null || projectMapper.selectById(request.getProjectId()) == null) {
            throw new BusinessException("请选择所属项目");
        }
        projectAccessService.assertCanOperateProject(request.getProjectId());
        if (request.getTaskName() == null || request.getTaskName().trim().isEmpty()) {
            throw new BusinessException("任务名称不能为空");
        }
        if (request.getDeployModules() == null || request.getDeployModules().isEmpty()) {
            throw new BusinessException("请至少选择一个上线模块");
        }
        if (request.getClusterId() == null) {
            throw new BusinessException("请选择上线集群");
        }
        if (request.getK8sNamespace() == null || request.getK8sNamespace().trim().isEmpty()) {
            throw new BusinessException("请选择上线 Namespace");
        }
        Cluster cluster = clusterMapper.selectById(request.getClusterId());
        if (cluster == null) {
            throw new BusinessException("上线集群不存在");
        }
        if (cluster.getStatus() != null && cluster.getStatus() == 0) {
            throw new BusinessException("上线集群已禁用");
        }
        // 兼容：若环境中存在匹配集群+namespace 的记录，写入 deployEnvIds
        List<Long> resolvedEnvIds = request.getDeployEnvIds();
        if (resolvedEnvIds == null || resolvedEnvIds.isEmpty()) {
            QueryWrapper<Env> envQuery = new QueryWrapper<>();
            envQuery.eq("cluster_id", request.getClusterId());
            envQuery.eq("k8s_namespace", request.getK8sNamespace().trim());
            List<Env> matchedEnvs = envMapper.selectList(envQuery);
            resolvedEnvIds = matchedEnvs.stream().map(Env::getId).collect(Collectors.toList());
        }
        if (resolvedEnvIds == null || resolvedEnvIds.isEmpty()) {
            throw new BusinessException("所选集群和 Namespace 未关联生产环境，不能创建上线任务");
        }
        List<Env> deployEnvs = envMapper.selectBatchIds(resolvedEnvIds);
        if (deployEnvs == null || deployEnvs.isEmpty()) {
            throw new BusinessException("部署环境不存在");
        }
        boolean allProd = deployEnvs.stream().allMatch(Env::isProdEnv);
        if (!allProd) {
            throw new BusinessException("创建上线任务只能选择生产环境，请检查该集群关联的环境类型");
        }
        if (request.getApprovalFlowId() == null) {
            throw new BusinessException("请选择审批流");
        }
        ApprovalFlow flow = approvalFlowMapper.selectById(request.getApprovalFlowId());
        if (flow == null) {
            throw new BusinessException("审批流不存在");
        }
        if (flow.getStatus() != null && flow.getStatus() == 0) {
            throw new BusinessException("审批流已禁用");
        }
        if (request.getPipelineTemplateId() == null) {
            throw new BusinessException("请选择 CD 流水线模版");
        }
        Pipeline pipeline = pipelineMapper.selectById(request.getPipelineTemplateId());
        if (pipeline == null || pipeline.getStatus() == null || pipeline.getStatus() != 1) {
            throw new BusinessException("CD 流水线模版不存在或已禁用");
        }
        String pipelineType = PipelineTypes.normalize(pipeline.getPipelineType());
        if (!PipelineTypes.CD.equals(pipelineType)) {
            throw new BusinessException("上线任务只能选择类型为 CD 的流水线模版");
        }

        String deployMode = DeployModes.normalize(request.getDeployMode());
        int deployParallelism = DeployModes.resolveParallelism(
                deployMode, request.getDeployParallelism());

        String notifyChannels = sanitizeNotifyChannels(request.getNotifyChannels());
        if (!StringUtils.hasText(notifyChannels)) {
            throw new BusinessException("请至少选择一种通知方式（站内信 / 飞书）");
        }

        List<String> normalizedModules = deployModuleImageResolver.normalizeDeployModules(request.getDeployModules());
        deployImageVerifyService.assertImagesExist(normalizedModules);
        Long userId = operator.getUserId();
        List<DeployModuleItemDTO> moduleItems = DeployModuleJson.fromImages(normalizedModules, username, userId);

        DeployTask task = new DeployTask();
        BeanUtils.copyProperties(request, task, "deployModules", "deployEnvIds", "approvalRecords",
                "clusterName", "notifyChannels", "pipelineTemplateName", "buildJobIds",
                "deployModuleDetails", "deployModuleItems", "deployMode", "deployParallelism",
                "locked", "lockedBy", "lockedAt", "canEdit", "canDelete", "canLock", "canUnlock", "canPublish",
                "canSubmitApproval", "showPublish");
        task.setClusterId(request.getClusterId());
        task.setK8sNamespace(request.getK8sNamespace().trim());
        task.setNotifyChannels(notifyChannels);
        task.setPipelineTemplateId(request.getPipelineTemplateId());
        task.setDeployMode(deployMode);
        task.setDeployParallelism(deployParallelism);

        try {
            task.setDeployModules(DeployModuleJson.writeItems(moduleItems));
            task.setDeployEnvs(objectMapper.writeValueAsString(resolvedEnvIds));
        } catch (Exception e) {
            throw new BusinessException("任务配置格式错误");
        }

        String taskNumber = "TASK-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
        task.setTaskNumber(taskNumber);
        task.setTaskStatus("pending");
        // 创建后进入协作编辑态：未锁定、未发起审批；发布时再 startApproval
        task.setApprovalStatus("none");
        task.setLocked(0);
        task.setCreatorId(userId);
        task.setCreatorName(username);
        task.setCreateTime(LocalDateTime.now());
        task.setUpdateTime(LocalDateTime.now());

        deployTaskMapper.insert(task);

        DeployTask saved = deployTaskMapper.selectById(task.getId());
        return saved != null ? saved : task;
    }

    /**
     * 更新上线任务。未锁定时可协作编辑：每人只能增删改自己的模块；任务元信息仅创建人可改。
     */
    public DeployTask update(Long id, DeployTaskDTO request, DeployTaskOperator operator) {
        DeployTask task = deployTaskMapper.selectById(id);
        if (task == null) {
            throw new BusinessException("任务不存在");
        }
        deployTaskEditService.assertEditable(task);
        deployTaskAccessGuard.assertTaskOperable(task);

        String username = operator.getUsername();
        Long userId = operator.getUserId();
        boolean creator = deployTaskEditService.isCreator(task, username, userId);

        if (creator) {
            if (StringUtils.hasText(request.getTaskName())) {
                task.setTaskName(request.getTaskName().trim());
            }
            if (request.getDescription() != null) {
                task.setDescription(request.getDescription());
            }
            if (request.getClusterId() != null) {
                task.setClusterId(request.getClusterId());
            }
            if (StringUtils.hasText(request.getK8sNamespace())) {
                task.setK8sNamespace(request.getK8sNamespace().trim());
            }
            if (request.getApprovalFlowId() != null) {
                task.setApprovalFlowId(request.getApprovalFlowId());
            }
            if (request.getPipelineTemplateId() != null) {
                task.setPipelineTemplateId(request.getPipelineTemplateId());
            }
            if (StringUtils.hasText(request.getDeployMode())) {
                String deployMode = DeployModes.normalize(request.getDeployMode());
                task.setDeployMode(deployMode);
                task.setDeployParallelism(DeployModes.resolveParallelism(deployMode, request.getDeployParallelism()));
            }
            if (request.getNotifyChannels() != null) {
                String notifyChannels = sanitizeNotifyChannels(request.getNotifyChannels());
                if (StringUtils.hasText(notifyChannels)) {
                    task.setNotifyChannels(notifyChannels);
                }
            }
            if (request.getDeployEnvIds() != null) {
                try {
                    task.setDeployEnvs(objectMapper.writeValueAsString(request.getDeployEnvIds()));
                } catch (Exception e) {
                    throw new BusinessException("环境配置格式错误");
                }
            }
        } else if (request.getTaskName() != null || request.getClusterId() != null
                || request.getApprovalFlowId() != null || request.getPipelineTemplateId() != null
                || request.getDeployEnvIds() != null || request.getDeployMode() != null) {
            // 非创建人仅允许改模块；若误传元信息则忽略，不报错
        }

        if (request.getDeployModuleItems() != null || request.getDeployModules() != null) {
            List<DeployModuleItemDTO> merged = deployTaskEditService.mergeModulesByOwnership(
                    task, request.getDeployModuleItems(), request.getDeployModules(), username, userId);
            List<String> normalized = deployModuleImageResolver.normalizeDeployModules(DeployModuleJson.toImageList(merged));
            deployImageVerifyService.assertImagesExist(normalized);
            for (int i = 0; i < merged.size(); i++) {
                merged.get(i).setImage(normalized.get(i));
            }
            try {
                task.setDeployModules(DeployModuleJson.writeItems(merged));
            } catch (Exception e) {
                throw new BusinessException("任务配置格式错误");
            }
        }

        task.setUpdateTime(LocalDateTime.now());
        deployTaskMapper.updateById(task);
        return task;
    }

    /**
     * 删除上线任务及其关联的 CD Job、阶段、审批记录。
     * <p>
     * 整个删除在一个事务里：任何一步失败都整体回滚，不会留下「Job 删了、任务还在」的半删数据。
     * </p>
     *
     * @param id 任务 ID
     * @return 是否删除成功
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean delete(Long id) {
        DeployTask task = deployTaskMapper.selectById(id);
        if (task == null) {
            throw new BusinessException("任务不存在");
        }
        deployTaskAccessGuard.assertTaskOperable(task);
        String status = task.getTaskStatus() == null ? "" : task.getTaskStatus().trim().toLowerCase();
        if ("deploying".equals(status) || "building".equals(status)) {
            throw new BusinessException("任务发布中，无法删除");
        }
        List<BuildJob> linkedJobs = buildJobMapper.selectList(
                new QueryWrapper<BuildJob>().eq("deploy_task_id", id));
        for (BuildJob job : linkedJobs) {
            buildJobStageMapper.delete(new QueryWrapper<com.opsflow.dao.model.BuildJobStage>()
                    .eq("build_job_id", job.getId()));
            buildJobMapper.deleteById(job.getId());
        }
        approvalRecordMapper.delete(new QueryWrapper<ApprovalRecord>().eq("task_id", id));
        return deployTaskMapper.deleteById(id) > 0;
    }

    /**
     * 未开通 notify_integration 时去掉飞书渠道，避免 Community 写入无效配置。
     */
    private String sanitizeNotifyChannels(java.util.List<String> channels) {
        String normalized = ApprovalNotifyDispatchServiceImpl.normalizeChannels(channels);
        if (!StringUtils.hasText(normalized)) {
            return normalized;
        }
        if (licenseService.isFeatureEnabled(LicenseFeatures.NOTIFY_INTEGRATION)) {
            return normalized;
        }
        if (!normalized.contains(ApprovalNotifyDispatchServiceImpl.CHANNEL_FEISHU)) {
            return normalized;
        }
        java.util.List<String> kept = new java.util.ArrayList<>();
        for (String part : normalized.split(",")) {
            String c = part.trim();
            if (ApprovalNotifyDispatchServiceImpl.CHANNEL_INBOX.equals(c)) {
                kept.add(c);
            }
        }
        return ApprovalNotifyDispatchServiceImpl.normalizeChannels(kept);
    }
}
