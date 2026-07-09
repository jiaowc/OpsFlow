package com.opsflow.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.opsflow.api.dto.BuildJobDTO;
import com.opsflow.api.dto.BuildRequest;
import com.opsflow.api.dto.BuildResponse;
import com.opsflow.api.dto.EnvDTO;
import com.opsflow.api.dto.BuildNodeDTO;
import com.opsflow.common.constant.BuildStatus;
import com.opsflow.common.exception.BusinessException;
import com.opsflow.common.util.JobNumberGenerator;
import com.opsflow.dao.mapper.*;
import com.opsflow.dao.model.*;
import com.opsflow.dao.model.Pipeline;
import com.opsflow.integration.harbor.HarborClient;
import com.opsflow.integration.git.GitRefService;
import com.opsflow.service.PipelineRunService;
import com.opsflow.service.BuildService;
import lombok.extern.slf4j.Slf4j;
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
import java.util.List;
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
    private HarborClient harborClient;

    @Autowired
    private GitRefService gitRefService;

    @Autowired
    private UserMapper userMapper;

    @Value("${opsflow.dev-user-name:${user.name:admin}}")
    private String devUserName;

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
        
        if (request.getPipelineTemplateId() == null) {
            throw new BusinessException("Pipeline模板ID不能为空");
        }
        
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
        
        // 验证是否为非生产环境
        if ("prod".equalsIgnoreCase(env.getName())) {
            throw new BusinessException("生产环境请使用上线任务流程");
        }
        
        // 2. 获取Pipeline模板
        Pipeline pipeline = null;
        String buildNodeName = null;
        if (request.getPipelineTemplateId() != null) {
            pipeline = pipelineMapper.selectById(request.getPipelineTemplateId());
            if (pipeline == null || pipeline.getStatus() != 1) {
                throw new BusinessException("Pipeline模板不存在或已禁用");
            }
        } else {
            if (request.getBuildParameters() != null && request.getBuildParameters().getNode() != null) {
                BuildNode node = buildNodeMapper.selectById(request.getBuildParameters().getNode());
                if (node != null) {
                    buildNodeName = node.getName();
                }
            }
            if (buildNodeName == null || buildNodeName.isEmpty()) {
                buildNodeName = selectRandomNode();
                log.info("随机选择构建节点: {}", buildNodeName);
            }
        }
        
        // 3. 创建构建任务记录
        BuildJob buildJob = new BuildJob();
        buildJob.setJobNumber(JobNumberGenerator.generateBuildJobNumber());
        buildJob.setTaskName(taskName);
        buildJob.setServiceId(request.getServiceId());
        buildJob.setEnvId(request.getEnvId());
        buildJob.setBranch(request.getBranch());
        buildJob.setGitType(normalizeGitType(request.getGitType()));
        
        // 保存Pipeline模板ID
        if (pipeline != null) {
            buildJob.setPipelineTemplateId(pipeline.getId());
        } else if (buildNodeName != null) {
            buildJob.setBuildNode(buildNodeName);
        }
        
        // 保存构建参数（JSON格式）
        if (request.getBuildParameters() != null) {
            try {
                ObjectMapper objectMapper = new ObjectMapper();
                String buildParametersJson = objectMapper.writeValueAsString(request.getBuildParameters());
                buildJob.setBuildParameters(buildParametersJson);
            } catch (Exception e) {
                log.error("序列化构建参数失败", e);
            }
        }
        
        buildJob.setStatus(BuildStatus.PENDING);
        buildJob.setCreatorId(getCurrentUserId());
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
    
    /**
     * 随机选择构建节点（兼容旧参数）
     */
    private String selectRandomNode() {
        List<BuildNode> nodes = buildNodeMapper.selectList(
            new QueryWrapper<BuildNode>()
                .eq("status", "ONLINE")
        );

        if (nodes.isEmpty()) {
            throw new BusinessException("没有可用的构建节点");
        }

        int index = (int) (Math.random() * nodes.size());
        return nodes.get(index).getName();
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
        return devUserName;
    }

    @Override
    public BuildResponse getBuildStatus(Long jobId) {
        BuildJob buildJob = buildJobMapper.selectById(jobId);
        if (buildJob == null) {
            throw new BusinessException("构建任务不存在");
        }
        
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
        response.setBuildLogUrl(buildJob.getBuildLogUrl());
        response.setErrorMessage(buildJob.getErrorMessage());
        response.setStartTime(buildJob.getStartTime());
        response.setEndTime(buildJob.getEndTime());
        
        return response;
    }

    @Override
    public List<String> getBranches(Long serviceId, String type) {
        com.opsflow.dao.model.Service service = serviceMapper.selectById(serviceId);
        if (service == null) {
            throw new BusinessException("服务不存在");
        }
        if (service.getGitRepo() == null || service.getGitRepo().trim().isEmpty()) {
            return fallbackRefs(null);
        }

        String refType = normalizeGitType(type);
        try {
            List<String> refs = gitRefService.listRefs(service.getGitRepo(), refType);
            if (refs.isEmpty()) {
                return fallbackRefs(null);
            }
            return new java.util.ArrayList<>(refs);
        } catch (Exception e) {
            log.warn("获取服务 Git 引用失败: serviceId={}, type={}, error={}", serviceId, refType, e.getMessage());
            return fallbackRefs(null);
        }
    }

    private List<String> fallbackRefs(String defaultRef) {
        java.util.LinkedHashSet<String> refs = new java.util.LinkedHashSet<>();
        if (defaultRef != null && !defaultRef.trim().isEmpty()) {
            refs.add(defaultRef.trim());
        }
        refs.add("develop");
        refs.add("master");
        refs.add("main");
        return new java.util.ArrayList<>(refs);
    }

    @Override
    public List<EnvDTO> getNonProdEnvs() {
        List<Env> envs = envMapper.selectList(
            new QueryWrapper<Env>()
                .ne("name", "prod")
                .eq("status", 1)
        );
        
        return envs.stream().map(env -> {
            EnvDTO dto = new EnvDTO();
            dto.setId(env.getId());
            dto.setName(env.getName());
            dto.setK8sNamespace(env.getK8sNamespace());
            return dto;
        }).collect(Collectors.toList());
    }

    @Override
    public List<BuildNodeDTO> getBuildNodes(Boolean random) {
        List<BuildNode> nodes = buildNodeMapper.selectList(
            new QueryWrapper<BuildNode>()
                .eq("status", "ONLINE")
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
        wrapper.orderByDesc("create_time");
        
        List<BuildJob> jobs = buildJobMapper.selectList(wrapper);
        
        return jobs.stream().map(job -> {
            BuildJobDTO dto = new BuildJobDTO();
            dto.setId(job.getId());
            dto.setJobNumber(job.getJobNumber());
            dto.setTaskName(job.getTaskName());
            dto.setServiceId(job.getServiceId());
            dto.setEnvId(job.getEnvId());
            dto.setBranch(job.getBranch());
            dto.setBuildNode(job.getBuildNode());
            dto.setStatus(job.getStatus());
            dto.setBuildLogUrl(job.getBuildLogUrl());
            dto.setBuildNumber(job.getBuildNumber());
            dto.setCreateTime(job.getCreateTime());
            dto.setUpdateTime(job.getUpdateTime());
            dto.setStartTime(job.getStartTime());
            dto.setEndTime(job.getEndTime());
            
            // 填充服务名称
            if (job.getServiceId() != null) {
                com.opsflow.dao.model.Service service = serviceMapper.selectById(job.getServiceId());
                if (service != null) {
                    dto.setServiceName(service.getName());
                }
            }
            
            // 填充环境名称
            if (job.getEnvId() != null) {
                Env env = envMapper.selectById(job.getEnvId());
                if (env != null) {
                    dto.setEnvName(env.getName());
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


