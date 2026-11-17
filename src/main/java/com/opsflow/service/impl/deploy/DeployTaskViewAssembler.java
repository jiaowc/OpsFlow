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
import java.util.List;
import java.util.Map;
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
        String currentUsername = operator.getUsername();
        Long userId = operator.getUserId();
        boolean hasCreate = operator.isCanCreate();
        boolean hasUnlock = operator.isCanUnlock();
        boolean hasDelete = operator.isCanDelete();

        DeployTaskDTO dto = new DeployTaskDTO();
        BeanUtils.copyProperties(task, dto, "deployModules", "deployEnvs", "notifyChannels");
        if (task.getProjectId() != null) {
            com.opsflow.dao.model.Project project = projectMapper.selectById(task.getProjectId());
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
    private List<DeployModuleDetailDTO> buildDeployModuleDetails(List<DeployModuleItemDTO> items) {
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
                com.opsflow.dao.model.Service service = deployModuleImageResolver.findServiceByCode(serviceCode);
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
}
