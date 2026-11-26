package com.opsflow.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.opsflow.api.dto.BuildJobDTO;
import com.opsflow.api.dto.BuildRequest;
import com.opsflow.api.dto.BuildResponse;
import com.opsflow.api.dto.EnvDTO;
import com.opsflow.api.dto.BuildNodeDTO;
import com.opsflow.common.constant.BuildStatus;
import com.opsflow.common.exception.BusinessException;
import com.opsflow.common.util.BuildJobCreatorUtils;
import com.opsflow.common.util.JobNumberGenerator;
import com.opsflow.dao.mapper.*;
import com.opsflow.dao.model.*;
import com.opsflow.dao.model.Pipeline;
import com.opsflow.integration.harbor.HarborClient;
import com.opsflow.integration.git.GitRefService;
import com.opsflow.spi.ProdDeployPolicy;
import com.opsflow.service.PipelineRunService;
import com.opsflow.service.PipelineViewService;
import com.opsflow.service.BuildService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import com.fasterxml.jackson.databind.ObjectMapper;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 构建服务实现
 */
@Slf4j
@Service
public class BuildServiceImpl implements BuildService {

    @Autowired
    private BuildJobMapper buildJobMapper;
    
    @Autowired
    private ServiceMapper serviceMapper;
    
    @Autowired
    private EnvMapper envMapper;
    
    @Autowired
    private BuildNodeMapper buildNodeMapper;
    
    @Autowired
    private PipelineMapper pipelineMapper;
    
    @Autowired
    private PipelineRunService pipelineRunService;

    @Autowired
    private PipelineViewService pipelineViewService;

    @Autowired
    private com.opsflow.service.ProjectAccessService projectAccessService;
    
    @Autowired
    private HarborClient harborClient;

    @Autowired
    private GitRefService gitRefService;

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private ProdDeployPolicy prodDeployPolicy;

    @Value("${opsflow.dev-user-name:${user.name:admin}}")
    private String devUserName;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 非生产环境手动触发构建/部署的入口（流水线视图路径）。
     * <p><b>业务流程</b></p>
     * <ol>
     *   <li>校验 serviceId、envId、pipelineTemplateId、分支等必填项；</li>
     *   <li><b>生产环境校验</b>：交给 {@link ProdDeployPolicy}（社区版要求「部署生产环境」权限，商业版可强制走上线任务）；</li>
     *   <li>校验服务、环境、Pipeline 模板存在且模板已启用；</li>
     *   <li>insert BuildJob（初始 PENDING，无 deployTaskId）；</li>
     *   <li>调用 {@link PipelineRunService#prepareAndExecuteAsync}：同步置 BUILDING、创建阶段记录、事务提交后异步执行。</li>
     * </ol>
     * <p>
     * 扩展（如企业版上线任务）创建的 CD BuildJob 带 deployTaskId，同样调用 prepareAndExecuteAsync，
     * 终态由扩展通过任务监听回写。本方法面向手动触发的构建，不带 deployTaskId。
     * </p>
     *
     * @param request 构建请求（服务、环境、分支、Pipeline 模板、可选构建参数）
     * @return 新建 BuildJob 的 jobId、jobNumber 及当前状态（通常为 BUILDING）
     */
    @Override
    @Transactional
    public BuildResponse startBuild(BuildRequest request) {
        // 1. 验证请求参数
        if (request.getServiceId() == null) {
            throw new BusinessException("服务ID不能为空");
        }
        
        if (request.getEnvId() == null) {
            throw new BusinessException("环境ID不能为空");
        }
        pipelineViewService.assertAccessibleEnv(request.getEnvId());
        projectAccessService.assertCanOperateService(request.getServiceId());
        
        if (request.getPipelineTemplateId() == null) {
            throw new BusinessException("Pipeline模板ID不能为空");
        }
        // 分支不强制：流水线的拉代码步骤可以在步骤参数里写死分支，或者流水线没有拉代码步骤
        request.setBranch(StringUtils.hasText(request.getBranch()) ? request.getBranch().trim() : null);
        
        // 2. 验证服务和环境
        com.opsflow.dao.model.Service service = serviceMapper.selectById(request.getServiceId());
        if (service == null) {
            throw new BusinessException("服务不存在，ID: " + request.getServiceId());
        }

        String taskName = resolveTaskName(request, service);
        
        Env env = envMapper.selectById(request.getEnvId());
        if (env == null) {
            throw new BusinessException("环境不存在，ID: " + request.getEnvId());
        }
        
        // 生产环境能否直接部署由策略决定
        prodDeployPolicy.checkDirectDeploy(env, ProdDeployPolicy.Trigger.MANUAL);

        // 同一服务 + 环境共用工作目录和 Deployment，禁止并发构建
        pipelineRunService.assertNoRunningBuild(request.getServiceId(), request.getEnvId());
        
        // 2. 获取Pipeline模板
        Pipeline pipeline = pipelineMapper.selectById(request.getPipelineTemplateId());
        if (pipeline == null || pipeline.getStatus() != 1) {
            throw new BusinessException("Pipeline模板不存在或已禁用");
        }
        
        // 3. 创建构建任务记录
        BuildJob buildJob = new BuildJob();
        buildJob.setJobNumber(JobNumberGenerator.generateBuildJobNumber());
        buildJob.setTaskName(taskName);
        buildJob.setServiceId(request.getServiceId());
        buildJob.setEnvId(request.getEnvId());
        buildJob.setBranch(request.getBranch());
        buildJob.setGitType(normalizeGitType(request.getGitType()));
        
        buildJob.setPipelineTemplateId(pipeline.getId());

        // 保存构建参数（JSON格式）
        if (request.getBuildParameters() != null) {
            try {
                String buildParametersJson = objectMapper.writeValueAsString(request.getBuildParameters());
                buildJob.setBuildParameters(buildParametersJson);
            } catch (Exception e) {
                log.error("序列化构建参数失败", e);
            }
        }

        buildJob.setStatus(BuildStatus.PENDING);
        BuildJobCreatorUtils.stampCreator(objectMapper, buildJob, getCurrentUserId(), getCurrentUsername());
        buildJobMapper.insert(buildJob);
        
        // 4. 同步准备阶段 + 异步执行 Pipeline（保证前端立即能看到步骤）
        pipelineRunService.prepareAndExecuteAsync(buildJob.getId());
        
        // 5. 返回响应（重新读取状态：应为 BUILDING）
        BuildJob fresh = buildJobMapper.selectById(buildJob.getId());
        BuildResponse response = new BuildResponse();
        response.setJobId(buildJob.getId());
        response.setJobNumber(buildJob.getJobNumber());
        response.setTaskName(buildJob.getTaskName());
        response.setStatus(fresh != null ? fresh.getStatus() : BuildStatus.BUILDING);
        response.setStartTime(fresh != null ? fresh.getStartTime() : null);
        return response;
    }
    
    private Long getCurrentUserId() {
        String username = getCurrentUsername();
        if (username == null || username.trim().isEmpty()) {
            return null;
        }
        User user = userMapper.selectOne(new QueryWrapper<User>().eq("username", username.trim()).last("LIMIT 1"));
        return user != null ? user.getId() : null;
    }

    private String getCurrentUsername() {
        try {
            RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
            if (attrs instanceof ServletRequestAttributes) {
                HttpServletRequest request = ((ServletRequestAttributes) attrs).getRequest();
                HttpSession session = request != null ? request.getSession(false) : null;
                Object user = session != null ? session.getAttribute("user") : null;
                if (user != null && !String.valueOf(user).trim().isEmpty()) {
                    return String.valueOf(user).trim();
                }
            }
        } catch (Exception e) {
            log.debug("读取当前登录用户名失败: {}", e.getMessage());
        }
        return null;
    }

    @Override
    public BuildResponse getBuildStatus(Long jobId) {
        BuildJob buildJob = buildJobMapper.selectById(jobId);
        if (buildJob == null) {
            throw new BusinessException("构建任务不存在");
        }
        projectAccessService.assertCanViewService(buildJob.getServiceId());
        
        // 如果构建已完成且有镜像信息，验证镜像是否已推送到Harbor
        String buildStatus = buildJob.getStatus();
        if (buildJob.getImageFullName() != null && 
            (BuildStatus.SUCCESS.equals(buildStatus) || 
             BuildStatus.DEPLOYING.equals(buildStatus))) {
            try {
                boolean imageExists = harborClient.verifyImagePushed(buildJob.getImageFullName());
                if (!imageExists) {
                    log.warn("镜像尚未推送到Harbor: {}", buildJob.getImageFullName());
                }
            } catch (Exception e) {
                log.warn("验证Harbor镜像失败: {}", e.getMessage());
            }
        }
        
        BuildResponse response = new BuildResponse();
        response.setJobId(buildJob.getId());
        response.setJobNumber(buildJob.getJobNumber());
        response.setTaskName(buildJob.getTaskName());
        response.setStatus(buildJob.getStatus());
        response.setImageTag(buildJob.getImageTag());
        response.setErrorMessage(buildJob.getErrorMessage());
        response.setStartTime(buildJob.getStartTime());
        response.setEndTime(buildJob.getEndTime());
        
        return response;
    }

    @Override
    public List<String> getBranches(Long serviceId, String type) {
        projectAccessService.assertCanViewService(serviceId);
        com.opsflow.dao.model.Service service = serviceMapper.selectById(serviceId);
        if (service == null) {
            throw new BusinessException("服务不存在");
        }
        if (service.getGitRepo() == null || service.getGitRepo().trim().isEmpty()) {
            throw new BusinessException("服务未配置 Git 仓库地址，无法获取分支/Tag");
        }

        String refType = normalizeGitType(type);
        try {
            // 不再用 develop/master/main 冒充结果：拉取失败要让用户看到原因，仓库没有 Tag 时就是空列表
            return new java.util.ArrayList<>(gitRefService.listRefs(service.getGitRepo(), refType, service.getComponentId()));
        } catch (Exception e) {
            log.warn("获取服务 Git 引用失败: serviceId={}, type={}, error={}", serviceId, refType, e.getMessage());
            throw new BusinessException("获取 Git " + ("tag".equals(refType) ? "Tag " : "分支")
                    + "列表失败，请检查仓库地址、网络和 Git 组件认证配置");
        }
    }

    @Override
    public List<EnvDTO> getBuildableEnvs() {
        List<Env> envs = envMapper.selectList(new QueryWrapper<Env>().eq("status", 1));
        
        return envs.stream().map(env -> {
            if (!prodDeployPolicy.canDirectDeploy(env)) {
                return null;
            }
            EnvDTO dto = new EnvDTO();
            dto.setId(env.getId());
            dto.setName(env.getName());
            dto.setK8sNamespace(env.getK8sNamespace());
            dto.setEnvType(env.getEnvType());
            return dto;
        }).filter(java.util.Objects::nonNull).collect(Collectors.toList());
    }

    @Override
    public List<BuildNodeDTO> getBuildNodes(Boolean random) {
        List<BuildNode> nodes = buildNodeMapper.selectList(
            new QueryWrapper<BuildNode>()
                // 环境检测写入的是中文「在线」；ONLINE 兼容历史数据
                .in("status", NodeServiceImpl.STATUS_ONLINE, "ONLINE")
        );

        if (Boolean.TRUE.equals(random) && !nodes.isEmpty()) {
            java.util.Collections.shuffle(nodes);
            nodes = nodes.subList(0, Math.min(1, nodes.size()));
        }

        return nodes.stream().map(node -> {
            BuildNodeDTO dto = new BuildNodeDTO();
            dto.setId(node.getId());
            dto.setName(node.getName());
            dto.setLabel(node.getLabel());
            dto.setStatus(node.getStatus());
            dto.setDescription(node.getDescription());
            return dto;
        }).collect(Collectors.toList());
    }

    @Override
    public List<BuildJobDTO> getBuildJobs(Long envId) {
        QueryWrapper<BuildJob> wrapper = new QueryWrapper<>();
        if (envId != null) {
            wrapper.eq("env_id", envId);
        }
        Set<Long> serviceIds = projectAccessService.accessibleServiceIds(null);
        if (serviceIds != null) {
            if (serviceIds.isEmpty()) {
                return new ArrayList<>();
            }
            wrapper.in("service_id", serviceIds);
        }
        wrapper.orderByDesc("create_time");
        // 只查列表用到的列：构建参数、错误信息（可能很长）不参与返回
        wrapper.select("id", "job_number", "task_name", "service_id", "env_id", "branch", "status",
            "build_number", "create_time", "update_time", "start_time", "end_time");

        List<BuildJob> jobs = buildJobMapper.selectList(wrapper);
        // 同一个服务、环境在列表里反复出现，名称各查一次
        Map<Long, String> serviceNames = new HashMap<>();
        Map<Long, String> envNames = new HashMap<>();

        return jobs.stream().map(job -> {
            BuildJobDTO dto = new BuildJobDTO();
            dto.setId(job.getId());
            dto.setJobNumber(job.getJobNumber());
            dto.setTaskName(job.getTaskName());
            dto.setServiceId(job.getServiceId());
            dto.setEnvId(job.getEnvId());
            dto.setBranch(job.getBranch());
            dto.setStatus(job.getStatus());
            dto.setBuildNumber(job.getBuildNumber());
            dto.setCreateTime(job.getCreateTime());
            dto.setUpdateTime(job.getUpdateTime());
            dto.setStartTime(job.getStartTime());
            dto.setEndTime(job.getEndTime());
            
            // 填充服务名称
            if (job.getServiceId() != null) {
                if (!serviceNames.containsKey(job.getServiceId())) {
                    com.opsflow.dao.model.Service service = serviceMapper.selectById(job.getServiceId());
                    serviceNames.put(job.getServiceId(), service != null ? service.getName() : null);
                }
                if (serviceNames.get(job.getServiceId()) != null) {
                    dto.setServiceName(serviceNames.get(job.getServiceId()));
                }
            }

            // 填充环境名称
            if (job.getEnvId() != null) {
                if (!envNames.containsKey(job.getEnvId())) {
                    Env env = envMapper.selectById(job.getEnvId());
                    envNames.put(job.getEnvId(), env != null ? env.getName() : null);
                }
                if (envNames.get(job.getEnvId()) != null) {
                    dto.setEnvName(envNames.get(job.getEnvId()));
                }
            }
            
            return dto;
        }).collect(Collectors.toList());
    }

    private String resolveTaskName(BuildRequest request, com.opsflow.dao.model.Service service) {
        if (request.getTaskName() != null && !request.getTaskName().trim().isEmpty()) {
            return request.getTaskName().trim();
        }
        if (service != null && service.getName() != null && !service.getName().trim().isEmpty()) {
            return service.getName().trim();
        }
        throw new BusinessException("服务名称不能为空，无法创建任务");
    }

    private String normalizeGitType(String gitType) {
        return "tag".equalsIgnoreCase(gitType) ? "tag" : "branch";
    }
}


