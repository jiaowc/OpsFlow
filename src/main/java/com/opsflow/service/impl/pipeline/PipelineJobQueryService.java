package com.opsflow.service.impl.pipeline;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opsflow.api.dto.BuildHistoryDTO;
import com.opsflow.api.dto.PipelineJobPageResult;
import com.opsflow.api.dto.PipelineJobViewDTO;
import com.opsflow.api.dto.PipelineStageDTO;
import com.opsflow.api.dto.PipelineStepDTO;
import com.opsflow.api.dto.PipelineViewDTO;
import com.opsflow.common.constant.BuildStatus;
import com.opsflow.common.exception.BusinessException;
import com.opsflow.common.util.BuildJobCreatorUtils;
import com.opsflow.dao.mapper.*;
import com.opsflow.dao.model.*;
import com.opsflow.integration.pipeline.PipelineJobDispatcher;
import com.opsflow.service.DeployTaskCdService;
import com.opsflow.service.PipelineStepResolveService;
import com.opsflow.service.PipelineViewService;
import com.opsflow.service.ProjectAccessService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 流水线任务只读查询：列表分页、阶段视图、构建阶段与状态展示。
 * <p>由 {@link com.opsflow.service.impl.PipelineRunServiceImpl} 拆分而来，行为保持不变。</p>
 */
@Slf4j
@Component
public class PipelineJobQueryService {
    @Autowired
    private BuildJobMapper buildJobMapper;

    @Autowired
    private BuildJobStageMapper buildJobStageMapper;

    @Autowired
    private ServiceMapper serviceMapper;

    @Autowired
    private EnvMapper envMapper;

    @Autowired
    private PipelineMapper pipelineMapper;

    @Autowired
    private BuildNodeMapper buildNodeMapper;

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private PipelineStepResolveService pipelineStepResolveService;

    @Autowired
    private PipelineViewService pipelineViewService;

    @Autowired
    private PipelineJobDispatcher pipelineJobDispatcher;

    @Autowired
    private ProjectAccessService projectAccessService;

    @Autowired
    @Lazy
    private DeployTaskCdService deployTaskCdService;

    @Autowired
    private PipelineJobSupport support;

    @Autowired
    private PipelineJobLiveness liveness;

    @Autowired
    private BuildNodeSelector nodeSelector;

    @Autowired
    private PipelineRollbackService rollbackService;

    @Autowired
    private PipelineWebhookTriggerService webhookService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    public PipelineJobPageResult listPipelineJobsPage(Long envId, Long projectId, String keyword, int page, int pageSize) {
        page = Math.max(1, page);
        pageSize = normalizePageSize(pageSize);
        String filterKeyword = normalizePipelineListKeyword(keyword);

        pipelineViewService.assertAccessibleEnv(envId);

        Long filterEnvId = envId;
        Collection<Long> filterEnvIds = null;
        if (filterEnvId == null && !pipelineViewService.canAccessAllViews()) {
            Set<Long> allowedEnvIds = pipelineViewService.listAccessibleEnvIds();
            if (allowedEnvIds.isEmpty()) {
                PipelineJobPageResult empty = new PipelineJobPageResult();
                empty.setItems(Collections.emptyList());
                empty.setTotal(0);
                empty.setPage(page);
                empty.setPageSize(pageSize);
                empty.setTotalPages(0);
                return empty;
            }
            filterEnvIds = allowedEnvIds;
        }
        Set<Long> filterServiceIds = projectAccessService.accessibleServiceIds(projectId);
        if (filterServiceIds != null && filterServiceIds.isEmpty()) {
            PipelineJobPageResult empty = new PipelineJobPageResult();
            empty.setItems(Collections.emptyList());
            empty.setTotal(0);
            empty.setPage(page);
            empty.setPageSize(pageSize);
            empty.setTotalPages(0);
            return empty;
        }

        long total = buildJobMapper.countPipelineJobGroups(filterEnvId, filterEnvIds, filterServiceIds, filterKeyword);
        int offset = (page - 1) * pageSize;
        List<PipelineJobViewDTO> items = new ArrayList<>();
        if (total > 0 && offset < total) {
            List<BuildJobGroupPageRow> rows = buildJobMapper.selectPipelineJobGroupPage(
                    filterEnvId, filterEnvIds, filterServiceIds, filterKeyword, offset, pageSize);
            Set<Long> jobIds = new HashSet<>();
            for (BuildJobGroupPageRow row : rows) {
                if (row.getLatestId() != null) {
                    jobIds.add(row.getLatestId());
                }
                if (row.getAnchorId() != null) {
                    jobIds.add(row.getAnchorId());
                }
            }
            Map<Long, BuildJob> jobMap = new HashMap<>();
            if (!jobIds.isEmpty()) {
                List<BuildJob> loaded = buildJobMapper.selectBatchIds(jobIds);
                if (loaded != null) {
                    for (BuildJob job : loaded) {
                        jobMap.put(job.getId(), job);
                    }
                }
            }
            for (BuildJobGroupPageRow row : rows) {
                BuildJob latest = jobMap.get(row.getLatestId());
                BuildJob anchor = jobMap.get(row.getAnchorId());
                if (latest == null) {
                    continue;
                }
                if (anchor == null) {
                    anchor = latest;
                }
                items.add(toTaskView(anchor, latest));
            }
        }

        PipelineJobPageResult result = new PipelineJobPageResult();
        result.setItems(items);
        result.setTotal(total);
        result.setPage(page);
        result.setPageSize(pageSize);
        result.setTotalPages(pageSize > 0 ? (int) Math.ceil((double) total / pageSize) : 0);
        return result;
    }

    int normalizePageSize(int pageSize) {
        if (pageSize == 20 || pageSize == 50) {
            return pageSize;
        }
        return 10;
    }

    String normalizePipelineListKeyword(String keyword) {
        if (keyword == null) {
            return null;
        }
        String trimmed = keyword.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (trimmed.length() > 100) {
            trimmed = trimmed.substring(0, 100);
        }
        return trimmed;
    }

    public PipelineViewDTO getStageView(Long anchorJobId, int page, int pageSize) {
        BuildJob anchor = buildJobMapper.selectById(anchorJobId);
        if (anchor == null) {
            throw new BusinessException("流水线任务不存在");
        }
        support.assertJobEnvAccessible(anchor);

        page = Math.max(1, page);
        pageSize = normalizePageSize(pageSize);

        QueryWrapper<BuildJob> countWrapper = support.buildTaskGroupWrapper(anchor);
        long total = buildJobMapper.selectCount(countWrapper);

        int offset = (page - 1) * pageSize;
        QueryWrapper<BuildJob> listWrapper = support.buildTaskGroupWrapper(anchor);
        listWrapper.orderByDesc("create_time").last("LIMIT " + offset + ", " + pageSize);
        List<BuildJob> jobs = buildJobMapper.selectList(listWrapper);

        Map<Long, List<BuildJobStage>> stagesByJobId = loadStagesGrouped(jobs);

        List<BuildHistoryDTO> buildHistory = jobs.stream()
            .map(job -> toBuildHistory(job, stagesByJobId.get(job.getId())))
            .collect(Collectors.toList());

        PipelineViewDTO view = new PipelineViewDTO();
        view.setJobName(anchor.getTaskName());
        view.setTaskName(anchor.getTaskName());
        view.setBranch(anchor.getBranch());

        BuildJob highlightJob = jobs.stream()
            .filter(j -> BuildStatus.BUILDING.equals(j.getStatus()) || BuildStatus.DEPLOYING.equals(j.getStatus()))
            .findFirst()
            .orElse(jobs.isEmpty() ? anchor : jobs.get(0));
        view.setHighlightJobId(highlightJob.getId());
        view.setCurrentStatus(highlightJob.getStatus());
        view.setBuildHistory(buildHistory);
        view.setTotalBuilds((int) total);
        view.setCurrentPage(page);
        view.setPageSize(pageSize);
        view.setTotalPages(pageSize > 0 ? (int) Math.ceil((double) total / pageSize) : 0);

        PipelineJobViewDTO anchorView = toJobView(highlightJob);
        view.setServiceName(anchorView.getServiceName());
        view.setEnvName(anchorView.getEnvName());
        view.setPipelineTemplateName(anchorView.getPipelineTemplateName());
        view.setBuildNodeDisplay(anchorView.getBuildNodeDisplay());
        view.setDeployNodeDisplay(anchorView.getDeployNodeDisplay());
        String configuredBranch = support.configuredBranch(highlightJob);
        if (configuredBranch != null) {
            view.setBranch(configuredBranch);
        }

        return view;
    }

    public PipelineJobViewDTO toTaskView(BuildJob anchor, BuildJob latestBuild) {
        BuildJob displayJob = support.isDeployTaskManaged(anchor) ? latestBuild : anchor;
        boolean fromDeployTask = support.isDeployTaskManaged(anchor);
        PipelineJobViewDTO dto = new PipelineJobViewDTO();
        dto.setId(anchor.getId());
        dto.setLatestBuildId(latestBuild.getId());
        dto.setJobNumber(latestBuild.getJobNumber());
        dto.setTaskName(displayJob.getTaskName());
        // 任务行展示的是「当前配置」，编辑过分支但还没重新构建时取 next_*，不去改历史行
        dto.setBranch(support.configuredBranch(latestBuild));
        dto.setGitType(support.configuredGitType(latestBuild));
        dto.setStatus(latestBuild.getStatus());
        dto.setQueuePosition(pipelineJobDispatcher.queuePosition(latestBuild.getId()));
        dto.setQueued(dto.getQueuePosition() > 0);
        boolean groupRunning = support.isGroupRunning(anchor);
        dto.setBuilding(groupRunning);
        dto.setFromDeployTask(fromDeployTask);
        dto.setEditable(!groupRunning && !fromDeployTask);
        dto.setDeletable(!groupRunning);
        dto.setWebhookEnabled(!fromDeployTask && webhookService.isWebhookEnabled(anchor.getId()));
        dto.setOperable(projectAccessService.canOperateService(anchor.getServiceId()));
        dto.setServiceId(anchor.getServiceId());
        dto.setEnvId(anchor.getEnvId());
        dto.setPipelineTemplateId(anchor.getPipelineTemplateId());

        if (anchor.getServiceId() != null) {
            com.opsflow.dao.model.Service service = serviceMapper.selectById(anchor.getServiceId());
            if (service != null) {
                dto.setServiceName(service.getName());
                dto.setGitRepo(service.getGitRepo());
            }
        }
        if (anchor.getEnvId() != null) {
            Env env = envMapper.selectById(anchor.getEnvId());
            if (env != null) {
                dto.setEnvName(env.getName());
                dto.setProdEnv(env.isProdEnv());
            }
        }
        if (anchor.getPipelineTemplateId() != null) {
            Pipeline pipeline = pipelineMapper.selectById(anchor.getPipelineTemplateId());
            if (pipeline != null) {
                dto.setPipelineTemplateName(pipeline.getName());
                dto.setBuildNodeDisplay(resolveNodeDisplay(nodeSelector.firstBuildNodeId(pipeline)));
                dto.setDeployNodeDisplay(resolveNodeDisplay(pipeline.getDeployNodeId()));
            }
        }

        dto.setName(resolveDisplayName(displayJob, dto));
        dto.setLastDuration(calculateDuration(latestBuild));
        dto.setLastDurationText(formatDuration(dto.getLastDuration()));
        dto.setCreatorName(BuildJobCreatorUtils.resolveCreatorName(objectMapper, userMapper, latestBuild));
        if (anchor.getCreateTime() != null) {
            dto.setCreateTimeText(anchor.getCreateTime().format(DateTimeFormatter.ofPattern("MM-dd HH:mm")));
        }
        dto.setLatestStages(getDisplayStages(latestBuild));
        if (fromDeployTask) {
            dto.setRetryable(deployTaskCdService.isRetryableCdJob(latestBuild.getId()));
            dto.setBlockedReason(deployTaskCdService.getCdJobBlockedReason(latestBuild.getId()));
        }
        dto.setRollbackable(!groupRunning && rollbackService.canRollback(latestBuild));
        return dto;
    }

    private Map<Long, List<BuildJobStage>> loadStagesGrouped(List<BuildJob> jobs) {
        if (jobs == null || jobs.isEmpty()) {
            return Collections.emptyMap();
        }
        List<Long> jobIds = jobs.stream().map(BuildJob::getId).collect(Collectors.toList());
        List<BuildJobStage> stages = buildJobStageMapper.selectList(
            new QueryWrapper<BuildJobStage>()
                .in("build_job_id", jobIds)
                .orderByAsc("step_order")
        );
        return stages.stream().collect(Collectors.groupingBy(BuildJobStage::getBuildJobId));
    }

    private BuildHistoryDTO toBuildHistory(BuildJob job, List<BuildJobStage> stages) {
        BuildHistoryDTO dto = new BuildHistoryDTO();
        dto.setJobId(job.getId());
        dto.setJobNumber(job.getJobNumber());
        dto.setBuildNumber(job.getBuildNumber());
        dto.setStatus(job.getStatus());
        dto.setBuilding(BuildStatus.BUILDING.equals(job.getStatus()) || BuildStatus.DEPLOYING.equals(job.getStatus()));
        dto.setErrorMessage(job.getErrorMessage());
        dto.setBuildTime(job.getCreateTime() != null
            ? java.util.Date.from(job.getCreateTime().atZone(java.time.ZoneId.systemDefault()).toInstant())
            : null);
        if (job.getCreateTime() != null) {
            dto.setBuildTimeText(job.getCreateTime().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        }
        Long duration = calculateDuration(job);
        dto.setTotalDuration(duration);
        dto.setTotalDurationText(formatDuration(duration));
        dto.setCreatorId(job.getCreatorId());
        dto.setCreatorName(BuildJobCreatorUtils.resolveCreatorName(objectMapper, userMapper, job));
        if (stages != null && !stages.isEmpty()) {
            dto.setStages(stages.stream().map(this::toStageDTO).collect(Collectors.toList()));
            fillActualNodeDisplays(dto, stages, job);
        } else {
            dto.setStages(Collections.emptyList());
            fillActualNodeDisplays(dto, Collections.emptyList(), job);
        }
        return dto;
    }

    private void fillActualNodeDisplays(BuildHistoryDTO dto, List<BuildJobStage> stages, BuildJob job) {
        String buildNode = null;
        String deployNode = null;

        for (BuildJobStage stage : stages) {
            String node = extractNodeDisplay(stage.getLogPreview());
            if (node == null || node.isEmpty()) {
                continue;
            }

            String stepType = stage.getStepType() != null ? stage.getStepType().toLowerCase() : "";
            if (isDeployStage(stepType)) {
                if (deployNode == null) {
                    deployNode = node;
                }
            } else if (buildNode == null) {
                buildNode = node;
            }
        }

        Pipeline pipeline = job.getPipelineTemplateId() != null ? pipelineMapper.selectById(job.getPipelineTemplateId()) : null;
        if (buildNode == null || buildNode.isEmpty()) {
            buildNode = pipeline != null ? resolveNodeDisplay(nodeSelector.firstBuildNodeId(pipeline)) : "本机";
        }
        if (deployNode == null || deployNode.isEmpty()) {
            deployNode = pipeline != null ? resolveNodeDisplay(pipeline.getDeployNodeId()) : "本机";
        }

        dto.setBuildNodeDisplay(buildNode);
        dto.setDeployNodeDisplay(deployNode);
    }

    private boolean isDeployStage(String stepType) {
        return "deploy".equals(stepType)
            || "check_deploy".equals(stepType)
            || "rollback".equals(stepType);
    }

    private String extractNodeDisplay(String logText) {
        if (logText == null || logText.trim().isEmpty()) {
            return null;
        }
        String marker = "执行节点:";
        int start = logText.indexOf(marker);
        if (start < 0) {
            return null;
        }
        int lineEnd = logText.indexOf('\n', start);
        String line = lineEnd >= 0 ? logText.substring(start, lineEnd) : logText.substring(start);
        String value = line.substring(marker.length()).trim();
        return value.isEmpty() ? null : value;
    }

    public List<PipelineStageDTO> getBuildStages(Long buildJobId) {
        List<BuildJobStage> stages = buildJobStageMapper.selectList(
            new QueryWrapper<BuildJobStage>()
                .eq("build_job_id", buildJobId)
                .orderByAsc("step_order")
        );
        if (stages.isEmpty()) {
            return Collections.emptyList();
        }
        return stages.stream().map(this::toStageDTO).collect(Collectors.toList());
    }

    private List<PipelineStageDTO> getDisplayStages(BuildJob buildJob) {
        List<BuildJobStage> stages = buildJobStageMapper.selectList(
            new QueryWrapper<BuildJobStage>()
                .eq("build_job_id", buildJob.getId())
                .orderByAsc("step_order")
        );
        List<PipelineStageDTO> stageDtos = stages.stream().map(this::toStageDTO).collect(Collectors.toList());

        if (buildJob.getPipelineTemplateId() == null) {
            return stageDtos;
        }

        Pipeline pipeline = pipelineMapper.selectById(buildJob.getPipelineTemplateId());
        if (pipeline == null) {
            return stageDtos;
        }

        try {
            List<PipelineStepDTO> templateSteps = pipelineStepResolveService.resolveSteps(support.parseSteps(pipeline.getStepsConfig()));
            if (templateSteps.isEmpty()) {
                return stageDtos;
            }

            Map<Integer, PipelineStageDTO> existingByOrder = new LinkedHashMap<>();
            for (BuildJobStage stage : stages) {
                existingByOrder.put(stage.getStepOrder(), toStageDTO(stage));
            }

            List<PipelineStageDTO> merged = new ArrayList<>();
            for (PipelineStepDTO step : templateSteps) {
                int order = step.getOrder() != null ? step.getOrder() : (merged.size() + 1);
                PipelineStageDTO existing = existingByOrder.get(order);
                if (existing != null) {
                    merged.add(existing);
                    continue;
                }

                PipelineStageDTO placeholder = new PipelineStageDTO();
                placeholder.setId("template-step-" + order);
                placeholder.setName(step.getStepName() != null && !step.getStepName().trim().isEmpty()
                    ? step.getStepName().trim()
                    : step.getStepType());
                placeholder.setStatus("NOT_EXECUTED");
                placeholder.setDurationMillis(0L);
                placeholder.setDurationText(formatDuration(0L));
                merged.add(placeholder);
            }

            for (Map.Entry<Integer, PipelineStageDTO> entry : existingByOrder.entrySet()) {
                if (templateSteps.stream().noneMatch(step ->
                        (step.getOrder() != null ? step.getOrder() : 0) == entry.getKey())) {
                    merged.add(entry.getValue());
                }
            }
            return merged;
        } catch (Exception e) {
            log.warn("合并 Pipeline 模板步骤失败，回退到构建阶段快照: buildJobId={}, pipelineTemplateId={}, error={}",
                buildJob.getId(), buildJob.getPipelineTemplateId(), e.getMessage());
            return stageDtos;
        }
    }

    public Map<String, Object> getBuildRunStatus(Long buildJobId) {
        Map<String, Object> result = new HashMap<>();
        BuildJob buildJob = buildJobMapper.selectById(buildJobId);
        if (buildJob == null) {
            result.put("exists", false);
            return result;
        }

        result.put("exists", true);
        result.put("jobId", buildJob.getId());
        result.put("jobNumber", buildJob.getJobNumber());
        result.put("taskName", buildJob.getTaskName());
        result.put("status", buildJob.getStatus());
        result.put("building", BuildStatus.BUILDING.equals(buildJob.getStatus()) || BuildStatus.DEPLOYING.equals(buildJob.getStatus()));
        int queuePosition = pipelineJobDispatcher.queuePosition(buildJobId);
        result.put("queued", queuePosition > 0);
        result.put("queuePosition", queuePosition);
        result.put("errorMessage", buildJob.getErrorMessage());
        result.put("stages", getBuildStages(buildJobId));
        result.put("isPipeline", true);
        return result;
    }

    public PipelineJobViewDTO getPipelineJob(Long buildJobId) {
        BuildJob buildJob = buildJobMapper.selectById(buildJobId);
        if (buildJob == null) {
            throw new BusinessException("流水线任务不存在");
        }
        support.assertJobEnvAccessible(buildJob);
        BuildJob anchor = support.resolveAnchorJob(buildJob);
        BuildJob latest = support.resolveLatestJobInGroup(anchor);
        return toTaskView(anchor, latest);
    }

    private PipelineJobViewDTO toJobView(BuildJob job) {
        PipelineJobViewDTO dto = new PipelineJobViewDTO();
        boolean fromDeployTask = support.isDeployTaskManaged(job);
        dto.setId(job.getId());
        dto.setJobNumber(job.getJobNumber());
        dto.setTaskName(job.getTaskName());
        dto.setBranch(job.getBranch());
        dto.setGitType(support.resolveJobGitType(job));
        dto.setStatus(job.getStatus());
        dto.setBuilding(BuildStatus.BUILDING.equals(job.getStatus()) || BuildStatus.DEPLOYING.equals(job.getStatus()));
        dto.setFromDeployTask(fromDeployTask);
        dto.setEditable(!liveness.isJobRunning(job) && !fromDeployTask);
        dto.setDeletable(!liveness.isJobRunning(job));
        dto.setServiceId(job.getServiceId());
        dto.setEnvId(job.getEnvId());
        dto.setPipelineTemplateId(job.getPipelineTemplateId());

        if (job.getServiceId() != null) {
            com.opsflow.dao.model.Service service = serviceMapper.selectById(job.getServiceId());
            if (service != null) {
                dto.setServiceName(service.getName());
                dto.setGitRepo(service.getGitRepo());
            }
        }
        if (job.getEnvId() != null) {
            Env env = envMapper.selectById(job.getEnvId());
            if (env != null) {
                dto.setEnvName(env.getName());
            }
        }
        if (job.getPipelineTemplateId() != null) {
            Pipeline pipeline = pipelineMapper.selectById(job.getPipelineTemplateId());
            if (pipeline != null) {
                dto.setPipelineTemplateName(pipeline.getName());
            }
        }

        dto.setName(resolveDisplayName(job, dto));
        dto.setLastDuration(calculateDuration(job));
        dto.setLastDurationText(formatDuration(dto.getLastDuration()));
        if (job.getCreateTime() != null) {
            dto.setCreateTimeText(job.getCreateTime().format(DateTimeFormatter.ofPattern("MM-dd HH:mm")));
        }
        dto.setLatestStages(getBuildStages(job.getId()));
        return dto;
    }

    private String resolveDisplayName(BuildJob job, PipelineJobViewDTO dto) {
        if (job.getTaskName() != null && !job.getTaskName().trim().isEmpty()) {
            return job.getTaskName().trim();
        }
        return buildDisplayName(dto);
    }

    private String buildDisplayName(PipelineJobViewDTO dto) {
        String service = dto.getServiceName() != null ? dto.getServiceName() : "service";
        String env = dto.getEnvName() != null ? dto.getEnvName() : "env";
        String branch = dto.getBranch() != null ? dto.getBranch() : "branch";
        return service + " / " + env + " / " + branch;
    }

    private String resolveNodeDisplay(Long nodeId) {
        if (nodeId == null) {
            return "本机";
        }
        BuildNode node = buildNodeMapper.selectById(nodeId);
        if (node == null) {
            return "节点不存在";
        }
        String name = node.getName() != null && !node.getName().trim().isEmpty()
            ? node.getName().trim()
            : "node-" + nodeId;
        String host = node.getHost() != null && !node.getHost().trim().isEmpty()
            ? node.getHost().trim()
            : "";
        return host.isEmpty() ? name : name + " (" + host + ")";
    }

    private Long calculateDuration(BuildJob job) {
        if (job.getStartTime() == null || job.getEndTime() == null) {
            if (job.getStartTime() != null) {
                return java.time.Duration.between(job.getStartTime(), LocalDateTime.now()).toMillis();
            }
            return 0L;
        }
        return java.time.Duration.between(job.getStartTime(), job.getEndTime()).toMillis();
    }

    private PipelineStageDTO toStageDTO(BuildJobStage stage) {
        PipelineStageDTO dto = new PipelineStageDTO();
        dto.setId(String.valueOf(stage.getId()));
        dto.setName(stage.getStepName());
        dto.setStatus(mapStageStatus(stage.getStatus()));
        dto.setDurationMillis(stage.getDurationMs());
        dto.setDurationText(formatDuration(stage.getDurationMs()));
        if (stage.getStartTime() != null) {
            dto.setStartTimeMillis(stage.getStartTime().atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli());
        }
        return dto;
    }

    String mapStageStatus(String status) {
        if (status == null) {
            return "UNKNOWN";
        }
        switch (status) {
            case "SUCCESS":
                return "SUCCESS";
            case "FAILURE":
                return "FAILURE";
            case "RUNNING":
                return "IN_PROGRESS";
            case "PENDING":
                return "NOT_EXECUTED";
            case "SKIPPED":
                return "SKIPPED";
            default:
                return status;
        }
    }

    String formatDuration(Long millis) {
        if (millis == null || millis <= 0) {
            return "-";
        }
        if (millis < 1000) {
            return millis + "ms";
        }
        long seconds = millis / 1000;
        if (seconds < 60) {
            return seconds + "s";
        }
        return (seconds / 60) + "m " + (seconds % 60) + "s";
    }
}
