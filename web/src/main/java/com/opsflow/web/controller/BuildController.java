package com.opsflow.web.controller;

import com.opsflow.api.dto.BuildJobDTO;
import com.opsflow.api.dto.BuildRequest;
import com.opsflow.api.dto.BuildResponse;
import com.opsflow.api.dto.EnvDTO;
import com.opsflow.api.dto.BuildNodeDTO;
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

    @PostMapping("/start")
    public BuildResponse startBuild(@RequestBody BuildRequest request) {
        return buildService.startBuild(request);
    }

    @GetMapping("/{jobId}")
    public BuildResponse getBuildStatus(@PathVariable Long jobId) {
        return buildService.getBuildStatus(jobId);
    }

    @GetMapping("/service/{serviceId}/branches")
    public List<String> getBranches(@PathVariable Long serviceId,
            @RequestParam(defaultValue = "branch") String type) {
        return buildService.getBranches(serviceId, type);
    }

    @GetMapping("/envs")
    public List<EnvDTO> getNonProdEnvs() {
        return buildService.getNonProdEnvs();
    }

    /**
     * 获取构建节点列表（支持随机选择）
     */
    @GetMapping("/nodes")
    public List<BuildNodeDTO> getBuildNodes(@RequestParam(required = false) Boolean random) {
        return buildService.getBuildNodes(random);
    }

    @GetMapping("/jobs")
    public List<BuildJobDTO> getBuildJobs(@RequestParam(required = false) Long envId) {
        return buildService.getBuildJobs(envId);
    }
}
