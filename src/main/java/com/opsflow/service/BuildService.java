package com.opsflow.service;

import com.opsflow.api.dto.BuildJobDTO;
import com.opsflow.api.dto.BuildRequest;
import com.opsflow.api.dto.BuildResponse;
import com.opsflow.api.dto.BuildNodeDTO;
import com.opsflow.api.dto.EnvDTO;

import java.util.List;

/**
 * 构建服务
 */
public interface BuildService {

    BuildResponse startBuild(BuildRequest request);

    BuildResponse getBuildStatus(Long jobId);

    List<String> getBranches(Long serviceId, String type);

    List<EnvDTO> getNonProdEnvs();

    /**
     * 获取构建节点列表
     */
    List<BuildNodeDTO> getBuildNodes(Boolean random);

    List<BuildJobDTO> getBuildJobs(Long envId);
}
