package com.opsflow.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.opsflow.api.dto.BuildJobDTO;
import com.opsflow.api.dto.BuildRequest;
import com.opsflow.api.dto.BuildResponse;
import com.opsflow.api.dto.EnvDTO;
import com.opsflow.api.dto.JenkinsNodeDTO;
import com.opsflow.common.constant.BuildStatus;
import com.opsflow.common.exception.BusinessException;
import com.opsflow.common.util.JobNumberGenerator;
import com.opsflow.dao.mapper.*;
import com.opsflow.dao.model.*;
import com.opsflow.dao.model.Pipeline;
import com.opsflow.integration.harbor.HarborClient;
import com.opsflow.integration.jenkins.JenkinsClient;
import com.opsflow.service.BuildService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.ObjectMapper;
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
    private JenkinsNodeMapper jenkinsNodeMapper;
    
    @Autowired
    private PipelineMapper pipelineMapper;
    
    @Autowired
    private JenkinsClient jenkinsClient;
    
    @Autowired
    private HarborClient harborClient;

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
        String jenkinsNode = null;
        if (request.getPipelineTemplateId() != null) {
            pipeline = pipelineMapper.selectById(request.getPipelineTemplateId());
            if (pipeline == null || pipeline.getStatus() != 1) {
                throw new BusinessException("Pipeline模板不存在或已禁用");
            }
        } else {
            // 如果没有指定Pipeline模板，从构建参数中获取节点
            if (request.getBuildParameters() != null && request.getBuildParameters().getNode() != null) {
                JenkinsNode node = jenkinsNodeMapper.selectById(request.getBuildParameters().getNode());
                if (node != null) {
                    jenkinsNode = node.getName();
                }
            }
            if (jenkinsNode == null || jenkinsNode.isEmpty()) {
                jenkinsNode = selectRandomNode();
                log.info("随机选择Jenkins节点: {}", jenkinsNode);
            }
        }
        
        // 3. 创建构建任务记录
        BuildJob buildJob = new BuildJob();
        buildJob.setJobNumber(JobNumberGenerator.generateBuildJobNumber());
        buildJob.setServiceId(request.getServiceId());
        buildJob.setEnvId(request.getEnvId());
        buildJob.setBranch(request.getBranch());
        
        // 保存Pipeline模板ID
        if (pipeline != null) {
            buildJob.setPipelineTemplateId(pipeline.getId());
            // 使用Pipeline模板的Jenkins Job模板名称（兼容旧版本）
            if (pipeline.getJenkinsJobTemplate() != null) {
                buildJob.setJenkinsJobName(pipeline.getJenkinsJobTemplate());
            }
        } else if (jenkinsNode != null) {
            buildJob.setJenkinsNode(jenkinsNode);
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
        
        // 4. 异步触发Jenkins构建
        asyncTriggerJenkinsBuild(buildJob, service, env, request.getAutoDeploy());
        
        // 5. 返回响应
        BuildResponse response = new BuildResponse();
        response.setJobId(buildJob.getId());
        response.setJobNumber(buildJob.getJobNumber());
        response.setStatus(buildJob.getStatus());
        return response;
    }
    
    /**
     * 随机选择Jenkins节点
     */
    private String selectRandomNode() {
        List<JenkinsNode> nodes = jenkinsNodeMapper.selectList(
            new QueryWrapper<JenkinsNode>()
                .eq("status", "ONLINE")
        );
        
        if (nodes.isEmpty()) {
            throw new BusinessException("没有可用的Jenkins节点");
        }
        
        // 随机选择一个
        int index = (int) (Math.random() * nodes.size());
        return nodes.get(index).getName();
    }
    
    /**
     * 异步触发Jenkins构建
     */
    @Async
    private void asyncTriggerJenkinsBuild(BuildJob buildJob, com.opsflow.dao.model.Service service, Env env, Boolean autoDeploy) {
        try {
            // 更新状态为构建中
            buildJob.setStatus(BuildStatus.BUILDING);
            buildJob.setStartTime(java.time.LocalDateTime.now());
            buildJobMapper.updateById(buildJob);
            
            // 调用Jenkins API触发构建
            String jenkinsJobName = env.getJenkinsJobTemplate() != null 
                ? env.getJenkinsJobTemplate() 
                : "build-and-deploy"; // 默认Job名称
            
            java.util.Map<String, String> params = new java.util.HashMap<>();
            params.put("GIT_REPO", service.getGitRepo());
            params.put("GIT_BRANCH", buildJob.getBranch());
            params.put("SERVICE_NAME", service.getName());
            params.put("SERVICE_CODE", service.getCode());
            params.put("ENV_NAME", env.getName());
            params.put("DOCKERFILE_PATH", service.getDockerfilePath());
            params.put("BUILD_COMMAND", service.getBuildCommand());
            params.put("K8S_NAMESPACE", env.getK8sNamespace());
            params.put("K8S_DEPLOYMENT", service.getK8sDeployment());
            params.put("HARBOR_PROJECT", env.getHarborProject());
            params.put("JENKINS_NODE", buildJob.getJenkinsNode());
            params.put("AUTO_DEPLOY", String.valueOf(autoDeploy != null && autoDeploy));
            
            // 触发Jenkins构建
            int buildNumber = jenkinsClient.buildJob(jenkinsJobName, params);
            
            // 更新Jenkins构建信息
            buildJob.setJenkinsBuildNumber(buildNumber);
            buildJob.setJenkinsJobName(jenkinsJobName);
            buildJob.setBuildLogUrl(jenkinsClient.getBuildLogUrl(jenkinsJobName, buildNumber));
            
            // 生成预期的镜像名称（与Jenkins Pipeline中的命名规则一致）
            // 格式: harbor.example.com/project/service-code:branch-buildNumber-commitId
            // 注意：实际镜像名称由Jenkins Pipeline生成，这里只是预估
            String expectedImageTag = String.format("%s:%s-%d", 
                service.getCode(), buildJob.getBranch(), buildNumber);
            buildJob.setImageTag(expectedImageTag);
            buildJobMapper.updateById(buildJob);
            
            log.info("Jenkins构建已触发: {} #{}", jenkinsJobName, buildNumber);
            
            // 如果启用了自动部署，可以在这里添加镜像验证逻辑
            // 注意：由于镜像推送是异步的，实际验证应该在构建完成后进行
            
        } catch (Exception e) {
            log.error("触发Jenkins构建失败", e);
            buildJob.setStatus(BuildStatus.FAILED);
            buildJob.setErrorMessage(e.getMessage());
            buildJob.setEndTime(java.time.LocalDateTime.now());
            buildJobMapper.updateById(buildJob);
        }
    }
    
    private Long getCurrentUserId() {
        // TODO: 从SecurityContext获取当前用户ID
        return 1L; // 简化处理
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
        response.setStatus(buildJob.getStatus());
        response.setImageTag(buildJob.getImageTag());
        response.setBuildLogUrl(buildJob.getBuildLogUrl());
        response.setErrorMessage(buildJob.getErrorMessage());
        response.setStartTime(buildJob.getStartTime());
        response.setEndTime(buildJob.getEndTime());
        
        return response;
    }

    @Override
    public List<String> getBranches(Long serviceId) {
        com.opsflow.dao.model.Service service = serviceMapper.selectById(serviceId);
        if (service == null) {
            throw new BusinessException("服务不存在");
        }
        
        // TODO: 调用Git API获取分支列表
        // 这里简化处理，返回默认分支
        return java.util.Arrays.asList(service.getDefaultBranch(), "develop", "test");
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
            dto.setHarborProject(env.getHarborProject());
            return dto;
        }).collect(Collectors.toList());
    }

    @Override
    public List<JenkinsNodeDTO> getJenkinsNodes(Boolean random) {
        List<JenkinsNode> nodes = jenkinsNodeMapper.selectList(
            new QueryWrapper<JenkinsNode>()
                .eq("status", "ONLINE")
        );
        
        if (Boolean.TRUE.equals(random) && !nodes.isEmpty()) {
            // 随机返回一个节点
            java.util.Collections.shuffle(nodes);
            nodes = nodes.subList(0, Math.min(1, nodes.size()));
        }
        
        return nodes.stream().map(node -> {
            JenkinsNodeDTO dto = new JenkinsNodeDTO();
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
            dto.setServiceId(job.getServiceId());
            dto.setEnvId(job.getEnvId());
            dto.setBranch(job.getBranch());
            dto.setJenkinsNode(job.getJenkinsNode());
            dto.setStatus(job.getStatus());
            dto.setJenkinsJobUrl(job.getBuildLogUrl());
            dto.setJenkinsBuildNumber(job.getJenkinsBuildNumber());
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
}


