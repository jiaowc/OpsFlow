package com.opsflow.web.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opsflow.api.dto.DeployModuleDetailDTO;
import com.opsflow.api.dto.DeployModuleItemDTO;
import com.opsflow.api.dto.DeployTaskDTO;
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
import com.opsflow.dao.mapper.ServiceMapper;
import com.opsflow.dao.model.ApprovalFlow;
import com.opsflow.dao.model.ApprovalRecord;
import com.opsflow.dao.model.BuildJob;
import com.opsflow.dao.model.Cluster;
import com.opsflow.dao.model.DeployTask;
import com.opsflow.dao.model.Env;
import com.opsflow.dao.model.Pipeline;
import com.opsflow.common.constant.DeployModes;
import com.opsflow.common.constant.PipelineTypes;
import com.opsflow.integration.harbor.HarborCredentialService;
import com.opsflow.service.ApprovalService;
import com.opsflow.service.DeployTaskEditService;
import com.opsflow.service.PermissionService;
import com.opsflow.service.impl.ApprovalNotifyDispatchServiceImpl;
import com.opsflow.license.LicenseFeatures;
import com.opsflow.service.LicenseService;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpSession;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;
import com.opsflow.web.security.RequiresPermission;

/**
 * 上线任务 REST 接口。
 * <p>
 * 上游：前端任务页提交创建/查询请求；下游：{@link com.opsflow.service.ApprovalService} 发起审批，
 * 审批全部通过后由 {@link com.opsflow.service.DeployTaskCdService} 按关联的 CD 流水线模版触发部署。
 * 创建任务时校验集群、Namespace、审批流与 CD 模版，并将上线模块规范化为完整 Harbor 镜像地址。
 * </p>
 */
@RestController
@RequestMapping("/api/task")
public class DeployTaskController {

    @Autowired
    private DeployTaskMapper deployTaskMapper;

    @Autowired
    @SuppressWarnings("unused")
    private ServiceMapper serviceMapper;

    @Autowired
    private EnvMapper envMapper;

    @Autowired
    private ClusterMapper clusterMapper;

    @Autowired
    private ApprovalFlowMapper approvalFlowMapper;

    @Autowired
    private ApprovalRecordMapper approvalRecordMapper;

    @Autowired
    private ApprovalService approvalService;

    @Autowired
    private PipelineMapper pipelineMapper;

    @Autowired
    private BuildJobMapper buildJobMapper;

    @Autowired
    private BuildJobStageMapper buildJobStageMapper;

    @Autowired
    private HarborCredentialService harborCredentialService;

    @Autowired
    private LicenseService licenseService;

    @Autowired
    private DeployTaskEditService deployTaskEditService;

    @Autowired
    private PermissionService permissionService;

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
     * 供 {@link com.opsflow.service.impl.DeployTaskCdServiceImpl#resolveEnv} 回退匹配使用。
     * </p>
     * <p>
     * <b>模块规范化</b>：{@link #normalizeDeployModules} 将相对路径补全为
     * {@code registry/project/service:tag} 后再 JSON 序列化存入 deployModules，
     * 保证库内数据与 CD 执行、列表展示使用同一套完整镜像格式。
     * </p>
     * <p>
     * <b>初始状态</b>：taskStatus=pending，approvalStatus=none，locked=0；不自动发起审批。
     * 返回 {@link #getTaskDTO} 含模块归属与操作标志（可编辑/锁定/发布）。
     * </p>
     *
     * @param request 前端提交的任务配置
     * @param session 当前登录会话，creatorName 取自 {@code user} 属性
     * @return 创建后的完整任务 DTO（含规范化模块、审批记录、关联名称等）
     */
    @RequiresPermission("deploy:create")
    @PostMapping("/create")
    public DeployTaskDTO createTask(@RequestBody DeployTaskDTO request, HttpSession session) {
        // 上线任务当前强依赖审批流，需 License 开通 deploy_approval
        licenseService.requireFeature(LicenseFeatures.DEPLOY_APPROVAL);
        String username = (String) session.getAttribute("user");

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

        String notifyChannels = ApprovalNotifyDispatchServiceImpl.normalizeChannels(request.getNotifyChannels());
        if (!StringUtils.hasText(notifyChannels)) {
            throw new BusinessException("请至少选择一种通知方式（站内信 / 飞书）");
        }

        List<String> normalizedModules = normalizeDeployModules(request.getDeployModules());
        Long userId = (Long) session.getAttribute("userId");
        List<DeployModuleItemDTO> moduleItems = DeployModuleJson.fromImages(normalizedModules, username, userId);

        DeployTask task = new DeployTask();
        BeanUtils.copyProperties(request, task, "deployModules", "deployEnvIds", "approvalRecords",
                "clusterName", "notifyChannels", "pipelineTemplateName", "buildJobIds",
                "pipelineParameters", "deployModuleDetails", "deployModuleItems", "deployMode", "deployParallelism",
                "locked", "lockedBy", "lockedAt", "canEdit", "canLock", "canUnlock", "canPublish",
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
        return getTaskDTO(saved != null ? saved : task, session);
    }

    /**
     * 查询上线任务列表。
     *
     * @param status 可选，按任务状态过滤：pending / deploying / success / failed 等
     * @param session 当前登录会话，用于填充审批可操作性
     * @return 任务列表，按创建时间倒序
     */
    @RequiresPermission("deploy:view")
    @GetMapping("/list")
    public List<DeployTaskDTO> listTasks(@RequestParam(required = false) String status, HttpSession session) {
        QueryWrapper<DeployTask> wrapper = new QueryWrapper<>();
        if (status != null && !status.isEmpty()) {
            wrapper.eq("task_status", status);
        }
        wrapper.orderByDesc("create_time");
        return deployTaskMapper.selectList(wrapper).stream()
                .map(task -> getTaskDTO(task, session))
                .collect(Collectors.toList());
    }

    /**
     * 查询单个上线任务详情。
     *
     * @param id 任务 ID
     * @param session 当前登录会话
     * @return 任务详情；不存在时返回 {@code null}
     */
    @RequiresPermission("deploy:view")
    @GetMapping("/{id}")
    public DeployTaskDTO getTask(@PathVariable Long id, HttpSession session) {
        DeployTask task = deployTaskMapper.selectById(id);
        if (task == null) {
            return null;
        }
        return getTaskDTO(task, session);
    }

    /**
     * 更新上线任务。未锁定时可协作编辑：每人只能增删改自己的模块；任务元信息仅创建人可改。
     */
    @RequiresPermission("deploy:create")
    @PutMapping("/{id}")
    public DeployTaskDTO updateTask(@PathVariable Long id, @RequestBody DeployTaskDTO request, HttpSession session) {
        DeployTask task = deployTaskMapper.selectById(id);
        if (task == null) {
            throw new BusinessException("任务不存在");
        }
        deployTaskEditService.assertEditable(task);

        String username = (String) session.getAttribute("user");
        Long userId = (Long) session.getAttribute("userId");
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
                String notifyChannels = ApprovalNotifyDispatchServiceImpl.normalizeChannels(request.getNotifyChannels());
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
            List<String> normalized = normalizeDeployModules(DeployModuleJson.toImageList(merged));
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
        return getTaskDTO(task, session);
    }

    @RequiresPermission("deploy:create")
    @PostMapping("/{id}/lock")
    public DeployTaskDTO lockTask(@PathVariable Long id, HttpSession session) {
        DeployTask task = deployTaskEditService.lock(id, sessionUsername(session), sessionUserId(session),
                sessionHasUnlock(session));
        return getTaskDTO(task, session);
    }

    @RequiresPermission({"deploy:create", "deploy:unlock"})
    @PostMapping("/{id}/unlock")
    public DeployTaskDTO unlockTask(@PathVariable Long id, HttpSession session) {
        DeployTask task = deployTaskEditService.unlock(id, sessionUsername(session), sessionUserId(session),
                sessionHasUnlock(session));
        return getTaskDTO(task, session);
    }

    @RequiresPermission("deploy:create")
    @PostMapping("/{id}/submit-approval")
    public DeployTaskDTO submitApproval(@PathVariable Long id, HttpSession session) {
        licenseService.requireFeature(LicenseFeatures.DEPLOY_APPROVAL);
        DeployTask task = deployTaskEditService.submitApproval(id, sessionUsername(session), sessionUserId(session),
                sessionHasUnlock(session));
        return getTaskDTO(task, session);
    }

    @RequiresPermission("deploy:create")
    @PostMapping("/{id}/publish")
    public DeployTaskDTO publishTask(@PathVariable Long id, HttpSession session) {
        licenseService.requireFeature(LicenseFeatures.DEPLOY_APPROVAL);
        DeployTask task = deployTaskEditService.publish(id, sessionUsername(session), sessionUserId(session),
                sessionHasUnlock(session));
        return getTaskDTO(task, session);
    }

    /**
     * 删除上线任务及其关联审批记录。
     *
     * @param id 任务 ID
     * @return 是否删除成功
     */
    @RequiresPermission("deploy:create")
    @DeleteMapping("/{id}")
    public boolean deleteTask(@PathVariable Long id) {
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
     * 将 DeployTask 实体组装为前端展示用 DTO，并补全关联信息与模块详情。
     * <p>
     * <b>业务目的</b>：库内仅存 ID 与 JSON 字段，列表/详情页需要可读名称、完整镜像、端口、审批可操作性等；
     * 本方法集中做「读时 enrich」，避免前端重复查表或解析镜像格式。
     * </p>
     * <p>
     * <b>主流程</b>：
     * <ol>
     *   <li>拷贝基础字段，解析 deployModules → {@link #normalizeDeployModules} → {@link #buildDeployModuleDetails}</li>
     *   <li>解析 notifyChannels、deployEnvIds 并关联环境名</li>
     *   <li>关联审批流名、CD 模版名、集群名、关联 BuildJob ID 列表</li>
     *   <li>{@link ApprovalService#listByTask} 填充审批记录（含当前用户 actionable 标志）</li>
     * </ol>
     * </p>
     * <p>
     * <b>为何列表/详情要补全镜像与端口</b>：历史任务或手工录入可能存相对路径；
     * 展示层统一规范化镜像地址，再通过服务表补 serviceName/servicePort（默认 8080），
     * 与 CD 创建 Job 时的逻辑对齐，用户看到的模块信息与即将部署的参数一致。
     * deployModuleDetails 供详情页结构化展示，deployModules 保留字符串列表兼容旧前端。
     * </p>
     * <p>
     * <b>边界</b>：deployModules/deployEnvs JSON 解析失败时静默跳过对应字段，不阻断整单返回。
     * </p>
     *
     * @param task 数据库实体
     * @param currentUsername 当前用户，用于审批记录 canApprove → actionable
     * @return 前端展示用 DTO
     */
    private DeployTaskDTO getTaskDTO(DeployTask task, HttpSession session) {
        String currentUsername = sessionUsername(session);
        Long userId = sessionUserId(session);
        boolean hasCreate = sessionHasPerm(session, "deploy:create");
        boolean hasUnlock = sessionHasUnlock(session);

        DeployTaskDTO dto = new DeployTaskDTO();
        BeanUtils.copyProperties(task, dto, "deployModules", "deployEnvs", "notifyChannels");

        try {
            if (task.getDeployModules() != null && !task.getDeployModules().isEmpty()) {
                List<DeployModuleItemDTO> items = DeployModuleJson.parseItems(task.getDeployModules());
                for (DeployModuleItemDTO item : items) {
                    if (!StringUtils.hasText(item.getOwnerName())) {
                        item.setOwnerName(task.getCreatorName());
                        item.setOwnerId(task.getCreatorId());
                    }
                }
                List<String> images = normalizeDeployModules(DeployModuleJson.toImageList(items));
                for (int i = 0; i < items.size() && i < images.size(); i++) {
                    items.get(i).setImage(images.get(i));
                }
                dto.setDeployModuleItems(items);
                dto.setDeployModules(images);
                dto.setDeployModuleDetails(buildDeployModuleDetails(items));
            }
        } catch (Exception ignored) {
        }

        if (StringUtils.hasText(task.getNotifyChannels())) {
            dto.setNotifyChannels(Arrays.stream(task.getNotifyChannels().split("[,;|\\s]+"))
                    .map(String::trim)
                    .filter(StringUtils::hasText)
                    .collect(Collectors.toList()));
        } else {
            dto.setNotifyChannels(java.util.Collections.singletonList(
                    ApprovalNotifyDispatchServiceImpl.CHANNEL_INBOX));
        }

        try {
            if (task.getDeployEnvs() != null && !task.getDeployEnvs().isEmpty()) {
                List<Long> envIds = objectMapper.readValue(
                        task.getDeployEnvs(), new TypeReference<List<Long>>() {});
                dto.setDeployEnvIds(envIds);
                List<String> envNames = new java.util.ArrayList<>();
                for (Long envId : envIds) {
                    Env env = envMapper.selectById(envId);
                    if (env != null) {
                        envNames.add(env.getName());
                    }
                }
                dto.setDeployEnvNames(envNames);
            }
        } catch (Exception ignored) {
        }

        if (task.getApprovalFlowId() != null) {
            ApprovalFlow flow = approvalFlowMapper.selectById(task.getApprovalFlowId());
            if (flow != null) {
                dto.setApprovalFlowName(flow.getName());
            }
        }
        if (task.getPipelineTemplateId() != null) {
            Pipeline pipeline = pipelineMapper.selectById(task.getPipelineTemplateId());
            if (pipeline != null) {
                dto.setPipelineTemplateName(pipeline.getName());
            }
        }
        List<BuildJob> linkedJobs = buildJobMapper.selectList(
                new QueryWrapper<BuildJob>().eq("deploy_task_id", task.getId()).orderByAsc("id"));
        if (linkedJobs != null && !linkedJobs.isEmpty()) {
            dto.setBuildJobIds(linkedJobs.stream().map(BuildJob::getId).collect(Collectors.toList()));
        }
        if (task.getClusterId() != null) {
            Cluster cluster = clusterMapper.selectById(task.getClusterId());
            if (cluster != null) {
                dto.setClusterName(cluster.getName());
            }
        }

        dto.setApprovalRecords(approvalService.listByTask(task.getId(), currentUsername));
        deployTaskEditService.fillActionFlags(dto, task, currentUsername, userId, hasCreate, hasUnlock);
        return dto;
    }

    private String sessionUsername(HttpSession session) {
        return session != null ? (String) session.getAttribute("user") : null;
    }

    private Long sessionUserId(HttpSession session) {
        return session != null ? (Long) session.getAttribute("userId") : null;
    }

    private boolean sessionHasUnlock(HttpSession session) {
        return sessionHasPerm(session, "deploy:unlock");
    }

    @SuppressWarnings("unchecked")
    private boolean sessionHasPerm(HttpSession session, String code) {
        Collection<String> perms = session != null
                ? (Collection<String>) session.getAttribute("permissions") : null;
        return permissionService.hasAnyPermission(perms, code);
    }

    /**
     * 将上线模块列表规范化为完整 Harbor 镜像地址，供入库与展示共用。
     * <p>
     * <b>规范化规则</b>（按条处理，跳过空串）：
     * <ul>
     *   <li>未配置 Registry → 原样返回（无法补全，交给下游 CD 服务报错）</li>
     *   <li>已以 {@code registry/} 开头 → 不变</li>
     *   <li>形如 {@code host-or-ip/.../service:tag}（首段含 {@code .} 或 {@code :}）→ 视为已含 registry，不变</li>
     *   <li>其余相对路径（如 {@code project/service:tag}）→ 前缀拼接 {@code registry/}</li>
     * </ul>
     * </p>
     * <p>
     * <b>与 CD 侧关系</b>：createTask 入库前调用，保证 deployModules 字段持久化为完整地址；
     * getTaskDTO 读库后再次调用，兼容旧数据中的相对路径。规则与
     * {@link com.opsflow.service.impl.DeployTaskCdServiceImpl#resolveImageFullName} 语义一致。
     * </p>
     *
     * @param modules 原始或已部分规范化的模块列表
     * @return 规范化后的镜像地址列表（可能为空列表，不为 null）
     */
    private List<String> normalizeDeployModules(List<String> modules) {
        if (modules == null || modules.isEmpty()) {
            return modules;
        }
        String registry = harborCredentialService.getRegistryHost(null);
        List<String> result = new java.util.ArrayList<>();
        for (String module : modules) {
            if (!StringUtils.hasText(module)) {
                continue;
            }
            String text = module.trim();
            if (!StringUtils.hasText(registry)) {
                result.add(text);
                continue;
            }
            String reg = registry.trim();
            if (text.startsWith(reg + "/")) {
                result.add(text);
                continue;
            }
            // 已是 host/... 形态
            int slash = text.indexOf('/');
            int colon = text.lastIndexOf(':');
            if (slash > 0 && colon > slash) {
                String first = text.substring(0, slash);
                if (first.contains(".") || first.contains(":")) {
                    result.add(text);
                    continue;
                }
            }
            result.add(reg + "/" + text);
        }
        return result;
    }

    /**
     * 由规范化镜像列表构建模块详情 DTO，关联服务表补全展示字段。
     * <p>
     * <b>业务目的</b>：详情页除镜像字符串外，还需服务中文名、标准 code、容器端口等；
     * 这些信息存在于服务管理表，按镜像路径最后一段（serviceCode）关联查询。
     * </p>
     * <p>
     * <b>查找策略</b>：先按 code 精确匹配，再按 name 兜底（与 CD 侧 findServiceByCode 一致）；
     * 找到则写入 serviceName、serviceCode、servicePort（未配置或 ≤0 时用 8080）；
     * 找不到则用 serviceCode 充当 serviceName，端口默认 8080，避免前端空白。
     * </p>
     * <p>
     * <b>为何在展示层补端口</b>：用户确认上线清单时需要看到即将写入 K8s Service/Deployment 的端口，
     * 与 {@link com.opsflow.service.impl.DeployTaskCdServiceImpl#createCdJob} 注入 buildParameters 的规则一致，
     * 减少「页面显示 8080、实际部署另一端口」的困惑。
     * </p>
     *
     * @param modules 经 {@link #normalizeDeployModules} 处理后的完整镜像列表
     * @return 与 modules 顺序一致的详情列表；modules 为 null 时返回空列表
     */
    private List<DeployModuleDetailDTO> buildDeployModuleDetails(List<DeployModuleItemDTO> items) {
        List<DeployModuleDetailDTO> details = new java.util.ArrayList<>();
        if (items == null) {
            return details;
        }
        for (DeployModuleItemDTO item : items) {
            String module = item.getImage();
            DeployModuleDetailDTO detail = new DeployModuleDetailDTO();
            detail.setImageFullName(module);
            detail.setOwnerName(item.getOwnerName());
            detail.setOwnerId(item.getOwnerId());
            String serviceCode = extractServiceCode(module);
            detail.setServiceCode(serviceCode);
            if (StringUtils.hasText(serviceCode)) {
                com.opsflow.dao.model.Service service = serviceMapper.selectOne(
                        new QueryWrapper<com.opsflow.dao.model.Service>()
                                .eq("code", serviceCode.trim()).last("LIMIT 1"));
                if (service == null) {
                    service = serviceMapper.selectOne(
                            new QueryWrapper<com.opsflow.dao.model.Service>()
                                    .eq("name", serviceCode.trim()).last("LIMIT 1"));
                }
                if (service != null) {
                    detail.setServiceName(service.getName());
                    detail.setServiceCode(service.getCode() != null ? service.getCode() : serviceCode);
                    Integer port = service.getServicePort();
                    detail.setServicePort(port != null && port > 0 ? port : 8080);
                } else {
                    detail.setServiceName(serviceCode);
                    detail.setServicePort(8080);
                }
            } else {
                detail.setServicePort(8080);
            }
            details.add(detail);
        }
        return details;
    }

    /**
     * 从镜像地址中提取服务标识（路径最后一段，不含 tag）。
     *
     * @param module 完整或相对镜像地址，如 {@code registry/proj/service:tag}
     * @return 服务 code；无法解析时返回 {@code null}
     */
    private String extractServiceCode(String module) {
        if (!StringUtils.hasText(module)) {
            return null;
        }
        String text = module.trim();
        int colon = text.lastIndexOf(':');
        String left = colon > 0 ? text.substring(0, colon) : text;
        int slash = left.lastIndexOf('/');
        if (slash >= 0 && slash < left.length() - 1) {
            return left.substring(slash + 1).trim();
        }
        return left.trim();
    }
}
