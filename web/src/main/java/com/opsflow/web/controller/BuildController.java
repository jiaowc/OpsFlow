package com.opsflow.web.controller;

import com.opsflow.api.dto.BuildJobDTO;
import com.opsflow.api.dto.BuildRequest;
import com.opsflow.api.dto.BuildResponse;
import com.opsflow.api.dto.EnvDTO;
import com.opsflow.api.dto.JenkinsNodeDTO;
import com.opsflow.service.BuildService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 构建发布控制器
 */
@RestController
@RequestMapping("/api/build")
public class BuildController {

    @Autowired
    private BuildService buildService;

    /**
     * 非生产环境一键构建发布
     */
    @PostMapping("/start")
    public BuildResponse startBuild(@RequestBody BuildRequest request) {
        return buildService.startBuild(request);
    }

    /**
     * 查询构建任务状态
     */
    @GetMapping("/{jobId}")
    public BuildResponse getBuildStatus(@PathVariable Long jobId) {
        return buildService.getBuildStatus(jobId);
    }

    /**
     * 获取服务的分支列表
     */
    @GetMapping("/service/{serviceId}/branches")
    public List<String> getBranches(@PathVariable Long serviceId) {
        return buildService.getBranches(serviceId);
    }

    /**
     * 获取可用的环境列表（非生产环境）
     */
    @GetMapping("/envs")
    public List<EnvDTO> getNonProdEnvs() {
        return buildService.getNonProdEnvs();
    }

    /**
     * 获取Jenkins节点列表（支持随机选择）
     */
    @GetMapping("/nodes")
    public List<JenkinsNodeDTO> getJenkinsNodes(@RequestParam(required = false) Boolean random) {
        return buildService.getJenkinsNodes(random);
    }

    /**
     * 获取构建任务列表（按环境分组）
     */
    @GetMapping("/jobs")
    public List<BuildJobDTO> getBuildJobs(@RequestParam(required = false) Long envId) {
        return buildService.getBuildJobs(envId);
    }
}


