package com.opsflow.web.controller;

import com.opsflow.api.dto.BuildResponse;
import com.opsflow.api.dto.PipelineJobPageResult;
import com.opsflow.api.dto.PipelineJobViewDTO;
import com.opsflow.api.dto.PipelineStageDTO;
import com.opsflow.api.dto.PipelineViewDTO;
import com.opsflow.api.dto.BuildRequest;
import com.opsflow.service.PipelineRunService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 平台原生流水线视图控制器
 */
@RestController
@RequestMapping("/api/pipeline-run")
public class PipelineRunController {

    @Autowired
    private PipelineRunService pipelineRunService;

    @GetMapping("/jobs")
    public PipelineJobPageResult listJobs(
            @RequestParam(required = false) Long envId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int pageSize) {
        return pipelineRunService.listPipelineJobsPage(envId, page, pageSize);
    }

    @GetMapping("/{jobId}/stage-view")
    public PipelineViewDTO getStageView(
            @PathVariable Long jobId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int pageSize) {
        return pipelineRunService.getStageView(jobId, page, pageSize);
    }

    @GetMapping("/{jobId}/stages")
    public List<PipelineStageDTO> getStages(@PathVariable Long jobId) {
        return pipelineRunService.getBuildStages(jobId);
    }

    @GetMapping("/{jobId}/stage/{stageId}/log")
    public Map<String, String> getStageLog(@PathVariable Long jobId, @PathVariable Long stageId) {
        Map<String, String> result = new HashMap<>();
        result.put("log", pipelineRunService.getStageLog(jobId, stageId));
        result.put("jobId", String.valueOf(jobId));
        result.put("stageId", String.valueOf(stageId));
        return result;
    }

    @GetMapping("/{jobId}/status")
    public Map<String, Object> getStatus(@PathVariable Long jobId) {
        return pipelineRunService.getBuildRunStatus(jobId);
    }

    @GetMapping("/{jobId}")
    public PipelineJobViewDTO getJob(@PathVariable Long jobId) {
        return pipelineRunService.getPipelineJob(jobId);
    }

    @PutMapping("/{jobId}")
    public PipelineJobViewDTO updateJob(@PathVariable Long jobId, @RequestBody BuildRequest request) {
        return pipelineRunService.updatePipelineJob(jobId, request);
    }

    @DeleteMapping("/{jobId}")
    public boolean deleteJob(@PathVariable Long jobId) {
        return pipelineRunService.deletePipelineJob(jobId);
    }

    @PostMapping("/{jobId}/build")
    public BuildResponse rebuildJob(@PathVariable Long jobId) {
        return pipelineRunService.rebuildPipelineJob(jobId);
    }
}
