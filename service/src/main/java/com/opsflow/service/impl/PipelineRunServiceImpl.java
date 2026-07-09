package com.opsflow.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opsflow.api.dto.BuildHistoryDTO;
import com.opsflow.api.dto.BuildRequest;
import com.opsflow.api.dto.BuildResponse;
import com.opsflow.api.dto.PipelineDTO;
import com.opsflow.api.dto.PipelineJobPageResult;
import com.opsflow.api.dto.PipelineJobViewDTO;
import com.opsflow.api.dto.PipelineStageDTO;
import com.opsflow.api.dto.PipelineViewDTO;
import com.opsflow.api.dto.PipelineStepDTO;
import com.opsflow.common.constant.BuildStatus;
import com.opsflow.common.exception.BusinessException;
import com.opsflow.common.util.JobNumberGenerator;
import com.opsflow.dao.mapper.*;
import com.opsflow.dao.model.*;
import com.opsflow.integration.pipeline.PipelineExecutionContext;
import com.opsflow.integration.pipeline.PipelineExecutionResult;
import com.opsflow.integration.pipeline.PipelineExecutor;
import com.opsflow.integration.pipeline.PipelineStageListener;
import com.opsflow.integration.ssh.SshClient;
import com.opsflow.integration.ssh.SshCommandResult;
import com.opsflow.service.PipelineRunService;
import com.opsflow.service.PipelineStepResolveService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
public class PipelineRunServiceImpl implements PipelineRunService {
    private static final long STALE_RUNNING_TIMEOUT_MINUTES = 10;

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
    @Qualifier("nativePipelineExecutor")
    private PipelineExecutor pipelineExecutor;

    @Autowired
    private PipelineStepResolveService pipelineStepResolveService;

    @Autowired
    private SshClient sshClient;

    /**
     * 通过代理调用，确保 @Async 生效（避免同类自调用变成同步阻塞）
     */
    @Autowired
    @Lazy
    private PipelineRunService self;

    @Value("${opsflow.dev-user-name:${user.name:admin}}")
    private String devUserName;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    @Transactional
    public void prepareAndExecuteAsync(Long buildJobId) {
        BuildJob buildJob = buildJobMapper.selectById(buildJobId);
        if (buildJob == null) {
            throw new BusinessException("构建任务不存在");
        }

        Pipeline pipeline = pipelineMapper.selectById(buildJob.getPipelineTemplateId());
        if (pipeline == null) {
            failBuildJob(buildJob, "Pipeline 模板不存在");
            return;
        }

        List<PipelineStepDTO> steps;
        try {
            steps = pipelineStepResolveService.resolveSteps(parseSteps(pipeline.getStepsConfig()));
        } catch (Exception e) {
            failBuildJob(buildJob, "解析 Pipeline 步骤失败: " + e.getMessage());
            return;
        }
        if (steps.isEmpty()) {
            failBuildJob(buildJob, "Pipeline 步骤配置为空");
            return;
        }

        // 同步落库：立刻让前端能看到全部步骤（PENDING）和运行中状态
        buildJob.setStatus(BuildStatus.BUILDING);
        if (buildJob.getStartTime() == null) {
            buildJob.setStartTime(LocalDateTime.now());
        }
        buildJobMapper.updateById(buildJob);

        long existing = buildJobStageMapper.selectCount(
            new QueryWrapper<BuildJobStage>().eq("build_job_id", buildJobId)
        );
        if (existing == 0) {
            createStageRecords(buildJobId, steps);
        }

        // 事务提交后再异步执行，避免异步线程读不到未提交的阶段记录
        runAsyncAfterCommit(buildJobId);
    }

    private void runAsyncAfterCommit(Long buildJobId) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    self.executeBuildJobAsync(buildJobId);
                }
            });
        } else {
            self.executeBuildJobAsync(buildJobId);
        }
    }

    @Override
    @Async
    public void executeBuildJobAsync(Long buildJobId) {
        BuildJob buildJob = buildJobMapper.selectById(buildJobId);
        if (buildJob == null) {
            log.warn("构建任务不存在: {}", buildJobId);
            return;
        }

        com.opsflow.dao.model.Service service = serviceMapper.selectById(buildJob.getServiceId());
        Env env = envMapper.selectById(buildJob.getEnvId());
        Pipeline pipeline = pipelineMapper.selectById(buildJob.getPipelineTemplateId());

        if (service == null || env == null || pipeline == null) {
            failBuildJob(buildJob, "服务、环境或 Pipeline 模板不存在");
            return;
        }

        try {
            if (!BuildStatus.BUILDING.equals(buildJob.getStatus()) && !BuildStatus.DEPLOYING.equals(buildJob.getStatus())) {
                buildJob.setStatus(BuildStatus.BUILDING);
                if (buildJob.getStartTime() == null) {
                    buildJob.setStartTime(LocalDateTime.now());
                }
                buildJobMapper.updateById(buildJob);
            }

            List<PipelineStepDTO> steps = pipelineStepResolveService.resolveSteps(parseSteps(pipeline.getStepsConfig()));
            if (steps.isEmpty()) {
                throw new RuntimeException("Pipeline 步骤配置为空");
            }

            Map<Integer, BuildJobStage> stageByOrder = loadOrCreateStageRecords(buildJobId, steps);
            PipelineDTO pipelineDTO = new PipelineDTO();
            pipelineDTO.setId(pipeline.getId());
            pipelineDTO.setName(pipeline.getName());
            pipelineDTO.setSteps(steps);

            PipelineExecutionContext context = buildContext(buildJob, service, env, pipeline);
            context.setBuildJobId(buildJobId);
            context.setStageListener(buildStageListener(stageByOrder));

            PipelineExecutionResult result = pipelineExecutor.execute(pipelineDTO, context);

            if (Boolean.TRUE.equals(result.getSuccess())) {
                buildJob.setStatus(BuildStatus.SUCCESS);
                if (result.getImageTag() != null) {
                    buildJob.setImageTag(result.getImageTag());
                }
                if (result.getImageFullName() != null) {
                    buildJob.setImageFullName(result.getImageFullName());
                }
            } else {
                buildJob.setStatus(BuildStatus.FAILED);
                buildJob.setErrorMessage(result.getErrorMessage());
            }
        } catch (Exception e) {
            log.error("原生 Pipeline 执行失败, jobId={}", buildJobId, e);
            buildJob.setStatus(BuildStatus.FAILED);
            buildJob.setErrorMessage(e.getMessage());
        } finally {
            buildJob.setEndTime(LocalDateTime.now());
            buildJobMapper.updateById(buildJob);
        }
    }

    @Override
    public List<PipelineJobViewDTO> listPipelineJobs(Long envId) {
        return listPipelineJobsPage(envId, 1, 10).getItems();
    }

    @Override
    public PipelineJobPageResult listPipelineJobsPage(Long envId, int page, int pageSize) {
        page = Math.max(1, page);
        pageSize = normalizePageSize(pageSize);

        QueryWrapper<BuildJob> wrapper = new QueryWrapper<>();
        if (envId != null) {
            wrapper.eq("env_id", envId);
        }
        wrapper.orderByDesc("create_time");
        List<BuildJob> allJobs = buildJobMapper.selectList(wrapper);

        Map<String, List<BuildJob>> grouped = new LinkedHashMap<>();
        for (BuildJob job : allJobs) {
            grouped.computeIfAbsent(buildTaskGroupKey(job), k -> new ArrayList<>()).add(job);
        }

        List<List<BuildJob>> groups = new ArrayList<>(grouped.values());
        groups.sort((g1, g2) -> {
            LocalDateTime t1 = g1.get(0).getCreateTime();
            LocalDateTime t2 = g2.get(0).getCreateTime();
            if (t1 == null && t2 == null) {
                return 0;
            }
            if (t1 == null) {
                return 1;
            }
            if (t2 == null) {
                return -1;
            }
            return t2.compareTo(t1);
        });

        long total = groups.size();
        int from = (page - 1) * pageSize;
        int to = Math.min(from + pageSize, groups.size());

        List<PipelineJobViewDTO> items = new ArrayList<>();
        for (int i = from; i < to; i++) {
            List<BuildJob> groupJobs = groups.get(i);
            BuildJob latest = groupJobs.get(0);
            BuildJob anchor = groupJobs.stream()
                .min(Comparator.comparing(BuildJob::getId))
                .orElse(latest);
            items.add(toTaskView(anchor, latest));
        }

        PipelineJobPageResult result = new PipelineJobPageResult();
        result.setItems(items);
        result.setTotal(total);
        result.setPage(page);
        result.setPageSize(pageSize);
        result.setTotalPages(pageSize > 0 ? (int) Math.ceil((double) total / pageSize) : 0);
        return result;
    }

    private int normalizePageSize(int pageSize) {
        if (pageSize == 20 || pageSize == 50) {
            return pageSize;
        }
        return 10;
    }

    @Override
    public PipelineViewDTO getStageView(Long anchorJobId, int page, int pageSize) {
        BuildJob anchor = buildJobMapper.selectById(anchorJobId);
        if (anchor == null) {
            throw new BusinessException("流水线任务不存在");
        }

        page = Math.max(1, page);
        pageSize = normalizePageSize(pageSize);

        QueryWrapper<BuildJob> countWrapper = buildTaskGroupWrapper(anchor);
        long total = buildJobMapper.selectCount(countWrapper);

        int offset = (page - 1) * pageSize;
        QueryWrapper<BuildJob> listWrapper = buildTaskGroupWrapper(anchor);
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
        if (highlightJob.getBranch() != null) {
            view.setBranch(highlightJob.getBranch());
        }

        fillAverageStageTimes(view, buildHistory);
        return view;
    }

    private QueryWrapper<BuildJob> buildTaskGroupWrapper(BuildJob anchor) {
        QueryWrapper<BuildJob> wrapper = new QueryWrapper<>();
        if (anchor.getTaskName() != null && !anchor.getTaskName().trim().isEmpty()) {
            wrapper.eq("task_name", anchor.getTaskName().trim());
        } else {
            wrapper.and(w -> w.isNull("task_name").or().eq("task_name", ""));
        }
        if (anchor.getServiceId() != null) {
            wrapper.eq("service_id", anchor.getServiceId());
        }
        if (anchor.getEnvId() != null) {
            wrapper.eq("env_id", anchor.getEnvId());
        }
        if (anchor.getPipelineTemplateId() != null) {
            wrapper.eq("pipeline_template_id", anchor.getPipelineTemplateId());
        }
        return wrapper;
    }

    private String buildTaskGroupKey(BuildJob job) {
        return String.format("%s|%s|%s|%s",
            job.getTaskName() != null ? job.getTaskName().trim() : "",
            job.getServiceId(),
            job.getEnvId(),
            job.getPipelineTemplateId());
    }

    private BuildJob resolveAnchorJob(BuildJob job) {
        QueryWrapper<BuildJob> wrapper = buildTaskGroupWrapper(job);
        wrapper.orderByAsc("id").last("LIMIT 1");
        BuildJob anchor = buildJobMapper.selectOne(wrapper);
        return anchor != null ? anchor : job;
    }

    private BuildJob resolveLatestJobInGroup(BuildJob anchor) {
        QueryWrapper<BuildJob> wrapper = buildTaskGroupWrapper(anchor);
        wrapper.orderByDesc("create_time").last("LIMIT 1");
        BuildJob latest = buildJobMapper.selectOne(wrapper);
        if (latest != null) {
            refreshStaleRunningJob(latest);
            return latest;
        }
        return anchor;
    }

    private List<BuildJob> listJobsInGroup(BuildJob anchor) {
        QueryWrapper<BuildJob> wrapper = buildTaskGroupWrapper(anchor);
        wrapper.orderByDesc("create_time");
        return buildJobMapper.selectList(wrapper);
    }

    private boolean isGroupRunning(BuildJob anchor) {
        BuildJob groupAnchor = resolveAnchorJob(anchor);
        BuildJob latest = resolveLatestJobInGroup(groupAnchor);
        return latest != null && isJobRunning(latest);
    }

    private PipelineJobViewDTO toTaskView(BuildJob anchor, BuildJob latestBuild) {
        PipelineJobViewDTO dto = new PipelineJobViewDTO();
        dto.setId(anchor.getId());
        dto.setLatestBuildId(latestBuild.getId());
        dto.setJobNumber(latestBuild.getJobNumber());
        dto.setTaskName(anchor.getTaskName());
        dto.setBranch(latestBuild.getBranch());
        dto.setGitType(resolveJobGitType(anchor));
        dto.setStatus(latestBuild.getStatus());
        boolean groupRunning = isGroupRunning(anchor);
        dto.setBuilding(groupRunning);
        dto.setEditable(!groupRunning);
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
            }
        }
        if (anchor.getPipelineTemplateId() != null) {
            Pipeline pipeline = pipelineMapper.selectById(anchor.getPipelineTemplateId());
            if (pipeline != null) {
                dto.setPipelineTemplateName(pipeline.getName());
                dto.setBuildNodeDisplay(resolveNodeDisplay(pipeline.getBuildNodeId()));
                dto.setDeployNodeDisplay(resolveNodeDisplay(pipeline.getDeployNodeId()));
            }
        }

        dto.setName(resolveDisplayName(anchor, dto));
        dto.setLastDuration(calculateDuration(latestBuild));
        dto.setLastDurationText(formatDuration(dto.getLastDuration()));
        dto.setCreatorName(resolveCreatorName(latestBuild.getCreatorId()));
        if (anchor.getCreateTime() != null) {
            dto.setCreateTimeText(anchor.getCreateTime().format(DateTimeFormatter.ofPattern("MM-dd HH:mm")));
        }
        dto.setLatestStages(getDisplayStages(latestBuild));
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
            String node = extractNodeDisplay(stage.getLogText());
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
            buildNode = pipeline != null ? resolveNodeDisplay(pipeline.getBuildNodeId()) : "本机";
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

    private void fillAverageStageTimes(PipelineViewDTO view, List<BuildHistoryDTO> buildHistory) {
        Map<String, List<Long>> durationsByStage = new HashMap<>();
        List<Long> fullRunDurations = new ArrayList<>();

        for (BuildHistoryDTO build : buildHistory) {
            if (build.getTotalDuration() != null && build.getTotalDuration() > 0) {
                fullRunDurations.add(build.getTotalDuration());
            }
            if (build.getStages() == null) {
                continue;
            }
            for (PipelineStageDTO stage : build.getStages()) {
                if (stage.getDurationMillis() != null && stage.getDurationMillis() > 0) {
                    durationsByStage.computeIfAbsent(stage.getName(), k -> new ArrayList<>())
                        .add(stage.getDurationMillis());
                }
            }
        }

        Map<String, Long> averageStageTimes = new LinkedHashMap<>();
        Map<String, String> averageStageTimesText = new LinkedHashMap<>();
        for (Map.Entry<String, List<Long>> entry : durationsByStage.entrySet()) {
            long avg = (long) entry.getValue().stream().mapToLong(Long::longValue).average().orElse(0);
            averageStageTimes.put(entry.getKey(), avg);
            averageStageTimesText.put(entry.getKey(), formatDuration(avg));
        }
        view.setAverageStageTimes(averageStageTimes);
        view.setAverageStageTimesText(averageStageTimesText);

        if (!fullRunDurations.isEmpty()) {
            long avgFull = (long) fullRunDurations.stream().mapToLong(Long::longValue).average().orElse(0);
            view.setAverageFullRunTime(avgFull);
            view.setAverageFullRunTimeText(formatDuration(avgFull));
        }
    }

    @Override
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
            List<PipelineStepDTO> templateSteps = pipelineStepResolveService.resolveSteps(parseSteps(pipeline.getStepsConfig()));
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

    @Override
    public String getStageLog(Long buildJobId, Long stageId) {
        BuildJobStage stage = buildJobStageMapper.selectById(stageId);
        if (stage == null || !buildJobId.equals(stage.getBuildJobId())) {
            return "阶段不存在";
        }
        if (stage.getLogText() != null && !stage.getLogText().isEmpty()) {
            return stage.getLogText();
        }
        if (stage.getErrorMessage() != null && !stage.getErrorMessage().isEmpty()) {
            return stage.getErrorMessage();
        }
        return "暂无日志";
    }

    @Override
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
        result.put("errorMessage", buildJob.getErrorMessage());
        result.put("stages", getBuildStages(buildJobId));
        result.put("isPipeline", true);
        return result;
    }

    @Override
    public PipelineJobViewDTO getPipelineJob(Long buildJobId) {
        BuildJob buildJob = buildJobMapper.selectById(buildJobId);
        if (buildJob == null) {
            throw new BusinessException("流水线任务不存在");
        }
        BuildJob anchor = resolveAnchorJob(buildJob);
        BuildJob latest = resolveLatestJobInGroup(anchor);
        return toTaskView(anchor, latest);
    }

    @Override
    @Transactional
    public BuildResponse rebuildPipelineJob(Long jobId) {
        BuildJob ref = buildJobMapper.selectById(jobId);
        if (ref == null) {
            throw new BusinessException("流水线任务不存在");
        }

        BuildJob anchor = resolveAnchorJob(ref);
        BuildJob configSource = resolveLatestJobInGroup(anchor);

        if (isGroupRunning(anchor)) {
            throw new BusinessException("任务正在构建中，请稍后再试");
        }

        BuildJob newJob = new BuildJob();
        newJob.setJobNumber(JobNumberGenerator.generateBuildJobNumber());
        newJob.setTaskName(configSource.getTaskName());
        newJob.setServiceId(configSource.getServiceId());
        newJob.setEnvId(configSource.getEnvId());
        newJob.setBranch(configSource.getBranch());
        newJob.setGitType(normalizeGitType(configSource.getGitType()));
        newJob.setPipelineTemplateId(configSource.getPipelineTemplateId());
        newJob.setBuildNode(configSource.getBuildNode());
        newJob.setBuildParameters(configSource.getBuildParameters());
        newJob.setStatus(BuildStatus.BUILDING);
        newJob.setStartTime(LocalDateTime.now());
        newJob.setCreatorId(configSource.getCreatorId());
        buildJobMapper.insert(newJob);

        // 同步准备阶段 + 提交后异步执行
        self.prepareAndExecuteAsync(newJob.getId());

        BuildResponse response = new BuildResponse();
        response.setJobId(newJob.getId());
        response.setJobNumber(newJob.getJobNumber());
        response.setTaskName(newJob.getTaskName());
        response.setStatus(BuildStatus.BUILDING);
        response.setStartTime(newJob.getStartTime());
        return response;
    }

    @Override
    @Transactional
    public PipelineJobViewDTO updatePipelineJob(Long buildJobId, BuildRequest request) {
        BuildJob buildJob = buildJobMapper.selectById(buildJobId);
        if (buildJob == null) {
            throw new BusinessException("流水线任务不存在");
        }
        if (isGroupRunning(buildJob)) {
            throw new BusinessException("任务运行中，无法编辑");
        }

        validateUpdateRequest(request);

        com.opsflow.dao.model.Service service = serviceMapper.selectById(request.getServiceId());
        if (service == null) {
            throw new BusinessException("服务不存在");
        }
        String taskName = resolveTaskName(request, service);
        Env env = envMapper.selectById(request.getEnvId());
        if (env == null) {
            throw new BusinessException("环境不存在");
        }
        if ("prod".equalsIgnoreCase(env.getName())) {
            throw new BusinessException("生产环境请使用上线任务流程");
        }
        Pipeline pipeline = pipelineMapper.selectById(request.getPipelineTemplateId());
        if (pipeline == null || pipeline.getStatus() != 1) {
            throw new BusinessException("Pipeline 模板不存在或已禁用");
        }

        buildJob.setUpdateTime(LocalDateTime.now());

        List<BuildJob> groupJobs = listJobsInGroup(buildJob);
        for (BuildJob job : groupJobs) {
            job.setTaskName(taskName);
            job.setServiceId(request.getServiceId());
            job.setEnvId(request.getEnvId());
            job.setBranch(request.getBranch());
            job.setGitType(normalizeGitType(request.getGitType()));
            job.setPipelineTemplateId(request.getPipelineTemplateId());
            job.setUpdateTime(LocalDateTime.now());
            buildJobMapper.updateById(job);
        }

        BuildJob anchor = resolveAnchorJob(buildJob);
        BuildJob latest = resolveLatestJobInGroup(anchor);
        return toTaskView(anchor, latest);
    }

    @Override
    @Transactional
    public boolean deletePipelineJob(Long buildJobId) {
        BuildJob buildJob = buildJobMapper.selectById(buildJobId);
        if (buildJob == null) {
            throw new BusinessException("流水线任务不存在");
        }
        if (isGroupRunning(buildJob)) {
            throw new BusinessException("任务运行中，无法删除");
        }

        List<BuildJob> groupJobs = listJobsInGroup(buildJob);
        for (BuildJob job : groupJobs) {
            buildJobStageMapper.delete(new QueryWrapper<BuildJobStage>().eq("build_job_id", job.getId()));
            buildJobMapper.deleteById(job.getId());
        }
        return true;
    }

    private void validateUpdateRequest(BuildRequest request) {
        if (request.getServiceId() == null) {
            throw new BusinessException("服务ID不能为空");
        }
        if (request.getEnvId() == null) {
            throw new BusinessException("环境ID不能为空");
        }
        if (request.getPipelineTemplateId() == null) {
            throw new BusinessException("Pipeline模板ID不能为空");
        }
        if (request.getBranch() == null || request.getBranch().trim().isEmpty()) {
            throw new BusinessException("分支不能为空");
        }
    }

    private boolean isJobRunning(BuildJob job) {
        refreshStaleRunningJob(job);
        return BuildStatus.BUILDING.equals(job.getStatus()) || BuildStatus.DEPLOYING.equals(job.getStatus());
    }

    private String resolveCreatorName(Long creatorId) {
        if (creatorId == null) {
            return devUserName;
        }
        User user = userMapper.selectById(creatorId);
        if (user == null) {
            return creatorId == 1L ? devUserName : "用户#" + creatorId;
        }
        if (user.getRealName() != null && !user.getRealName().trim().isEmpty()) {
            return user.getRealName().trim();
        }
        if (user.getUsername() != null && !user.getUsername().trim().isEmpty()) {
            return user.getUsername().trim();
        }
        return creatorId == 1L ? devUserName : "用户#" + creatorId;
    }

    private void refreshStaleRunningJob(BuildJob job) {
        if (job == null) {
            return;
        }
        if (!BuildStatus.BUILDING.equals(job.getStatus()) && !BuildStatus.DEPLOYING.equals(job.getStatus())) {
            return;
        }

        LocalDateTime threshold = LocalDateTime.now().minusMinutes(STALE_RUNNING_TIMEOUT_MINUTES);
        LocalDateTime lastHeartbeat = job.getUpdateTime() != null ? job.getUpdateTime() : job.getStartTime();

        List<BuildJobStage> stages = buildJobStageMapper.selectList(
            new QueryWrapper<BuildJobStage>()
                .eq("build_job_id", job.getId())
                .orderByAsc("step_order")
        );
        for (BuildJobStage stage : stages) {
            if (stage.getUpdateTime() != null && (lastHeartbeat == null || stage.getUpdateTime().isAfter(lastHeartbeat))) {
                lastHeartbeat = stage.getUpdateTime();
            }
        }

        if (lastHeartbeat != null && lastHeartbeat.isAfter(threshold)) {
            return;
        }

        String message = "任务执行长时间无状态更新，已自动标记为失败，可能是服务重启或执行中断导致";
        boolean changed = false;
        for (BuildJobStage stage : stages) {
            if ("RUNNING".equalsIgnoreCase(stage.getStatus())) {
                stage.setStatus("FAILURE");
                stage.setErrorMessage(message);
                stage.setEndTime(LocalDateTime.now());
                stage.setUpdateTime(LocalDateTime.now());
                buildJobStageMapper.updateById(stage);
                changed = true;
            }
        }

        job.setStatus(BuildStatus.FAILED);
        job.setErrorMessage(message);
        if (job.getEndTime() == null) {
            job.setEndTime(LocalDateTime.now());
        }
        job.setUpdateTime(LocalDateTime.now());
        buildJobMapper.updateById(job);

        if (changed) {
            log.warn("检测到僵尸构建，已自动收尾失败: jobId={}, jobNumber={}", job.getId(), job.getJobNumber());
        }
    }

    private PipelineJobViewDTO toJobView(BuildJob job) {
        PipelineJobViewDTO dto = new PipelineJobViewDTO();
        dto.setId(job.getId());
        dto.setJobNumber(job.getJobNumber());
        dto.setTaskName(job.getTaskName());
        dto.setBranch(job.getBranch());
        dto.setGitType(resolveJobGitType(job));
        dto.setStatus(job.getStatus());
        dto.setBuilding(BuildStatus.BUILDING.equals(job.getStatus()) || BuildStatus.DEPLOYING.equals(job.getStatus()));
        dto.setEditable(!isJobRunning(job));
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

    private String mapStageStatus(String status) {
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

    private String formatDuration(Long millis) {
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

    private List<PipelineStepDTO> parseSteps(String stepsConfig) throws Exception {
        if (stepsConfig == null || stepsConfig.trim().isEmpty()) {
            return Collections.emptyList();
        }
        List<PipelineStepDTO> steps = objectMapper.readValue(stepsConfig, new TypeReference<List<PipelineStepDTO>>() {});
        return steps.stream()
            .filter(step -> step.getEnabled() == null || Boolean.TRUE.equals(step.getEnabled()))
            .sorted(Comparator.comparing(step -> step.getOrder() != null ? step.getOrder() : 0))
            .collect(Collectors.toList());
    }

    private Map<Integer, BuildJobStage> createStageRecords(Long buildJobId, List<PipelineStepDTO> steps) {
        Map<Integer, BuildJobStage> stageByOrder = new HashMap<>();
        LocalDateTime now = LocalDateTime.now();
        for (PipelineStepDTO step : steps) {
            int order = step.getOrder() != null ? step.getOrder() : stageByOrder.size() + 1;
            BuildJobStage stage = new BuildJobStage();
            stage.setBuildJobId(buildJobId);
            stage.setStepOrder(order);
            stage.setStepType(step.getStepType());
            stage.setStepName(step.getStepName() != null ? step.getStepName() : step.getStepType());
            stage.setStatus("PENDING");
            stage.setCreateTime(now);
            stage.setUpdateTime(now);
            buildJobStageMapper.insert(stage);
            stageByOrder.put(order, stage);
        }
        return stageByOrder;
    }

    private Map<Integer, BuildJobStage> loadOrCreateStageRecords(Long buildJobId, List<PipelineStepDTO> steps) {
        List<BuildJobStage> existing = buildJobStageMapper.selectList(
            new QueryWrapper<BuildJobStage>()
                .eq("build_job_id", buildJobId)
                .orderByAsc("step_order")
        );
        if (!existing.isEmpty()) {
            Map<Integer, BuildJobStage> stageByOrder = new HashMap<>();
            for (BuildJobStage stage : existing) {
                stageByOrder.put(stage.getStepOrder(), stage);
            }
            return stageByOrder;
        }
        return createStageRecords(buildJobId, steps);
    }

    private PipelineExecutionContext buildContext(BuildJob buildJob, com.opsflow.dao.model.Service service, Env env, Pipeline pipeline) {
        PipelineExecutionContext context = new PipelineExecutionContext();
        context.setExecutionId(String.valueOf(buildJob.getId()));

        PipelineExecutionContext.ServiceInfo serviceInfo = new PipelineExecutionContext.ServiceInfo();
        serviceInfo.setId(service.getId());
        serviceInfo.setName(service.getName());
        serviceInfo.setCode(service.getCode());
        serviceInfo.setGitRepo(service.getGitRepo());
        serviceInfo.setBranch(buildJob.getBranch());
        serviceInfo.setBuildCommand(service.getBuildCommand());
        serviceInfo.setDockerfilePath(service.getDockerfilePath());
        String deployment = service.getK8sDeployment();
        if (deployment == null || deployment.trim().isEmpty()) {
            deployment = service.getCode();
        }
        serviceInfo.setK8sDeployment(deployment);
        context.setService(serviceInfo);

        PipelineExecutionContext.EnvironmentInfo envInfo = new PipelineExecutionContext.EnvironmentInfo();
        envInfo.setId(env.getId());
        envInfo.setName(env.getName());
        envInfo.setK8sNamespace(env.getK8sNamespace());
        context.setEnvironment(envInfo);

        Map<String, String> params = new HashMap<>();
        params.put("gitRepo", service.getGitRepo());
        params.put("branch", buildJob.getBranch());
        params.put("serviceCode", service.getCode());
        params.put("envName", env.getName());
        Long selectedBuildNodeId = selectBuildNodeId(pipeline);
        if (pipeline != null && selectedBuildNodeId != null) {
            BuildNode buildNode = buildNodeMapper.selectById(selectedBuildNodeId);
            if (buildNode != null && buildNode.getWorkDir() != null && !buildNode.getWorkDir().trim().isEmpty()) {
                params.put("workspaceBase", buildNode.getWorkDir().trim());
            }
        }
        context.setParameters(params);

        PipelineExecutionContext.ExecutionOptions options = new PipelineExecutionContext.ExecutionOptions();
        options.setAutoDeploy(true);
        if (pipeline != null) {
            options.setBuildNodeId(selectedBuildNodeId != null ? selectedBuildNodeId : pipeline.getBuildNodeId());
            options.setDeployNodeId(pipeline.getDeployNodeId());
        }
        context.setOptions(options);
        return context;
    }

    private Long selectBuildNodeId(Pipeline pipeline) {
        if (pipeline == null) {
            return null;
        }
        List<Long> candidateIds = parseBuildNodeIds(pipeline.getBuildNodeIds(), pipeline.getBuildNodeId());
        if (candidateIds.isEmpty()) {
            return null;
        }
        if (candidateIds.size() == 1) {
            return candidateIds.get(0);
        }

        BuildNode bestNode = null;
        double bestScore = Double.MAX_VALUE;
        for (Long nodeId : candidateIds) {
            BuildNode node = buildNodeMapper.selectById(nodeId);
            if (node == null) {
                continue;
            }
            double score = readNodeLoadScore(node);
            if (score < bestScore) {
                bestScore = score;
                bestNode = node;
            }
        }
        if (bestNode != null) {
            log.info("多构建节点调度选择低负载节点: {}({}), score={}", bestNode.getName(), bestNode.getHost(), bestScore);
            return bestNode.getId();
        }
        return candidateIds.get(0);
    }

    private List<Long> parseBuildNodeIds(String buildNodeIds, Long fallbackBuildNodeId) {
        List<Long> ids = new ArrayList<>();
        if (buildNodeIds != null && !buildNodeIds.trim().isEmpty()) {
            for (String part : buildNodeIds.split(",")) {
                String text = part == null ? "" : part.trim();
                if (text.isEmpty()) continue;
                try {
                    Long id = Long.parseLong(text);
                    if (!ids.contains(id)) {
                        ids.add(id);
                    }
                } catch (NumberFormatException ignore) {
                }
            }
        }
        if (ids.isEmpty() && fallbackBuildNodeId != null) {
            ids.add(fallbackBuildNodeId);
        }
        return ids;
    }

    private double readNodeLoadScore(BuildNode node) {
        try {
            SshCommandResult result = sshClient.executeCheck(node,
                "cores=$(getconf _NPROCESSORS_ONLN 2>/dev/null || nproc 2>/dev/null || echo 1); "
                    + "load=$(cat /proc/loadavg 2>/dev/null | awk '{print $1}'); "
                    + "if [ -z \"$load\" ]; then load=$(uptime | awk -F'load average: ' '{print $2}' | cut -d, -f1 | tr -d ' '); fi; "
                    + "echo \"$cores $load\"");
            if (!result.isSuccess() || result.getOutput() == null || result.getOutput().trim().isEmpty()) {
                return Double.MAX_VALUE;
            }
            String[] parts = result.getOutput().trim().split("\\s+");
            double cores = parts.length > 0 ? Double.parseDouble(parts[0]) : 1D;
            double load = parts.length > 1 ? Double.parseDouble(parts[1]) : Double.MAX_VALUE / 2;
            if (cores <= 0) {
                cores = 1D;
            }
            return load / cores;
        } catch (Exception e) {
            log.warn("读取节点负载失败，回退默认顺序: nodeId={}, error={}", node.getId(), e.getMessage());
            return Double.MAX_VALUE;
        }
    }

    private PipelineStageListener buildStageListener(Map<Integer, BuildJobStage> stageByOrder) {
        return new PipelineStageListener() {
            @Override
            public void onStageStart(int stepOrder, String stepType, String stepName) {
                BuildJobStage stage = stageByOrder.get(stepOrder);
                if (stage == null) {
                    return;
                }
                stage.setStatus("RUNNING");
                stage.setStartTime(LocalDateTime.now());
                stage.setUpdateTime(LocalDateTime.now());
                buildJobStageMapper.updateById(stage);
            }

            @Override
            public void onStageComplete(int stepOrder, boolean success, String log, String errorMessage, long durationMs) {
                BuildJobStage stage = stageByOrder.get(stepOrder);
                if (stage == null) {
                    return;
                }
                stage.setStatus(success ? "SUCCESS" : "FAILURE");
                stage.setDurationMs(durationMs);
                stage.setLogText(log);
                stage.setErrorMessage(errorMessage);
                stage.setEndTime(LocalDateTime.now());
                stage.setUpdateTime(LocalDateTime.now());
                buildJobStageMapper.updateById(stage);
            }
        };
    }

    private void failBuildJob(BuildJob buildJob, String message) {
        buildJob.setStatus(BuildStatus.FAILED);
        buildJob.setErrorMessage(message);
        buildJob.setEndTime(LocalDateTime.now());
        buildJobMapper.updateById(buildJob);
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

    private String resolveJobGitType(BuildJob job) {
        if (job.getGitType() != null && !job.getGitType().trim().isEmpty()) {
            return normalizeGitType(job.getGitType());
        }
        if (job.getServiceId() != null) {
            com.opsflow.dao.model.Service service = serviceMapper.selectById(job.getServiceId());
            if (service != null && service.getGitType() != null && !service.getGitType().trim().isEmpty()) {
                return normalizeGitType(service.getGitType());
            }
        }
        return "branch";
    }
}
