package com.opsflow.service.impl.deploy;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opsflow.common.constant.BuildStatus;
import com.opsflow.common.exception.BusinessException;
import com.opsflow.common.util.BuildJobCreatorUtils;
import com.opsflow.common.util.JobNumberGenerator;
import com.opsflow.dao.mapper.BuildJobMapper;
import com.opsflow.dao.mapper.EnvMapper;
import com.opsflow.dao.model.BuildJob;
import com.opsflow.dao.model.DeployTask;
import com.opsflow.dao.model.Env;
import com.opsflow.dao.model.Pipeline;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 把上线任务的一个模块变成一条可执行的 CD BuildJob：解析目标环境、镜像和服务，组装流水线参数并落库。
 * <p>
 * 不开事务；由 {@code DeployTaskCdServiceImpl#scheduleApprovedTask} 在其事务内调用，
 * Job 的 insert 与任务状态更新一起提交。
 * </p>
 */
@Component
public class CdJobFactory {

    @Autowired
    private EnvMapper envMapper;

    @Autowired
    private BuildJobMapper buildJobMapper;

    @Autowired
    private DeployModuleImageResolver deployModuleImageResolver;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 为单个上线模块创建一条 CD BuildJob，并序列化流水线所需参数后落库。
     * <p>
     * <b>业务目的</b>：将「镜像 + 服务 + 环境 + 集群/Namespace」打包为流水线可消费的 BuildJob。
     * CD 场景下 branch/gitType 复用为镜像 tag（{@code gitType=tag}），不触发代码拉取。
     * </p>
     * <p>
     * <b>主流程</b>：
     * <ol>
     *   <li>{@link DeployModuleImageResolver#parseModule} 解析镜像，提取 project、serviceCode、version</li>
     *   <li>{@link DeployModuleImageResolver#findServiceByCode} 按 serviceCode 查服务（code 优先，name 兜底）</li>
     *   <li>{@link DeployModuleImageResolver#resolveImageFullName} 补全为含 registry 的完整镜像地址</li>
     *   <li>组装 BuildJob 实体与 buildParameters（image、port、clusterId、k8sNamespace 等）</li>
     *   <li>insert 后返回（调用方负责事务提交与异步执行）</li>
     * </ol>
     * </p>
     * <p>
     * <b>服务查找</b>：镜像路径最后一段（tag 前）作为服务标识，须能在服务管理中匹配到 code 或 name；
     * 找不到则抛 {@link BusinessException}，由 调用方 捕获后 markFailed。
     * </p>
     * <p>
     * <b>端口默认值</b>：服务未配置 servicePort 或 ≤0 时，参数与实体均回退 {@code 8080}，
     * 与前端展示逻辑保持一致，避免 CD 模版渲染时缺少 port。
     * </p>
     *
     * @param task 上线任务（提供 taskName、clusterId、k8sNamespace、deployTaskId）
     * @param pipeline 已校验为 CD 类型的流水线模版
     * @param env 由 {@link #resolveEnv} 匹配到的目标环境
     * @param module 单个上线模块镜像地址（完整或相对路径均可）
     * @param registry Harbor Registry 主机名，用于补全相对路径镜像
     * @return 已 insert 的 BuildJob（status=pending）
     * @throws BusinessException 服务不存在或参数序列化失败
     */
    public BuildJob createCdJob(DeployTask task, Pipeline pipeline, Env env, String module, String registry,
                                 int deploySequence) {
        DeployModuleImageResolver.ParsedModule parsed = deployModuleImageResolver.parseModule(module);
        com.opsflow.dao.model.Service service = deployModuleImageResolver.findServiceByCode(parsed.serviceCode);
        if (service == null) {
            throw new BusinessException("找不到服务: " + parsed.serviceCode + "（模块: " + module + "）");
        }

        String imageFullName = deployModuleImageResolver.resolveImageFullName(module, registry);

        BuildJob buildJob = new BuildJob();
        buildJob.setJobNumber(JobNumberGenerator.generateBuildJobNumber());
        buildJob.setTaskName(task.getTaskName() + " / " + parsed.serviceCode);
        buildJob.setServiceId(service.getId());
        buildJob.setEnvId(env.getId());
        buildJob.setBranch(parsed.version);
        buildJob.setGitType("tag");
        buildJob.setPipelineTemplateId(pipeline.getId());
        buildJob.setDeployTaskId(task.getId());
        buildJob.setImageTag(parsed.version);
        buildJob.setImageFullName(imageFullName);
        buildJob.setStatus(BuildStatus.PENDING);
        buildJob.setCreateTime(LocalDateTime.now());
        buildJob.setUpdateTime(LocalDateTime.now());

        Map<String, Object> params = new HashMap<>();
        params.put("imageFullName", imageFullName);
        params.put("image", imageFullName);
        params.put("imageTag", parsed.version);
        params.put("harborProject", parsed.project);
        params.put("deployTaskId", task.getId());
        params.put("deploySequence", deploySequence);
        params.put("clusterId", task.getClusterId());
        params.put("k8sNamespace", task.getK8sNamespace());
        String servicePort = service.getServicePort() != null && service.getServicePort() > 0
                ? String.valueOf(service.getServicePort()) : "8080";
        params.put("servicePort", servicePort);
        params.put("port", servicePort);
        params.put("serviceName", service.getCode());
        params.put("serviceCode", service.getCode());
        try {
            buildJob.setBuildParameters(objectMapper.writeValueAsString(params));
        } catch (Exception e) {
            throw new BusinessException("序列化 CD 参数失败");
        }
        BuildJobCreatorUtils.stampCreator(objectMapper, buildJob, task.getCreatorId(), task.getCreatorName());

        buildJobMapper.insert(buildJob);
        return buildJob;
    }

    /**
     * 为 CD 部署解析目标环境（Env），决定 BuildJob.envId 与流水线部署目标。
     * <p>
     * <b>匹配策略（按优先级）</b>：
     * <ol>
     *   <li><b>精确匹配</b>：clusterId + k8sNamespace 与任务一致的环境记录，
     *       按 id 升序取第一条。这是首选路径，与创建任务时「选集群 + Namespace」的语义对齐。</li>
     *   <li><b>回退</b>：精确匹配为空时，遍历 deployEnvs JSON 中的环境 ID，返回首个存在的 Env。
     *       兼容历史数据或创建时自动写入的 deployEnvIds，避免仅因环境表未按 Namespace 建记录而阻断 CD。</li>
     * </ol>
     * </p>
     * <p>
     * <b>前置条件</b>：clusterId 非空且 k8sNamespace 有文本；否则直接返回 null（不尝试回退），
     * 因为 Namespace 是 CD 部署的硬性约束，缺失说明任务数据不完整。
     * </p>
     * <p>
     * <b>边界</b>：两条路径均无结果时返回 null，由调用方 markFailed 并提示检查环境管理配置。
     * </p>
     *
     * @param task 含 clusterId、k8sNamespace、deployEnvs 的上线任务
     * @return 匹配到的环境；未找到时返回 {@code null}
     */
    public Env resolveEnv(DeployTask task) {
        if (task.getClusterId() == null || !StringUtils.hasText(task.getK8sNamespace())) {
            return null;
        }
        List<Env> matched = envMapper.selectList(new QueryWrapper<Env>()
                .eq("cluster_id", task.getClusterId())
                .eq("k8s_namespace", task.getK8sNamespace().trim())
                .orderByAsc("id"));
        if (!matched.isEmpty()) {
            return matched.get(0);
        }
        // 回退：仅按部署环境 ID
        List<Long> envIds = parseEnvIds(task.getDeployEnvs());
        for (Long envId : envIds) {
            Env env = envMapper.selectById(envId);
            if (env != null) {
                return env;
            }
        }
        return null;
    }

    /**
     * 解析 deployModules JSON 字段为镜像地址列表。
     *
     * @param json JSON 数组字符串
     * @return 模块列表；解析失败时返回空列表
     */
    public List<String> parseModules(String json) {
        return com.opsflow.common.util.DeployModuleJson.parseImages(json);
    }

    /**
     * 解析 deployEnvs JSON 字段为环境 ID 列表。
     *
     * @param json JSON 数组字符串
     * @return 环境 ID 列表；解析失败时返回空列表
     */
    private List<Long> parseEnvIds(String json) {
        if (!StringUtils.hasText(json)) {
            return new ArrayList<>();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<Long>>() {});
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }
}
