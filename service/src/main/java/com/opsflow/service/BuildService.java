package com.opsflow.service;

import com.opsflow.api.dto.BuildJobDTO;
import com.opsflow.api.dto.BuildRequest;
import com.opsflow.api.dto.BuildResponse;
import com.opsflow.api.dto.EnvDTO;
import com.opsflow.api.dto.JenkinsNodeDTO;

import java.util.List;

/**
 * 构建服务接口
 */
public interface BuildService {
    
    /**
     * 启动构建任务
     */
    BuildResponse startBuild(BuildRequest request);
    
    /**
     * 查询构建状态
     */
    BuildResponse getBuildStatus(Long jobId);
    
    /**
     * 获取服务分支列表
     */
    List<String> getBranches(Long serviceId);
    
    /**
     * 获取非生产环境列表
     */
    List<EnvDTO> getNonProdEnvs();
    
    /**
     * 获取Jenkins节点列表
     */
    List<JenkinsNodeDTO> getJenkinsNodes(Boolean random);
    
    /**
     * 获取构建任务列表（按环境过滤）
     */
    List<BuildJobDTO> getBuildJobs(Long envId);
}


