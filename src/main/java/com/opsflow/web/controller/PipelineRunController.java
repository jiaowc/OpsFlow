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

import java.util.List;
import java.util.Map;
import com.opsflow.web.security.RequiresPermission;

/**
 * 平台原生流水线视图控制器
 */
@RestController
@RequestMapping("/api/pipeline-run")
public class PipelineRunController {

    @Autowired
    private PipelineRunService pipelineRunService;

    @RequiresPermission({"pipeline:view", "statistics:view"})
    @GetMapping("/jobs")
    public PipelineJobPageResult listJobs(
            @RequestParam(required = false) Long envId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int pageSize) {
        return pipelineRunService.listPipelineJobsPage(envId, page, pageSize);
    }

    @RequiresPermission("pipeline:view")
    @GetMapping("/{jobId}/stage-view")
    public PipelineViewDTO getStageView(
            @PathVariable Long jobId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int pageSize) {
        return pipelineRunService.getStageView(jobId, page, pageSize);
    }

    @RequiresPermission("pipeline:view")
    @GetMapping("/{jobId}/stages")
    public List<PipelineStageDTO> getStages(@PathVariable Long jobId) {
        return pipelineRunService.getBuildStages(jobId);
    }

    @RequiresPermission("pipeline:view")
    @GetMapping("/{jobId}/stage/{stageId}/log")
    public Map<String, Object> getStageLog(@PathVariable Long jobId,
                                           @PathVariable Long stageId,
                                           @RequestParam(defaultValue = "0") long offset) {
        return pipelineRunService.getStageLogDetail(jobId, stageId, offset);
    }

    @RequiresPermission("pipeline:view")
    @GetMapping("/{jobId}/status")
    public Map<String, Object> getStatus(@PathVariable Long jobId) {
        return pipelineRunService.getBuildRunStatus(jobId);
    }

    @RequiresPermission("pipeline:view")
    @GetMapping("/{jobId}")
    public PipelineJobViewDTO getJob(@PathVariable Long jobId) {
        return pipelineRunService.getPipelineJob(jobId);
    }

    @RequiresPermission("pipeline:edit")
    @PutMapping("/{jobId}")
    public PipelineJobViewDTO updateJob(@PathVariable Long jobId, @RequestBody BuildRequest request) {
        return pipelineRunService.updatePipelineJob(jobId, request);
    }

    @RequiresPermission("pipeline:delete")
    @DeleteMapping("/{jobId}")
    public boolean deleteJob(@PathVariable Long jobId) {
        return pipelineRunService.deletePipelineJob(jobId);
    }

    @RequiresPermission("pipeline:run")
    @PostMapping("/{jobId}/build")
    public BuildResponse rebuildJob(@PathVariable Long jobId) {
        return pipelineRunService.rebuildPipelineJob(jobId);
    }

    @RequiresPermission({"pipeline:view", "pipeline:rollback"})
    @GetMapping("/{jobId}/rollback-candidates")
    public List<com.opsflow.api.dto.RollbackCandidateDTO> listRollbackCandidates(@PathVariable Long jobId) {
        return pipelineRunService.listRollbackCandidates(jobId);
    }

    @RequiresPermission("pipeline:rollback")
    @PostMapping("/{jobId}/rollback")
    public BuildResponse rollbackJob(@PathVariable Long jobId,
                                     @RequestBody com.opsflow.api.dto.RollbackRequest request) {
        return pipelineRunService.rollbackPipelineJob(jobId, request);
    }
}
