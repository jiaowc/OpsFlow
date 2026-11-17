package com.opsflow.service.impl.deploy;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opsflow.api.dto.DeployModuleDetailDTO;
import com.opsflow.api.dto.DeployModuleItemDTO;
import com.opsflow.api.dto.DeployTaskDTO;
import com.opsflow.common.util.DeployModuleJson;
import com.opsflow.dao.mapper.ApprovalFlowMapper;
import com.opsflow.dao.mapper.BuildJobMapper;
import com.opsflow.dao.mapper.ClusterMapper;
import com.opsflow.dao.mapper.EnvMapper;
import com.opsflow.dao.mapper.PipelineMapper;
import com.opsflow.dao.mapper.ProjectMapper;
import com.opsflow.dao.model.ApprovalFlow;
import com.opsflow.dao.model.BuildJob;
import com.opsflow.dao.model.Cluster;
import com.opsflow.dao.model.DeployTask;
import com.opsflow.dao.model.Env;
import com.opsflow.dao.model.Pipeline;
import com.opsflow.service.ApprovalService;
import com.opsflow.service.DeployImageVerifyService;
import com.opsflow.service.DeployTaskEditService;
import com.opsflow.service.DeployTaskOperator;
import com.opsflow.service.impl.ApprovalNotifyDispatchServiceImpl;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 上线任务实体 → 前端展示 DTO 的组装（读时补全关联名称、模块详情、审批记录和操作标志）。
 */
@Component
public class DeployTaskViewAssembler {

    @Autowired
    private ProjectMapper projectMapper;

    @Autowired
    private EnvMapper envMapper;

    @Autowired
    private ApprovalFlowMapper approvalFlowMapper;

    @Autowired
    private PipelineMapper pipelineMapper;

    @Autowired
    private BuildJobMapper buildJobMapper;

    @Autowired
    private ClusterMapper clusterMapper;

    @Autowired
    private ApprovalService approvalService;

    @Autowired
    private DeployTaskEditService deployTaskEditService;

    @Autowired
    private DeployImageVerifyService deployImageVerifyService;

    @Autowired
    private DeployModuleImageResolver deployModuleImageResolver;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 将 DeployTask 实体组装为前端展示用 DTO，并补全关联信息与模块详情。
     * <p>
     * <b>业务目的</b>：库内仅存 ID 与 JSON 字段，列表/详情页需要可读名称、完整镜像、端口、审批可操作性等；
     * 本方法集中做「读时 enrich」，避免前端重复查表或解析镜像格式。
     * </p>
     * <p>
     * <b>主流程</b>：
     * <ol>
     *   <li>拷贝基础字段，解析 deployModules → {@link DeployModuleImageResolver#normalizeDeployModules} → {@link #buildDeployModuleDetails}</li>
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
     * @param operator 当前操作人，用于审批记录 canApprove → actionable 以及可编辑/锁定/发布等操作标志
     * @return 前端展示用 DTO
     */
    public DeployTaskDTO toDTO(DeployTask task, DeployTaskOperator operator) {
        return toDTO(task, operator, new Related());
    }

    /**
     * 批量组装（列表用）：先把所有任务引用到的项目、环境、审批流、流水线模版、集群、关联 Job 一次性查出来，
     * 再逐个组装，避免每个任务各查一遍。结果与逐个调用 {@link #toDTO(DeployTask, DeployTaskOperator)} 相同。
     * <p>
     * 审批记录和「已在非生产验证的版本」仍按任务查询（各有自己的业务逻辑，不在这里展开）。
     * </p>
     */
    public List<DeployTaskDTO> toDTOs(List<DeployTask> tasks, DeployTaskOperator operator) {
        if (tasks == null || tasks.isEmpty()) {
            return new java.util.ArrayList<>();
        }
        Related related = preload(tasks);
        return tasks.stream().map(task -> toDTO(task, operator, related)).collect(Collectors.toList());
    }

    private Related preload(List<DeployTask> tasks) {
        Related related = new Related();
        Set<Long> projectIds = new LinkedHashSet<>();
        Set<Long> envIds = new LinkedHashSet<>();
        Set<Long> flowIds = new LinkedHashSet<>();
        Set<Long> pipelineIds = new LinkedHashSet<>();
        Set<Long> clusterIds = new LinkedHashSet<>();
        Set<Long> taskIds = new LinkedHashSet<>();
        for (DeployTask task : tasks) {
            addIfPresent(projectIds, task.getProjectId());
            addIfPresent(flowIds, task.getApprovalFlowId());
            addIfPresent(pipelineIds, task.getPipelineTemplateId());
            addIfPresent(clusterIds, task.getClusterId());
            addIfPresent(taskIds, task.getId());
            envIds.addAll(parseEnvIdsQuietly(task.getDeployEnvs()));
        }
        // 查不到的 ID 也记下来（值为 null），后面不再单独去查
        if (!projectIds.isEmpty()) {
            projectIds.forEach(id -> related.projects.put(id, null));
            projectMapper.selectBatchIds(projectIds).forEach(p -> related.projects.put(p.getId(), p));
        }
        if (!envIds.isEmpty()) {
            envIds.forEach(id -> related.envs.put(id, null));
            envMapper.selectBatchIds(envIds).forEach(e -> related.envs.put(e.getId(), e));
        }
        if (!flowIds.isEmpty()) {
            flowIds.forEach(id -> related.flows.put(id, null));
            approvalFlowMapper.selectBatchIds(flowIds).forEach(f -> related.flows.put(f.getId(), f));
        }
        if (!pipelineIds.isEmpty()) {
            pipelineIds.forEach(id -> related.pipelines.put(id, null));
            pipelineMapper.selectBatchIds(pipelineIds).forEach(pl -> related.pipelines.put(pl.getId(), pl));
        }
        if (!clusterIds.isEmpty()) {
            clusterIds.forEach(id -> related.clusters.put(id, null));
            clusterMapper.selectBatchIds(clusterIds).forEach(c -> related.clusters.put(c.getId(), c));
        }
        if (!taskIds.isEmpty()) {
            taskIds.forEach(id -> related.jobIdsByTask.put(id, new java.util.ArrayList<>()));
            List<BuildJob> jobs = buildJobMapper.selectList(new QueryWrapper<BuildJob>()
                    .in("deploy_task_id", taskIds).orderByAsc("id"));
            for (BuildJob job : jobs) {
                List<Long> ids = related.jobIdsByTask.get(job.getDeployTaskId());
                if (ids != null) {
                    ids.add(job.getId());
                }
            }
        }
        return related;
    }

    private static void addIfPresent(Set<Long> ids, Long id) {
        if (id != null) {
            ids.add(id);
        }
    }

    private List<Long> parseEnvIdsQuietly(String json) {
        if (!StringUtils.hasText(json)) {
            return java.util.Collections.emptyList();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<Long>>() {});
        } catch (Exception e) {
            return java.util.Collections.emptyList();
        }
    }

    private DeployTaskDTO toDTO(DeployTask task, DeployTaskOperator operator, Related related) {
        String currentUsername = operator.getUsername();
        Long userId = operator.getUserId();
        boolean hasCreate = operator.isCanCreate();
        boolean hasUnlock = operator.isCanUnlock();
        boolean hasDelete = operator.isCanDelete();

        DeployTaskDTO dto = new DeployTaskDTO();
        BeanUtils.copyProperties(task, dto, "deployModules", "deployEnvs", "notifyChannels");
        if (task.getProjectId() != null) {
            com.opsflow.dao.model.Project project = related.project(task.getProjectId());
            dto.setProjectName(project != null ? project.getName() : null);
        }

        try {
            if (task.getDeployModules() != null && !task.getDeployModules().isEmpty()) {
                List<DeployModuleItemDTO> items = DeployModuleJson.parseItems(task.getDeployModules());
                for (DeployModuleItemDTO item : items) {
                    if (!StringUtils.hasText(item.getOwnerName())) {
                        item.setOwnerName(task.getCreatorName());
                        item.setOwnerId(task.getCreatorId());
                    }
                }
                List<String> images = deployModuleImageResolver.normalizeDeployModules(DeployModuleJson.toImageList(items));
                for (int i = 0; i < items.size() && i < images.size(); i++) {
                    items.get(i).setImage(images.get(i));
                }
                dto.setDeployModuleItems(items);
                dto.setDeployModules(images);
                dto.setDeployModuleDetails(buildDeployModuleDetails(items, related));
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
                    Env env = related.env(envId);
                    if (env != null) {
                        envNames.add(env.getName());
                    }
                }
                dto.setDeployEnvNames(envNames);
            }
        } catch (Exception ignored) {
        }

        if (task.getApprovalFlowId() != null) {
            ApprovalFlow flow = related.flow(task.getApprovalFlowId());
            if (flow != null) {
                dto.setApprovalFlowName(flow.getName());
            }
        }
        if (task.getPipelineTemplateId() != null) {
            Pipeline pipeline = related.pipeline(task.getPipelineTemplateId());
            if (pipeline != null) {
                dto.setPipelineTemplateName(pipeline.getName());
            }
        }
        List<Long> linkedJobIds = related.buildJobIds(task.getId());
        if (linkedJobIds != null && !linkedJobIds.isEmpty()) {
            dto.setBuildJobIds(linkedJobIds);
        }
        if (task.getClusterId() != null) {
            Cluster cluster = related.cluster(task.getClusterId());
            if (cluster != null) {
                dto.setClusterName(cluster.getName());
            }
        }

        dto.setApprovalRecords(approvalService.listByTask(task.getId(), currentUsername));
        deployTaskEditService.fillActionFlags(dto, task, currentUsername, userId, hasCreate, hasUnlock, hasDelete);
        return dto;
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
     * 与 CD 侧创建 Job 时注入 buildParameters 的规则一致，
     * 减少「页面显示 8080、实际部署另一端口」的困惑。
     * </p>
     *
     * @param modules 经 {@link DeployModuleImageResolver#normalizeDeployModules} 处理后的完整镜像列表
     * @return 与 modules 顺序一致的详情列表；modules 为 null 时返回空列表
     */
    private List<DeployModuleDetailDTO> buildDeployModuleDetails(List<DeployModuleItemDTO> items, Related related) {
        List<DeployModuleDetailDTO> details = new java.util.ArrayList<>();
        if (items == null) {
            return details;
        }
        Map<String, String> verifiedEnvs = deployImageVerifyService.verifiedEnvByImage(
                items.stream().map(DeployModuleItemDTO::getImage).filter(StringUtils::hasText).collect(Collectors.toList()));
        for (DeployModuleItemDTO item : items) {
            String module = item.getImage();
            DeployModuleDetailDTO detail = new DeployModuleDetailDTO();
            detail.setImageFullName(module);
            detail.setVerifiedEnv(verifiedEnvs.get(module));
            detail.setOwnerName(item.getOwnerName());
            detail.setOwnerId(item.getOwnerId());
            String serviceCode = deployModuleImageResolver.extractServiceCode(module);
            detail.setServiceCode(serviceCode);
            if (StringUtils.hasText(serviceCode)) {
                com.opsflow.dao.model.Service service = related.service(serviceCode);
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
     * 一次组装过程中用到的关联数据。已经查过的（包括查不到的）不再重复查询；
     * 列表场景由 {@link #preload} 预先批量填充，单个任务场景按需逐个查。
     */
    private final class Related {
        private final Map<Long, com.opsflow.dao.model.Project> projects = new HashMap<>();
        private final Map<Long, Env> envs = new HashMap<>();
        private final Map<Long, ApprovalFlow> flows = new HashMap<>();
        private final Map<Long, Pipeline> pipelines = new HashMap<>();
        private final Map<Long, Cluster> clusters = new HashMap<>();
        private final Map<Long, List<Long>> jobIdsByTask = new HashMap<>();
        private final Map<String, com.opsflow.dao.model.Service> services = new HashMap<>();

        com.opsflow.dao.model.Project project(Long id) {
            if (!projects.containsKey(id)) {
                projects.put(id, projectMapper.selectById(id));
            }
            return projects.get(id);
        }

        Env env(Long id) {
            if (!envs.containsKey(id)) {
                envs.put(id, envMapper.selectById(id));
            }
            return envs.get(id);
        }

        ApprovalFlow flow(Long id) {
            if (!flows.containsKey(id)) {
                flows.put(id, approvalFlowMapper.selectById(id));
            }
            return flows.get(id);
        }

        Pipeline pipeline(Long id) {
            if (!pipelines.containsKey(id)) {
                pipelines.put(id, pipelineMapper.selectById(id));
            }
            return pipelines.get(id);
        }

        Cluster cluster(Long id) {
            if (!clusters.containsKey(id)) {
                clusters.put(id, clusterMapper.selectById(id));
            }
            return clusters.get(id);
        }

        List<Long> buildJobIds(Long taskId) {
            if (!jobIdsByTask.containsKey(taskId)) {
                List<BuildJob> jobs = buildJobMapper.selectList(
                        new QueryWrapper<BuildJob>().eq("deploy_task_id", taskId).orderByAsc("id"));
                jobIdsByTask.put(taskId, jobs == null ? new java.util.ArrayList<>()
                        : jobs.stream().map(BuildJob::getId).collect(Collectors.toList()));
            }
            return jobIdsByTask.get(taskId);
        }

        /** 同一个服务标识在多个任务、多个模块里反复出现，只查一次 */
        com.opsflow.dao.model.Service service(String code) {
            String key = code.trim();
            if (!services.containsKey(key)) {
                services.put(key, deployModuleImageResolver.findServiceByCode(code));
            }
            return services.get(key);
        }
    }
}
