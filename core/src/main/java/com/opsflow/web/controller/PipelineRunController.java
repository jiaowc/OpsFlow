package com.opsflow.web.controller;

import com.opsflow.api.dto.BuildResponse;
import com.opsflow.api.dto.PipelineJobPageResult;
import com.opsflow.api.dto.PipelineJobViewDTO;
import com.opsflow.api.dto.PipelineStageDTO;
import com.opsflow.api.dto.PipelineViewDTO;
import com.opsflow.api.dto.BuildRequest;
import com.opsflow.integration.pipeline.PipelineJobDispatcher;
import com.opsflow.common.exception.BusinessException;
import com.opsflow.service.PermissionService;
import com.opsflow.service.PipelineRunService;
import com.opsflow.service.WorkloadInstanceService;
import com.opsflow.web.security.SessionPermissionResolver;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpSession;
import java.util.HashMap;
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

    @Autowired
    private PipelineJobDispatcher pipelineJobDispatcher;

    @Autowired
    private WorkloadInstanceService workloadInstanceService;

    @Autowired
    private PermissionService permissionService;

    @Autowired
    private SessionPermissionResolver sessionPermissionResolver;

    @RequiresPermission({"pipeline:view", "statistics:view"})
    @GetMapping("/queue")
    public Map<String, Object> queue() {
        Map<String, Object> result = new HashMap<>();
        result.put("running", pipelineJobDispatcher.runningCount());
        result.put("maxConcurrent", pipelineJobDispatcher.maxConcurrentJobs());
        result.put("queuedJobIds", pipelineJobDispatcher.queuedJobIds());
        return result;
    }

    @RequiresPermission({"pipeline:view", "statistics:view"})
    @GetMapping("/jobs")
    public PipelineJobPageResult listJobs(
            @RequestParam(required = false) Long envId,
            @RequestParam(required = false) Long projectId,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int pageSize) {
        return pipelineRunService.listPipelineJobsPage(envId, projectId, keyword, page, pageSize);
    }

    @RequiresPermission("pipeline:view")
    @GetMapping("/{jobId}/stage-view")
    public PipelineViewDTO getStageView(
            @PathVariable Long jobId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int pageSize,
            @RequestParam(required = false) Long focus) {
        return pipelineRunService.getStageView(jobId, page, pageSize, focus);
    }

    @RequiresPermission("pipeline:view")
    @GetMapping("/{jobId}/stages")
    public List<PipelineStageDTO> getStages(@PathVariable Long jobId) {
        pipelineRunService.assertJobAccessible(jobId);
        return pipelineRunService.getBuildStages(jobId);
    }

    @RequiresPermission("pipeline:view")
    @GetMapping("/{jobId}/stage/{stageId}/log")
    public Map<String, Object> getStageLog(@PathVariable Long jobId,
                                           @PathVariable Long stageId,
                                           @RequestParam(defaultValue = "0") long offset) {
        pipelineRunService.assertJobAccessible(jobId);
        return pipelineRunService.getStageLogDetail(jobId, stageId, offset);
    }

    @RequiresPermission("pipeline:view")
    @GetMapping("/{jobId}/status")
    public Map<String, Object> getStatus(@PathVariable Long jobId) {
        pipelineRunService.assertJobAccessible(jobId);
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

    @RequiresPermission("pipeline:edit")
    @PutMapping("/{jobId}/webhook")
    public Map<String, Object> setWebhook(@PathVariable Long jobId, @RequestBody Map<String, Object> body) {
        boolean enabled = Boolean.TRUE.equals(body.get("enabled"));
        pipelineRunService.setWebhookEnabled(jobId, enabled);
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("enabled", enabled);
        return result;
    }

    @RequiresPermission("pipeline:view")
    @GetMapping("/{jobId}/instances")
    public Map<String, Object> listInstances(@PathVariable Long jobId) {
        return workloadInstanceService.listInstances(jobId);
    }

    @RequiresPermission("pipeline:view")
    @GetMapping("/{jobId}/instances/{pod}/logs")
    public Map<String, Object> podLogs(@PathVariable Long jobId, @PathVariable String pod,
                                       @RequestParam(required = false) String container,
                                       @RequestParam(required = false) Integer tail,
                                       @RequestParam(defaultValue = "false") boolean previous) {
        Map<String, Object> result = new HashMap<>();
        result.put("log", workloadInstanceService.podLogs(jobId, pod, container, tail, previous));
        return result;
    }

    @RequiresPermission("pipeline:view")
    @GetMapping("/{jobId}/instances/{pod}/events")
    public List<Map<String, Object>> podEvents(@PathVariable Long jobId, @PathVariable String pod) {
        return workloadInstanceService.podEvents(jobId, pod);
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

    @RequiresPermission({"pipeline:view", "pipeline:rollback", "pipeline:rollback:nonprod"})
    @GetMapping("/{jobId}/rollback-candidates")
    public List<com.opsflow.api.dto.RollbackCandidateDTO> listRollbackCandidates(@PathVariable Long jobId) {
        pipelineRunService.assertJobAccessible(jobId);
        return pipelineRunService.listRollbackCandidates(jobId);
    }

    @RequiresPermission({"pipeline:rollback", "pipeline:rollback:nonprod"})
    @PostMapping("/{jobId}/rollback")
    public BuildResponse rollbackJob(@PathVariable Long jobId,
                                     @RequestBody com.opsflow.api.dto.RollbackRequest request,
                                     HttpSession session) {
        List<String> permissions = sessionPermissionResolver.resolvePermissions(session);
        boolean prod = pipelineRunService.isProdEnvJob(jobId);
        if (prod) {
            if (!permissionService.hasAnyPermission(permissions, "pipeline:rollback")) {
                throw new BusinessException("生产环境回滚需要「回滚部署」权限，当前账号仅可回滚非生产环境");
            }
        } else if (!permissionService.hasAnyPermission(permissions, "pipeline:rollback")
                && !permissionService.hasAnyPermission(permissions, "pipeline:rollback:nonprod")) {
            throw new BusinessException("无回滚权限");
        }
        return pipelineRunService.rollbackPipelineJob(jobId, request);
    }
}
