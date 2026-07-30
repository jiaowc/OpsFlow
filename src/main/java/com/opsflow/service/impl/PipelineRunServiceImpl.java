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
import com.opsflow.api.dto.RollbackCandidateDTO;
import com.opsflow.api.dto.RollbackRequest;
import com.opsflow.common.constant.BuildStatus;
import com.opsflow.common.constant.PipelineTypes;
import com.opsflow.common.exception.BusinessException;
import com.opsflow.common.util.BuildJobCreatorUtils;
import com.opsflow.common.util.JobNumberGenerator;
import com.opsflow.dao.mapper.*;
import com.opsflow.dao.model.*;
import com.opsflow.integration.harbor.HarborCredentialService;
import com.opsflow.integration.pipeline.PipelineExecutionContext;
import com.opsflow.integration.pipeline.PipelineExecutionResult;
import com.opsflow.integration.pipeline.PipelineExecutor;
import com.opsflow.integration.pipeline.PipelineStageListener;
import com.opsflow.integration.pipeline.StageLogStore;
import com.opsflow.integration.ssh.SshClient;
import com.opsflow.integration.ssh.SshCommandResult;
import com.opsflow.service.DeployTaskCdService;
import com.opsflow.service.PipelineRunService;
import com.opsflow.service.PipelineStepResolveService;
import com.opsflow.service.PipelineViewService;
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
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 流水线运行服务：统一驱动 {@link com.opsflow.integration.pipeline.PipelineExecutor} 执行 BuildJob。
 * <p>
 * 本服务同时支撑两类入口，底层共用同一套「BuildJob + BuildJobStage + 异步执行」模型：
 * <ul>
 *   <li><b>流水线视图 Job</b>：由 {@link com.opsflow.service.impl.BuildServiceImpl#startBuild} 或
 *       {@link #rebuildPipelineJob} 触发，面向非生产环境的日常构建/部署；{@code deployTaskId} 为空。</li>
 *   <li><b>上线任务 CD Job</b>：由 {@link DeployTaskCdService} 创建 BuildJob 并调用
 *       {@link #prepareAndExecuteAsync}，{@code deployTaskId} 非空；构建终态通过
 *       {@link DeployTaskCdService#onBuildJobFinished} 回写上线任务进度。</li>
 * </ul>
 * 阶段状态（PENDING/RUNNING/SUCCESS/FAILURE）经 {@link PipelineStageListener} 实时落库至
 * {@code build_job_stage}；任务终态落库至 {@code build_job}。
 * </p>
 */
@Slf4j
@Service
public class PipelineRunServiceImpl implements PipelineRunService {
    private static final long STALE_RUNNING_TIMEOUT_MINUTES = 10;
    private static final int BUILD_JOB_STAGE_LOG_PREVIEW_MAX_CHARS = 3800;
    /** 异步线程接管前的宽限期，避免列表刷新误判刚创建的 BUILDING 任务 */
    private static final long RUNNING_JOB_GRACE_SECONDS = 90;
    /** 仅创建了阶段但未真正开始执行时的判定阈值 */
    private static final long PREPARED_BUT_NOT_STARTED_SECONDS = 30;

    /** 当前 JVM 内正在执行的构建任务，用于识别重启后的僵尸 BUILDING 记录 */
    private final Set<Long> activeRunningJobIds = ConcurrentHashMap.newKeySet();

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
    private DeployTaskMapper deployTaskMapper;

    @Autowired
    @Qualifier("nativePipelineExecutor")
    private PipelineExecutor pipelineExecutor;

    @Autowired
    private PipelineStepResolveService pipelineStepResolveService;

    @Autowired
    private SshClient sshClient;

    @Autowired
    private HarborCredentialService harborCredentialService;

    @Autowired
    private StageLogStore stageLogStore;

    @Autowired
    private PipelineViewService pipelineViewService;

    /**
     * 通过代理调用，确保 @Async 生效（避免同类自调用变成同步阻塞）
     */
    @Autowired
    @Lazy
    private PipelineRunService self;

    @Autowired
    @Lazy
    private DeployTaskCdService deployTaskCdService;

    @Value("${opsflow.dev-user-name:${user.name:admin}}")
    private String devUserName;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 唯一可靠的 job 启动入口：校验并准备 BUILDING + 阶段，事务提交后派发执行线程。
     */
    @Override
    @Transactional
    public void launchJob(Long buildJobId) {
        log.info("launchJob 开始准备: jobId={}", buildJobId);
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

        buildJob.setStatus(BuildStatus.BUILDING);
        buildJob.setErrorMessage(null);
        buildJob.setEndTime(null);
        buildJob.setStartTime(LocalDateTime.now());
        buildJob.setUpdateTime(LocalDateTime.now());
        buildJobMapper.updateById(buildJob);

        long existing = buildJobStageMapper.selectCount(
            new QueryWrapper<BuildJobStage>().eq("build_job_id", buildJobId)
        );
        if (existing == 0) {
            createStageRecords(buildJobId, steps);
        }

        log.info("launchJob 准备完成，等待事务提交后派发: jobId={}", buildJobId);
        runAsyncAfterCommit(buildJobId);
    }

    /**
     * @deprecated 请使用 {@link #launchJob(Long)}
     */
    @Override
    @Transactional
    public void prepareAndExecuteAsync(Long buildJobId) {
        launchJob(buildJobId);
    }

    private void runAsyncAfterCommit(Long buildJobId) {
        Runnable dispatch = () -> dispatchBuildJobExecution(buildJobId);
        // 必须有「实际事务」才注册 afterCommit。仅 sync 活跃（例如嵌套在外层 afterCommit 中）
        // 时再注册会导致回调永不触发，Job 停在 BUILDING。
        if (TransactionSynchronizationManager.isActualTransactionActive()
                && TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    dispatch.run();
                }
            });
        } else {
            if (TransactionSynchronizationManager.isSynchronizationActive()) {
                log.warn("同步器仍活跃但无实际事务，立即派发流水线执行以免丢任务: jobId={}", buildJobId);
            }
            dispatch.run();
        }
    }

    private void dispatchBuildJobExecution(Long buildJobId) {
        log.info("事务已提交，启动流水线执行线程: jobId={}", buildJobId);
        java.util.concurrent.CompletableFuture.runAsync(() -> {
            try {
                doExecuteBuildJob(buildJobId);
            } catch (Exception e) {
                log.error("流水线执行线程异常, jobId={}", buildJobId, e);
                BuildJob job = buildJobMapper.selectById(buildJobId);
                if (job != null) {
                    failBuildJob(job, "流水线执行异常: " + e.getMessage());
                }
            }
        });
    }

    /**
     * 异步执行完整 Pipeline，并在结束时落库 BuildJob 终态。
     * <p><b>业务流程</b></p>
     * <ol>
     *   <li>加载服务、环境、Pipeline 模板；缺失则 {@link #failBuildJob} 并返回；</li>
     *   <li>必要时补写 BUILDING/startTime；加载或创建 {@code build_job_stage}；</li>
     *   <li>{@link #buildContext} 组装执行上下文，注册 {@link #buildStageListener} 监听阶段回调；</li>
     *   <li>调用 {@link PipelineExecutor#execute} 逐步执行；</li>
     *   <li>成功：{@link BuildStatus#SUCCESS}，回写 imageTag/imageFullName（来自步骤 output）；</li>
     *   <li>失败/异常：{@link BuildStatus#FAILED}，写入 errorMessage。</li>
     * </ol>
     * <p><b>状态落库时机</b></p>
     * <ul>
     *   <li>各步骤 RUNNING/SUCCESS/FAILURE：由 stageListener 在步骤开始/结束时更新 {@code build_job_stage}；</li>
     *   <li>BuildJob 终态 + endTime：在 {@code finally} 中统一 {@code updateById}；</li>
     *   <li>若 {@code deployTaskId != null}（上线任务 CD），{@code finally} 中调用
     *       {@link DeployTaskCdService#onBuildJobFinished}，将 SUCCESS/FAILED 聚合回写上线任务，
     *       与 {@link #failBuildJob} 的提前失败路径保持一致。</li>
     * </ul>
     *
     * @param buildJobId 构建任务主键（须已通过 {@link #prepareAndExecuteAsync} 完成阶段初始化）
     */
    @Override
    @Async
    public void executeBuildJobAsync(Long buildJobId) {
        doExecuteBuildJob(buildJobId);
    }

    private void doExecuteBuildJob(Long buildJobId) {
        BuildJob buildJob = buildJobMapper.selectById(buildJobId);
        if (buildJob == null) {
            log.warn("构建任务不存在: {}", buildJobId);
            return;
        }

        log.info("流水线执行线程已开始: jobId={}, jobNumber={}", buildJobId, buildJob.getJobNumber());
        activeRunningJobIds.add(buildJobId);
        try {
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
                // CD 上线任务：构建结束（成功/失败）后通知 DeployTaskCdService 推进任务状态
                if (buildJob.getDeployTaskId() != null) {
                    try {
                        deployTaskCdService.onBuildJobFinished(buildJobId);
                    } catch (Exception e) {
                        log.error("回写上线任务状态失败, jobId={}, deployTaskId={}",
                                buildJobId, buildJob.getDeployTaskId(), e);
                    }
                }
            }
        } finally {
            activeRunningJobIds.remove(buildJobId);
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

        long total = buildJobMapper.countPipelineJobGroups(filterEnvId, filterEnvIds);
        int offset = (page - 1) * pageSize;
        List<PipelineJobViewDTO> items = new ArrayList<>();
        if (total > 0 && offset < total) {
            List<BuildJobGroupPageRow> rows = buildJobMapper.selectPipelineJobGroupPage(
                    filterEnvId, filterEnvIds, offset, pageSize);
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
        assertJobEnvAccessible(anchor);

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
        if (isDeployTaskManaged(anchor)) {
            wrapper.isNotNull("deploy_task_id");
        } else {
            wrapper.isNull("deploy_task_id");
            if (anchor.getTaskName() != null && !anchor.getTaskName().trim().isEmpty()) {
                wrapper.eq("task_name", anchor.getTaskName().trim());
            } else {
                wrapper.and(w -> w.isNull("task_name").or().eq("task_name", ""));
            }
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
        if (isDeployTaskManaged(job)) {
            return String.format("deploy|%s|%s|%s",
                job.getServiceId(),
                job.getEnvId(),
                job.getPipelineTemplateId());
        }
        return String.format("manual|%s|%s|%s|%s",
            job.getTaskName() != null ? job.getTaskName().trim() : "",
            job.getServiceId(),
            job.getEnvId(),
            job.getPipelineTemplateId());
    }

    private boolean isDeployTaskManaged(BuildJob job) {
        return job != null && job.getDeployTaskId() != null;
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
        BuildJob displayJob = isDeployTaskManaged(anchor) ? latestBuild : anchor;
        boolean fromDeployTask = isDeployTaskManaged(anchor);
        PipelineJobViewDTO dto = new PipelineJobViewDTO();
        dto.setId(anchor.getId());
        dto.setLatestBuildId(latestBuild.getId());
        dto.setJobNumber(latestBuild.getJobNumber());
        dto.setTaskName(displayJob.getTaskName());
        dto.setBranch(latestBuild.getBranch());
        dto.setGitType(resolveJobGitType(displayJob));
        dto.setStatus(latestBuild.getStatus());
        boolean groupRunning = isGroupRunning(anchor);
        dto.setBuilding(groupRunning);
        dto.setFromDeployTask(fromDeployTask);
        dto.setEditable(!groupRunning && !fromDeployTask);
        dto.setDeletable(!groupRunning);
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
                dto.setBuildNodeDisplay(resolveNodeDisplay(pipeline.getBuildNodeId()));
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
        dto.setRollbackable(!groupRunning && canRollback(latestBuild));
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
            String node = extractNodeDisplay(firstNonEmpty(stage.getLogPreview(), stage.getLogText()));
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

    private String firstNonEmpty(String a, String b) {
        if (a != null && !a.trim().isEmpty()) {
            return a;
        }
        return b;
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
        Map<String, Object> detail = getStageLogDetail(buildJobId, stageId, 0L);
        Object log = detail.get("log");
        return log != null ? String.valueOf(log) : "暂无日志";
    }

    @Override
    public Map<String, Object> getStageLogDetail(Long buildJobId, Long stageId, long offset) {
        Map<String, Object> result = new HashMap<>();
        result.put("jobId", String.valueOf(buildJobId));
        result.put("stageId", String.valueOf(stageId));
        result.put("offset", Math.max(0L, offset));
        result.put("nextOffset", Math.max(0L, offset));
        result.put("eof", true);
        result.put("reset", false);
        result.put("size", 0L);

        BuildJobStage stage = buildJobStageMapper.selectById(stageId);
        if (stage == null || !buildJobId.equals(stage.getBuildJobId())) {
            result.put("log", "阶段不存在");
            result.put("status", "UNKNOWN");
            result.put("running", false);
            return result;
        }

        String status = stage.getStatus() != null ? stage.getStatus() : "PENDING";
        boolean running = "RUNNING".equalsIgnoreCase(status) || "IN_PROGRESS".equalsIgnoreCase(status);
        result.put("status", status);
        result.put("running", running);
        result.put("stepName", stage.getStepName());
        result.put("stepType", stage.getStepType());
        result.put("logPath", stage.getLogPath());

        // 优先读文件日志
        if (stage.getLogPath() != null && stageLogStore.exists(stage.getLogPath())) {
            StageLogStore.ReadResult read = stageLogStore.readFromOffset(
                stage.getLogPath(), offset, StageLogStore.DEFAULT_READ_CHUNK_BYTES);
            result.put("log", read.getContent() != null ? read.getContent() : "");
            result.put("offset", read.getOffset());
            result.put("nextOffset", read.getNextOffset());
            result.put("size", read.getSize());
            result.put("eof", read.isEof());
            result.put("reset", read.isReset());
            if (read.isReset()) {
                result.put("log", "");
            }
            return result;
        }

        // 兼容旧数据：整段落在 log_text
        String legacy = stage.getLogText();
        if (legacy == null || legacy.isEmpty()) {
            if (stage.getLogPreview() != null && !stage.getLogPreview().isEmpty()) {
                legacy = stage.getLogPreview();
            } else if (stage.getErrorMessage() != null && !stage.getErrorMessage().isEmpty()) {
                legacy = stage.getErrorMessage();
            } else if (running) {
                legacy = "步骤执行中，正在等待日志输出...";
            } else {
                legacy = "暂无日志";
            }
        }
        byte[] bytes = legacy.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        result.put("size", (long) bytes.length);
        long start = Math.max(0L, offset);
        if (start >= bytes.length) {
            result.put("log", "");
            result.put("nextOffset", (long) bytes.length);
            result.put("eof", true);
            return result;
        }
        int len = (int) Math.min(StageLogStore.DEFAULT_READ_CHUNK_BYTES, bytes.length - start);
        result.put("log", new String(bytes, (int) start, len, java.nio.charset.StandardCharsets.UTF_8));
        result.put("nextOffset", start + len);
        result.put("eof", start + len >= bytes.length);
        return result;
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
        assertJobEnvAccessible(buildJob);
        BuildJob anchor = resolveAnchorJob(buildJob);
        BuildJob latest = resolveLatestJobInGroup(anchor);
        return toTaskView(anchor, latest);
    }

    /**
     * 流水线视图「重新构建」：在同一任务组（taskName + service + env + pipeline）下新建一次 BuildJob 并执行。
     * <p><b>业务流程</b></p>
     * <ol>
     *   <li>解析 anchor 与组内最新 Job 作为配置来源（分支、模板、构建参数等）；</li>
     *   <li>若组内仍有 BUILDING/DEPLOYING 任务则拒绝；</li>
     *   <li>insert 新 BuildJob（BUILDING + startTime），{@code deployTaskId} 为空（非 CD 路径）；</li>
     *   <li>调用 {@link #prepareAndExecuteAsync} 初始化阶段并异步执行。</li>
     * </ol>
     * 与上线任务 CD 的区别：不经过 {@link DeployTaskCdService}，不会设置 deployTaskId，也不会触发 CD 回写。
     *
     * @param jobId 组内任意一次历史 BuildJob ID（用于定位任务组）
     * @return 新建 BuildJob 的基本信息（jobId、jobNumber、status 等）
     */
    @Override
    @Transactional
    public BuildResponse rebuildPipelineJob(Long jobId) {
        BuildJob ref = buildJobMapper.selectById(jobId);
        if (ref == null) {
            throw new BusinessException("流水线任务不存在");
        }
        assertJobEnvAccessible(ref);
        if (ref.getDeployTaskId() != null) {
            return deployTaskCdService.retryCdJob(ref.getId());
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
        // Run 必须跑完整 CI+CD：若上次是回滚，buildParameters 会残留 rollback=true，需剔除
        newJob.setBuildParameters(copyParamsForFullRebuild(configSource.getBuildParameters()));
        newJob.setStatus(BuildStatus.BUILDING);
        newJob.setStartTime(LocalDateTime.now());
        BuildJobCreatorUtils.stampCreator(objectMapper, newJob,
                getCurrentUserId() != null ? getCurrentUserId() : configSource.getCreatorId(),
                getCurrentUsername());
        buildJobMapper.insert(newJob);

        // 同步准备阶段 + 提交后异步执行
        self.launchJob(newJob.getId());

        BuildResponse response = new BuildResponse();
        response.setJobId(newJob.getId());
        response.setJobNumber(newJob.getJobNumber());
        response.setTaskName(newJob.getTaskName());
        response.setStatus(BuildStatus.BUILDING);
        response.setStartTime(newJob.getStartTime());
        return response;
    }

    @Override
    public List<RollbackCandidateDTO> listRollbackCandidates(Long jobId) {
        BuildJob ref = buildJobMapper.selectById(jobId);
        if (ref == null) {
            throw new BusinessException("流水线任务不存在");
        }
        BuildJob latest = resolveLatestJobInGroup(resolveAnchorJob(ref));
        List<BuildJob> successJobs = listSuccessfulJobsWithImage(latest.getServiceId(), latest.getEnvId());
        String currentImage = firstNonEmpty(latest.getImageFullName(),
                parseBuildParamMap(latest.getBuildParameters()).get("imageFullName"),
                parseBuildParamMap(latest.getBuildParameters()).get("image"));

        List<RollbackCandidateDTO> result = new ArrayList<>();
        Set<String> seenImages = new HashSet<>();
        for (BuildJob job : successJobs) {
            String image = firstNonEmpty(job.getImageFullName(),
                    parseBuildParamMap(job.getBuildParameters()).get("imageFullName"),
                    parseBuildParamMap(job.getBuildParameters()).get("image"));
            if (image == null || image.isEmpty() || !seenImages.add(image)) {
                continue;
            }
            RollbackCandidateDTO dto = new RollbackCandidateDTO();
            dto.setJobId(job.getId());
            dto.setJobNumber(job.getJobNumber());
            dto.setImageFullName(image);
            dto.setImageTag(firstNonEmpty(job.getImageTag(), job.getBranch(),
                    parseBuildParamMap(job.getBuildParameters()).get("imageTag")));
            if (job.getCreateTime() != null) {
                dto.setCreateTimeText(job.getCreateTime().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
            }
            dto.setCurrent(currentImage != null && currentImage.equals(image)
                    && BuildStatus.SUCCESS.equals(latest.getStatus()));
            result.add(dto);
            if (result.size() >= 20) {
                break;
            }
        }
        return result;
    }

    @Override
    @Transactional
    public BuildResponse rollbackPipelineJob(Long jobId, RollbackRequest request) {
        if (request == null || request.getSourceJobId() == null) {
            throw new BusinessException("请选择要回滚到的历史版本");
        }
        BuildJob ref = buildJobMapper.selectById(jobId);
        if (ref == null) {
            throw new BusinessException("流水线任务不存在");
        }
        assertJobEnvAccessible(ref);
        BuildJob anchor = resolveAnchorJob(ref);
        BuildJob latest = resolveLatestJobInGroup(anchor);
        if (isGroupRunning(anchor)) {
            throw new BusinessException("任务正在运行中，请稍后再试");
        }

        Pipeline origin = pipelineMapper.selectById(latest.getPipelineTemplateId());
        if (origin == null || origin.getStatus() == null || origin.getStatus() != 1) {
            throw new BusinessException("Pipeline 模版不存在或已禁用");
        }
        if (!PipelineTypes.supportsImageRollback(origin.getPipelineType())) {
            throw new BusinessException("仅 CD / CI/CD 流水线支持回滚到历史镜像版本");
        }

        Env env = envMapper.selectById(latest.getEnvId());
        if (env != null && env.isProdEnv() && !Boolean.TRUE.equals(request.getConfirmed())) {
            throw new BusinessException("生产环境回滚需要二次确认");
        }

        BuildJob source = buildJobMapper.selectById(request.getSourceJobId());
        if (source == null) {
            throw new BusinessException("回滚目标版本不存在");
        }
        if (!BuildStatus.SUCCESS.equals(source.getStatus())) {
            throw new BusinessException("只能回滚到历史成功版本");
        }
        if (!Objects.equals(source.getServiceId(), latest.getServiceId())
                || !Objects.equals(source.getEnvId(), latest.getEnvId())) {
            throw new BusinessException("回滚目标必须属于同一服务与环境");
        }
        String imageFullName = firstNonEmpty(source.getImageFullName(),
                parseBuildParamMap(source.getBuildParameters()).get("imageFullName"),
                parseBuildParamMap(source.getBuildParameters()).get("image"));
        if (imageFullName == null || imageFullName.isEmpty()) {
            throw new BusinessException("回滚目标缺少镜像地址");
        }
        String imageTag = firstNonEmpty(source.getImageTag(), source.getBranch(),
                parseBuildParamMap(source.getBuildParameters()).get("imageTag"));
        if (imageTag == null || imageTag.isEmpty()) {
            int colon = imageFullName.lastIndexOf(':');
            imageTag = colon > 0 ? imageFullName.substring(colon + 1) : imageFullName;
        }

        String currentImage = firstNonEmpty(latest.getImageFullName(),
                parseBuildParamMap(latest.getBuildParameters()).get("imageFullName"),
                parseBuildParamMap(latest.getBuildParameters()).get("image"));
        if (imageFullName.equals(currentImage) && BuildStatus.SUCCESS.equals(latest.getStatus())) {
            throw new BusinessException("所选版本已是当前成功部署版本，无需回滚");
        }

        Map<String, String> params = new HashMap<>(parseBuildParamMap(latest.getBuildParameters()));
        Map<String, String> sourceParams = parseBuildParamMap(source.getBuildParameters());
        for (String key : Arrays.asList("clusterId", "k8sNamespace", "harborProject",
                "servicePort", "port", "serviceName", "serviceCode")) {
            if (sourceParams.containsKey(key) && !params.containsKey(key)) {
                params.put(key, sourceParams.get(key));
            }
        }
        params.put("imageFullName", imageFullName);
        params.put("image", imageFullName);
        params.put("imageTag", imageTag);
        params.put("rollback", "true");
        params.put("rollbackFromJobId", String.valueOf(source.getId()));
        params.put("rollbackFromImage", currentImage != null ? currentImage : "");

        // 在原 Job 上更新镜像与参数，不新建任务
        latest.setImageTag(imageTag);
        latest.setImageFullName(imageFullName);
        latest.setBranch(imageTag);
        latest.setGitType("tag");
        latest.setErrorMessage(null);
        latest.setEndTime(null);
        latest.setStatus(BuildStatus.PENDING);
        latest.setUpdateTime(LocalDateTime.now());
        try {
            latest.setBuildParameters(objectMapper.writeValueAsString(params));
        } catch (Exception e) {
            throw new BusinessException("序列化回滚参数失败");
        }
        buildJobMapper.updateById(latest);

        resetCdStagesForRollback(latest.getId(), origin);

        if (latest.getDeployTaskId() != null) {
            DeployTask task = deployTaskMapper.selectById(latest.getDeployTaskId());
            if (task != null && !"deploying".equalsIgnoreCase(task.getTaskStatus())) {
                task.setTaskStatus("deploying");
                task.setUpdateTime(LocalDateTime.now());
                deployTaskMapper.updateById(task);
            }
        }

        log.info("原 Job 回滚启动: jobId={}, fromJobId={}, image={}", latest.getId(), source.getId(), imageFullName);
        self.launchJob(latest.getId());

        BuildResponse response = new BuildResponse();
        response.setJobId(latest.getId());
        response.setJobNumber(latest.getJobNumber());
        response.setTaskName(latest.getTaskName());
        response.setStatus(BuildStatus.BUILDING);
        response.setImageTag(imageTag);
        response.setStartTime(LocalDateTime.now());
        return response;
    }

    /**
     * 回滚前仅重置 CD 阶段为 PENDING，CI 阶段保留原成功记录。
     */
    private void resetCdStagesForRollback(Long buildJobId, Pipeline pipeline) {
        List<PipelineStepDTO> steps;
        try {
            steps = pipelineStepResolveService.resolveSteps(parseSteps(pipeline.getStepsConfig()));
        } catch (Exception e) {
            throw new BusinessException("解析 Pipeline 步骤失败: " + e.getMessage());
        }
        if (steps.isEmpty()) {
            return;
        }
        Map<Integer, BuildJobStage> stageByOrder = loadOrCreateStageRecords(buildJobId, steps);
        LocalDateTime now = LocalDateTime.now();
        for (PipelineStepDTO step : steps) {
            int order = step.getOrder() != null ? step.getOrder() : 0;
            BuildJobStage stage = stageByOrder.get(order);
            if (stage == null) {
                continue;
            }
            if (isCiOnlyStepType(step.getStepType())) {
                continue;
            }
            stage.setStatus("PENDING");
            stage.setDurationMs(0L);
            stage.setErrorMessage(null);
            stage.setStartTime(null);
            stage.setEndTime(null);
            stage.setLogPreview(null);
            stage.setLogText(null);
            // 保留 logPath，执行时 onStageStart 会覆盖写入新日志
            if (stage.getLogPath() == null || stage.getLogPath().isEmpty()) {
                stage.setLogPath(stageLogStore.relativePath(buildJobId, stage.getId()));
            }
            stage.setUpdateTime(now);
            buildJobStageMapper.updateById(stage);
        }
    }

    private boolean isCiOnlyStepType(String stepType) {
        if (stepType == null) {
            return false;
        }
        String t = stepType.trim().toLowerCase();
        return "checkout".equals(t)
            || "build".equals(t)
            || "clean".equals(t)
            || "clean_workspace".equals(t)
            || "docker_build".equals(t)
            || "docker-build".equals(t)
            || "push_image".equals(t)
            || "push-image".equals(t);
    }

    private boolean canRollback(BuildJob latest) {
        if (latest == null || latest.getServiceId() == null || latest.getEnvId() == null) {
            return false;
        }
        if (latest.getPipelineTemplateId() == null) {
            return false;
        }
        Pipeline pipeline = pipelineMapper.selectById(latest.getPipelineTemplateId());
        if (pipeline == null) {
            return false;
        }
        if (!PipelineTypes.supportsImageRollback(pipeline.getPipelineType())) {
            return false;
        }
        List<BuildJob> successJobs = listSuccessfulJobsWithImage(latest.getServiceId(), latest.getEnvId());
        if (successJobs.isEmpty()) {
            return false;
        }
        String currentImage = firstNonEmpty(latest.getImageFullName(),
                parseBuildParamMap(latest.getBuildParameters()).get("imageFullName"),
                parseBuildParamMap(latest.getBuildParameters()).get("image"));
        for (BuildJob job : successJobs) {
            String image = firstNonEmpty(job.getImageFullName(),
                    parseBuildParamMap(job.getBuildParameters()).get("imageFullName"),
                    parseBuildParamMap(job.getBuildParameters()).get("image"));
            if (image == null || image.isEmpty()) {
                continue;
            }
            if (!BuildStatus.SUCCESS.equals(latest.getStatus()) || currentImage == null || !image.equals(currentImage)) {
                return true;
            }
        }
        // 仅有当前成功版本、没有更早版本时不可回滚
        return successJobs.size() > 1;
    }

    private List<BuildJob> listSuccessfulJobsWithImage(Long serviceId, Long envId) {
        if (serviceId == null || envId == null) {
            return Collections.emptyList();
        }
        QueryWrapper<BuildJob> wrapper = new QueryWrapper<>();
        wrapper.eq("service_id", serviceId)
                .eq("env_id", envId)
                .eq("status", BuildStatus.SUCCESS)
                .and(w -> w.isNotNull("image_full_name").ne("image_full_name", "")
                        .or().isNotNull("build_parameters").ne("build_parameters", ""))
                .orderByDesc("create_time")
                .last("LIMIT 50");
        List<BuildJob> jobs = buildJobMapper.selectList(wrapper);
        List<BuildJob> filtered = new ArrayList<>();
        for (BuildJob job : jobs) {
            String image = firstNonEmpty(job.getImageFullName(),
                    parseBuildParamMap(job.getBuildParameters()).get("imageFullName"),
                    parseBuildParamMap(job.getBuildParameters()).get("image"));
            if (image != null && !image.isEmpty()) {
                filtered.add(job);
            }
        }
        return filtered;
    }

    @Override
    @Transactional
    public PipelineJobViewDTO updatePipelineJob(Long buildJobId, BuildRequest request) {
        BuildJob buildJob = buildJobMapper.selectById(buildJobId);
        if (buildJob == null) {
            throw new BusinessException("流水线任务不存在");
        }
        assertJobEnvAccessible(buildJob);
        if (buildJob.getDeployTaskId() != null) {
            throw new BusinessException("上线任务自动创建的 CD 任务不支持手动编辑");
        }
        if (isGroupRunning(buildJob)) {
            throw new BusinessException("任务运行中，无法编辑");
        }

        validateUpdateRequest(request);
        pipelineViewService.assertAccessibleEnv(request.getEnvId());

        com.opsflow.dao.model.Service service = serviceMapper.selectById(request.getServiceId());
        if (service == null) {
            throw new BusinessException("服务不存在");
        }
        String taskName = resolveTaskName(request, service);
        Env env = envMapper.selectById(request.getEnvId());
        if (env == null) {
            throw new BusinessException("环境不存在");
        }
        if (env.isProdEnv()) {
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
        assertJobEnvAccessible(buildJob);
        if (buildJob.getDeployTaskId() != null) {
            if (isJobRunning(buildJob)) {
                throw new BusinessException("任务运行中，无法删除");
            }
            buildJobStageMapper.delete(new QueryWrapper<BuildJobStage>().eq("build_job_id", buildJob.getId()));
            stageLogStore.deleteJobLogs(buildJob.getId());
            return buildJobMapper.deleteById(buildJob.getId()) > 0;
        }
        if (isGroupRunning(buildJob)) {
            throw new BusinessException("任务运行中，无法删除");
        }

        List<BuildJob> groupJobs = listJobsInGroup(buildJob);
        for (BuildJob job : groupJobs) {
            buildJobStageMapper.delete(new QueryWrapper<BuildJobStage>().eq("build_job_id", job.getId()));
            stageLogStore.deleteJobLogs(job.getId());
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

    private void assertJobEnvAccessible(BuildJob job) {
        if (job == null) {
            return;
        }
        pipelineViewService.assertAccessibleEnv(job.getEnvId());
    }

    private Long getCurrentUserId() {
        String username = getCurrentUsername();
        if (username == null || username.trim().isEmpty()) {
            return null;
        }
        User user = userMapper.selectOne(new QueryWrapper<User>().eq("username", username.trim()).last("LIMIT 1"));
        return user != null ? user.getId() : null;
    }

    private String getCurrentUsername() {
        try {
            RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
            if (attrs instanceof ServletRequestAttributes) {
                HttpServletRequest request = ((ServletRequestAttributes) attrs).getRequest();
                HttpSession session = request != null ? request.getSession(false) : null;
                Object user = session != null ? session.getAttribute("user") : null;
                if (user != null && !String.valueOf(user).trim().isEmpty()) {
                    return String.valueOf(user).trim();
                }
            }
        } catch (Exception e) {
            log.debug("读取当前登录用户名失败: {}", e.getMessage());
        }
        return null;
    }

    @Override
    public void recoverInterruptedRunningJobs() {
        List<BuildJob> runningJobs = buildJobMapper.selectList(
            new QueryWrapper<BuildJob>().in("status", BuildStatus.BUILDING, BuildStatus.DEPLOYING)
        );
        if (runningJobs.isEmpty()) {
            return;
        }
        log.info("启动时发现 {} 条运行中的流水线任务，将标记为失败", runningJobs.size());
        for (BuildJob job : runningJobs) {
            markInterruptedRunningJob(job, "服务重启导致任务中断，已自动标记为失败");
        }
    }

    private void refreshStaleRunningJob(BuildJob job) {
        if (job == null) {
            return;
        }
        if (!BuildStatus.BUILDING.equals(job.getStatus()) && !BuildStatus.DEPLOYING.equals(job.getStatus())) {
            return;
        }

        List<BuildJobStage> stages = buildJobStageMapper.selectList(
            new QueryWrapper<BuildJobStage>()
                .eq("build_job_id", job.getId())
                .orderByAsc("step_order")
        );
        if (isStuckPreparedJob(job, stages)) {
            markInterruptedRunningJob(job, "流水线已创建但未实际执行，已自动标记为失败，请重试");
            return;
        }

        if (!activeRunningJobIds.contains(job.getId())) {
            LocalDateTime started = job.getStartTime() != null ? job.getStartTime() : job.getUpdateTime();
            if (started == null || started.isAfter(LocalDateTime.now().minusSeconds(RUNNING_JOB_GRACE_SECONDS))) {
                return;
            }
            markInterruptedRunningJob(job, "任务未在本进程执行中，可能是服务重启导致中断，已自动标记为失败");
            return;
        }

        LocalDateTime threshold = LocalDateTime.now().minusMinutes(STALE_RUNNING_TIMEOUT_MINUTES);
        LocalDateTime lastHeartbeat = job.getUpdateTime() != null ? job.getUpdateTime() : job.getStartTime();

        for (BuildJobStage stage : stages) {
            if (stage.getUpdateTime() != null && (lastHeartbeat == null || stage.getUpdateTime().isAfter(lastHeartbeat))) {
                lastHeartbeat = stage.getUpdateTime();
            }
        }

        if (lastHeartbeat != null && lastHeartbeat.isAfter(threshold)) {
            return;
        }

        markInterruptedRunningJob(job, "任务执行长时间无状态更新，已自动标记为失败，可能是服务重启或执行中断导致");
    }

    private boolean isStuckPreparedJob(BuildJob job, List<BuildJobStage> stages) {
        LocalDateTime started = job.getStartTime() != null ? job.getStartTime() : job.getUpdateTime();
        if (started == null || started.isAfter(LocalDateTime.now().minusSeconds(PREPARED_BUT_NOT_STARTED_SECONDS))) {
            return false;
        }
        if (stages == null || stages.isEmpty()) {
            return true;
        }
        return stages.stream().allMatch(stage -> "PENDING".equalsIgnoreCase(stage.getStatus()));
    }

    private void markInterruptedRunningJob(BuildJob job, String message) {
        if (job == null || job.getId() == null) {
            return;
        }
        if (!BuildStatus.BUILDING.equals(job.getStatus()) && !BuildStatus.DEPLOYING.equals(job.getStatus())) {
            return;
        }

        List<BuildJobStage> stages = buildJobStageMapper.selectList(
            new QueryWrapper<BuildJobStage>()
                .eq("build_job_id", job.getId())
                .orderByAsc("step_order")
        );
        for (BuildJobStage stage : stages) {
            if ("RUNNING".equalsIgnoreCase(stage.getStatus())) {
                stage.setStatus("FAILURE");
                stage.setErrorMessage(message);
                stage.setEndTime(LocalDateTime.now());
                stage.setUpdateTime(LocalDateTime.now());
                buildJobStageMapper.updateById(stage);
            }
        }

        job.setStatus(BuildStatus.FAILED);
        job.setErrorMessage(message);
        if (job.getEndTime() == null) {
            job.setEndTime(LocalDateTime.now());
        }
        job.setUpdateTime(LocalDateTime.now());
        buildJobMapper.updateById(job);

        activeRunningJobIds.remove(job.getId());

        log.warn("已回收中断任务: jobId={}, jobNumber={}", job.getId(), job.getJobNumber());

        if (job.getDeployTaskId() != null) {
            try {
                deployTaskCdService.onBuildJobFinished(job.getId());
            } catch (Exception e) {
                log.error("回写上线任务状态失败(recover), jobId={}", job.getId(), e);
            }
        }
    }

    private PipelineJobViewDTO toJobView(BuildJob job) {
        PipelineJobViewDTO dto = new PipelineJobViewDTO();
        boolean fromDeployTask = isDeployTaskManaged(job);
        dto.setId(job.getId());
        dto.setJobNumber(job.getJobNumber());
        dto.setTaskName(job.getTaskName());
        dto.setBranch(job.getBranch());
        dto.setGitType(resolveJobGitType(job));
        dto.setStatus(job.getStatus());
        dto.setBuilding(BuildStatus.BUILDING.equals(job.getStatus()) || BuildStatus.DEPLOYING.equals(job.getStatus()));
        dto.setFromDeployTask(fromDeployTask);
        dto.setEditable(!isJobRunning(job) && !fromDeployTask);
        dto.setDeletable(!isJobRunning(job));
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

    /**
     * 将 BuildJob + 服务/环境/Pipeline 配置转换为 {@link PipelineExecutionContext}，供各 StepExecutor 使用。
     * <p><b>关键参数如何进入上下文</b></p>
     * <ul>
     *   <li><b>imageFullName / imageTag</b>：优先级为 {@code buildJob.imageFullName/imageTag} &gt;
     *       {@code buildParameters} JSON 中的 {@code imageFullName/image/imageTag}。
     *       CD 场景下 DeployTaskCdService 通常在创建 Job 时已写入镜像信息（跳过构建、仅部署）；</li>
     *   <li><b>k8sNamespace</b>：{@code buildParameters.k8sNamespace} 优先，否则取环境的 {@code env.k8sNamespace}，
     *       写入 {@code EnvironmentInfo} 与 {@code parameters.k8sNamespace}；</li>
     *   <li><b>servicePort / port</b>：来自 {@code service.servicePort}，默认 8080，同时写入 {@code parameters}
     *       供 render_template 等步骤的 {@code ${port}} / {@code ${servicePort}} 占位符；</li>
     *   <li><b>clusterId</b>：环境默认 clusterId，可被 {@code buildParameters.clusterId} 覆盖（CD 指定集群）；</li>
     *   <li><b>构建/部署节点选择</b>：{@link #selectBuildNodeId} 从 Pipeline 的 buildNodeIds 中按负载选一台，
     *       写入 {@code options.buildNodeId}；{@code options.deployNodeId} 取自 Pipeline.deployNodeId；
     *       选中节点的 workDir 写入 {@code parameters.workspaceBase}。</li>
     * </ul>
     * {@code buildParameters} 中其余键值也会合并进 {@code parameters}（不覆盖已有键），供后续步骤读取。
     *
     * @param buildJob 当前执行的构建任务
     * @param service  关联服务（git、端口、K8s deployment 名等）
     * @param env      目标环境（命名空间、集群）
     * @param pipeline Pipeline 模板（构建/部署节点配置）
     * @return 填充完毕的执行上下文
     */
    private PipelineExecutionContext buildContext(BuildJob buildJob, com.opsflow.dao.model.Service service, Env env, Pipeline pipeline) {
        PipelineExecutionContext context = new PipelineExecutionContext();
        context.setExecutionId(String.valueOf(buildJob.getId()));

        PipelineExecutionContext.ServiceInfo serviceInfo = new PipelineExecutionContext.ServiceInfo();
        serviceInfo.setId(service.getId());
        serviceInfo.setName(service.getName());
        serviceInfo.setCode(service.getCode());
        serviceInfo.setComponentId(service.getComponentId());
        serviceInfo.setGitRepo(service.getGitRepo());
        serviceInfo.setBranch(buildJob.getBranch());
        serviceInfo.setBuildCommand(service.getBuildCommand());
        serviceInfo.setDockerfilePath(service.getDockerfilePath());
        serviceInfo.setServicePort(service.getServicePort());
        String deployment = service.getK8sDeployment();
        if (deployment == null || deployment.trim().isEmpty()) {
            deployment = service.getCode();
        }
        serviceInfo.setK8sDeployment(deployment);
        context.setService(serviceInfo);

        Map<String, String> extraParams = parseBuildParamMap(buildJob.getBuildParameters());

        PipelineExecutionContext.EnvironmentInfo envInfo = new PipelineExecutionContext.EnvironmentInfo();
        envInfo.setId(env.getId());
        envInfo.setName(env.getName());
        Long clusterId = env.getClusterId();
        String overrideCluster = firstNonEmpty(extraParams.get("clusterId"), null);
        if (overrideCluster != null) {
            try {
                clusterId = Long.parseLong(overrideCluster.trim());
            } catch (NumberFormatException ignore) {
            }
        }
        envInfo.setClusterId(clusterId);
        String namespace = firstNonEmpty(extraParams.get("k8sNamespace"), env.getK8sNamespace());
        envInfo.setK8sNamespace(namespace);
        String harborRegistry = harborCredentialService.getRegistryHost(null);
        if (harborRegistry != null && !harborRegistry.isEmpty()) {
            envInfo.setHarborRegistry(harborRegistry);
        }
        context.setEnvironment(envInfo);

        Map<String, String> params = new HashMap<>();
        params.put("gitRepo", service.getGitRepo());
        params.put("branch", buildJob.getBranch());
        params.put("serviceName", service.getCode());
        params.put("serviceCode", service.getCode());
        params.put("envName", env.getName());
        String servicePort = service.getServicePort() != null && service.getServicePort() > 0
            ? String.valueOf(service.getServicePort()) : "8080";
        params.put("servicePort", servicePort);
        // 兼容模版 ${port}
        params.put("port", servicePort);
        if (namespace != null) {
            params.put("k8sNamespace", namespace);
        }
        String imageFullName = firstNonEmpty(
                buildJob.getImageFullName(),
                extraParams.get("imageFullName"),
                extraParams.get("image"));
        if (imageFullName != null) {
            params.put("imageFullName", imageFullName);
            params.put("image", imageFullName);
        }
        String imageTag = firstNonEmpty(buildJob.getImageTag(), extraParams.get("imageTag"));
        if (imageTag != null) {
            params.put("imageTag", imageTag);
        }
        for (Map.Entry<String, String> entry : extraParams.entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null && !params.containsKey(entry.getKey())) {
                params.put(entry.getKey(), entry.getValue());
            }
        }

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

    private Map<String, String> parseBuildParamMap(String json) {
        Map<String, String> result = new HashMap<>();
        if (json == null || json.trim().isEmpty()) {
            return result;
        }
        try {
            Map<String, Object> raw = objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
            if (raw != null) {
                for (Map.Entry<String, Object> entry : raw.entrySet()) {
                    if (entry.getKey() != null && entry.getValue() != null) {
                        result.put(entry.getKey(), String.valueOf(entry.getValue()));
                    }
                }
            }
        } catch (Exception e) {
            log.debug("解析 buildParameters 失败: {}", e.getMessage());
        }
        return result;
    }

    /**
     * 复制构建参数供「Run」完整重建：去掉回滚标记，避免误跳过 CI。
     */
    private String copyParamsForFullRebuild(String sourceJson) {
        Map<String, String> params = new HashMap<>(parseBuildParamMap(sourceJson));
        params.remove("rollback");
        params.remove("rollbackFromJobId");
        params.remove("rollbackFromImage");
        if (params.isEmpty()) {
            return sourceJson == null || sourceJson.trim().isEmpty() ? null : "{}";
        }
        try {
            return objectMapper.writeValueAsString(params);
        } catch (Exception e) {
            log.warn("序列化重建参数失败，回退原参数并尝试剔除 rollback: {}", e.getMessage());
            return sourceJson;
        }
    }

    private String firstNonEmpty(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) {
                return value.trim();
            }
        }
        return null;
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
            private volatile long lastFlushMs = 0L;
            private static final long FLUSH_INTERVAL_MS = 500L;

            @Override
            public void onStageStart(int stepOrder, String stepType, String stepName) {
                BuildJobStage stage = stageByOrder.get(stepOrder);
                if (stage == null) {
                    return;
                }
                stage.setStatus("RUNNING");
                stage.setStartTime(LocalDateTime.now());
                stage.setUpdateTime(LocalDateTime.now());
                stage.setErrorMessage(null);
                stage.setLogText(null);

                String relativePath = stageLogStore.relativePath(stage.getBuildJobId(), stage.getId());
                stage.setLogPath(relativePath);
                String header = "==== 开始执行: " + (stepName != null ? stepName : stepType)
                    + " (" + stepType + ") ====\n"
                    + "开始时间: " + LocalDateTime.now() + "\n\n";
                stageLogStore.writeFull(relativePath, header);
                stage.setLogPreview(normalizeLogPreview(header));
                buildJobStageMapper.updateById(stage);
                lastFlushMs = System.currentTimeMillis();
            }

            @Override
            public void onStageLog(int stepOrder, String chunk) {
                if (chunk == null || chunk.isEmpty()) {
                    return;
                }
                BuildJobStage stage = stageByOrder.get(stepOrder);
                if (stage == null) {
                    return;
                }
                synchronized (this) {
                    String path = stage.getLogPath();
                    if (path == null || path.isEmpty()) {
                        path = stageLogStore.relativePath(stage.getBuildJobId(), stage.getId());
                        stage.setLogPath(path);
                    }
                    // 单文件保护约 20MB
                    if (stageLogStore.size(path) > 20L * 1024 * 1024) {
                        return;
                    }
                    stageLogStore.append(path, chunk);
                    long now = System.currentTimeMillis();
                    if (now - lastFlushMs >= FLUSH_INTERVAL_MS) {
                        stage.setLogPreview(normalizeLogPreview(
                            stageLogStore.tailPreview(path, StageLogStore.DEFAULT_PREVIEW_CHARS)));
                        stage.setLogText(null);
                        stage.setUpdateTime(LocalDateTime.now());
                        buildJobStageMapper.updateById(stage);
                        lastFlushMs = now;
                    }
                }
            }

            @Override
            public void onStageComplete(int stepOrder, boolean success, String log, String errorMessage, long durationMs) {
                BuildJobStage stage = stageByOrder.get(stepOrder);
                if (stage == null) {
                    return;
                }
                synchronized (this) {
                    String path = stage.getLogPath();
                    if (path == null || path.isEmpty()) {
                        path = stageLogStore.relativePath(stage.getBuildJobId(), stage.getId());
                        stage.setLogPath(path);
                    }

                    long fileSize = stageLogStore.size(path);
                    if (log != null && !log.trim().isEmpty()) {
                        // 步骤返回完整日志：覆盖写入，保证终态内容清晰
                        stageLogStore.writeFull(path, log);
                    } else if (!success && errorMessage != null && !errorMessage.trim().isEmpty()) {
                        if (fileSize <= 0) {
                            stageLogStore.writeFull(path, "[错误] " + errorMessage + "\n");
                        } else {
                            stageLogStore.append(path, "\n[错误] " + errorMessage + "\n");
                        }
                    }

                    stage.setStatus(success ? "SUCCESS" : "FAILURE");
                    stage.setDurationMs(durationMs);
                    stage.setErrorMessage(errorMessage);
                    stage.setLogPreview(normalizeLogPreview(
                        stageLogStore.tailPreview(path, StageLogStore.DEFAULT_PREVIEW_CHARS)));
                    stage.setLogText(null);
                    stage.setEndTime(LocalDateTime.now());
                    stage.setUpdateTime(LocalDateTime.now());
                    buildJobStageMapper.updateById(stage);
                    lastFlushMs = System.currentTimeMillis();
                }
            }

            @Override
            public void onStageSkipped(int stepOrder, String reason) {
                BuildJobStage stage = stageByOrder.get(stepOrder);
                if (stage == null) {
                    return;
                }
                synchronized (this) {
                    String path = stage.getLogPath();
                    if (path == null || path.isEmpty()) {
                        path = stageLogStore.relativePath(stage.getBuildJobId(), stage.getId());
                        stage.setLogPath(path);
                    }
                    String text = reason != null ? reason : "已跳过";
                    stageLogStore.writeFull(path, text.endsWith("\n") ? text : text + "\n");
                    stage.setStatus("SKIPPED");
                    stage.setDurationMs(0L);
                    stage.setErrorMessage(null);
                    stage.setLogPreview(normalizeLogPreview(text));
                    stage.setLogText(null);
                    stage.setStartTime(LocalDateTime.now());
                    stage.setEndTime(LocalDateTime.now());
                    stage.setUpdateTime(LocalDateTime.now());
                    buildJobStageMapper.updateById(stage);
                    lastFlushMs = System.currentTimeMillis();
                }
            }

            private String normalizeLogPreview(String text) {
                if (text == null) {
                    return null;
                }
                if (text.length() <= BUILD_JOB_STAGE_LOG_PREVIEW_MAX_CHARS) {
                    return text;
                }
                return text.substring(text.length() - BUILD_JOB_STAGE_LOG_PREVIEW_MAX_CHARS);
            }
        };
    }

    /**
     * 提前终止构建：将 BuildJob 标记为 FAILED 并立即落库。
     * <p>
     * 用于 {@link #prepareAndExecuteAsync} 预检失败（模板不存在、步骤解析失败等）或
     * {@link #executeBuildJobAsync} 前置校验失败，此时 Pipeline 尚未或未能完整执行。
     * </p>
     * <p><b>落库内容</b>：status=FAILED、errorMessage、endTime。</p>
     * <p>
     * 若 {@code deployTaskId != null}，同步调用 {@link DeployTaskCdService#onBuildJobFinished}，
     * 与 {@link #executeBuildJobAsync} 的 {@code finally} 回写逻辑一致，避免 CD 上线任务卡在「构建中」。
     * </p>
     *
     * @param buildJob 待失败的构建任务（须含 id）
     * @param message  失败原因，写入 errorMessage
     */
    private void failBuildJob(BuildJob buildJob, String message) {
        activeRunningJobIds.remove(buildJob.getId());
        buildJob.setStatus(BuildStatus.FAILED);
        buildJob.setErrorMessage(message);
        buildJob.setEndTime(LocalDateTime.now());
        buildJobMapper.updateById(buildJob);
        if (buildJob.getDeployTaskId() != null) {
            try {
                // 预检/解析失败等提前终止路径，与 executeBuildJobAsync finally 保持一致
                deployTaskCdService.onBuildJobFinished(buildJob.getId());
            } catch (Exception e) {
                log.error("回写上线任务状态失败(fail), jobId={}", buildJob.getId(), e);
            }
        }
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
