package com.opsflow.service.impl.pipeline;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.opsflow.api.dto.PipelineDTO;
import com.opsflow.api.dto.PipelineStepDTO;
import com.opsflow.common.constant.BuildStatus;
import com.opsflow.common.exception.BusinessException;
import com.opsflow.dao.mapper.*;
import com.opsflow.dao.model.*;
import com.opsflow.integration.harbor.HarborCredentialService;
import com.opsflow.integration.pipeline.PipelineExecutionContext;
import com.opsflow.integration.pipeline.PipelineExecutionResult;
import com.opsflow.integration.pipeline.PipelineExecutor;
import com.opsflow.integration.pipeline.PipelineJobDispatcher;
import com.opsflow.integration.pipeline.PipelineStageListener;
import com.opsflow.integration.pipeline.StageLogStore;
import com.opsflow.service.DeployTaskCdService;
import com.opsflow.service.PipelineStepResolveService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.*;

/**
 * 流水线任务执行：准备 BUILDING + 阶段，事务提交后派发执行线程，驱动 PipelineExecutor 并落库终态。
 * <p>由 {@link com.opsflow.service.impl.PipelineRunServiceImpl} 拆分而来，行为保持不变。</p>
 */
@Slf4j
@Component
public class PipelineJobRunner {
    private static final int BUILD_JOB_STAGE_LOG_PREVIEW_MAX_CHARS = 3800;

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
    @Qualifier("nativePipelineExecutor")
    private PipelineExecutor pipelineExecutor;

    @Autowired
    private PipelineStepResolveService pipelineStepResolveService;

    @Autowired
    private HarborCredentialService harborCredentialService;

    @Autowired
    private StageLogStore stageLogStore;

    @Autowired
    private PipelineJobDispatcher pipelineJobDispatcher;

    @Autowired
    @Lazy
    private DeployTaskCdService deployTaskCdService;

    @Autowired
    private PipelineJobSupport support;

    @Autowired
    private PipelineJobLiveness liveness;

    @Autowired
    private BuildNodeSelector nodeSelector;

    /**
     * 唯一可靠的 job 启动入口：校验并准备 BUILDING + 阶段，事务提交后派发执行线程。
     */
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
            steps = pipelineStepResolveService.resolveSteps(support.parseSteps(pipeline.getStepsConfig()));
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
            support.createStageRecords(buildJobId, steps);
        }

        log.info("launchJob 准备完成，等待事务提交后派发: jobId={}", buildJobId);
        runAsyncAfterCommit(buildJobId);
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
        log.info("事务已提交，提交流水线执行: jobId={}", buildJobId);
        pipelineJobDispatcher.submit(buildJobId, waitedMillis -> {
            try {
                if (waitedMillis >= 1000) {
                    markActualStart(buildJobId, waitedMillis);
                }
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

    /** 排队结束、真正开始执行时刷新开始时间，避免耗时把排队时间算进去 */
    private void markActualStart(Long buildJobId, long waitedMillis) {
        BuildJob update = new BuildJob();
        update.setId(buildJobId);
        update.setStartTime(LocalDateTime.now());
        update.setUpdateTime(LocalDateTime.now());
        buildJobMapper.updateById(update);
        log.info("流水线任务排队 {} 秒后开始执行: jobId={}", waitedMillis / 1000, buildJobId);
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
     * @param buildJobId 构建任务主键（须已通过 {@link #launchJob} 完成阶段初始化）
     */
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
        liveness.markActive(buildJobId);
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

                List<PipelineStepDTO> steps = pipelineStepResolveService.resolveSteps(support.parseSteps(pipeline.getStepsConfig()));
                if (steps.isEmpty()) {
                    throw new RuntimeException("Pipeline 步骤配置为空");
                }

                Map<Integer, BuildJobStage> stageByOrder = support.loadOrCreateStageRecords(buildJobId, steps);
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
                liveness.wakeServiceWaitersAfterCommit(buildJob);
            }
        } finally {
            liveness.markInactive(buildJobId);
        }
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
     *   <li><b>构建/部署节点选择</b>：{@link BuildNodeSelector#selectBuildNodeId} 从 Pipeline 的 buildNodeIds 中按负载选一台，
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

        Map<String, String> extraParams = support.parseBuildParamMap(buildJob.getBuildParameters());

        PipelineExecutionContext.EnvironmentInfo envInfo = new PipelineExecutionContext.EnvironmentInfo();
        envInfo.setId(env.getId());
        envInfo.setName(env.getName());
        Long clusterId = env.getClusterId();
        String overrideCluster = support.firstNonEmpty(extraParams.get("clusterId"), null);
        if (overrideCluster != null) {
            try {
                clusterId = Long.parseLong(overrideCluster.trim());
            } catch (NumberFormatException ignore) {
            }
        }
        envInfo.setClusterId(clusterId);
        String namespace = support.firstNonEmpty(extraParams.get("k8sNamespace"), env.getK8sNamespace());
        envInfo.setK8sNamespace(namespace);
        String harborRegistry = harborCredentialService.getRegistryHost(null);
        if (harborRegistry != null && !harborRegistry.isEmpty()) {
            envInfo.setHarborRegistry(harborRegistry);
        }
        context.setEnvironment(envInfo);

        Map<String, String> params = new HashMap<>();
        params.put("gitRepo", service.getGitRepo());
        params.put("branch", buildJob.getBranch());
        params.put("gitType", support.resolveJobGitType(buildJob));
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
        String imageFullName = support.firstNonEmpty(
                buildJob.getImageFullName(),
                extraParams.get("imageFullName"),
                extraParams.get("image"));
        if (imageFullName != null) {
            params.put("imageFullName", imageFullName);
            params.put("image", imageFullName);
        }
        String imageTag = support.firstNonEmpty(buildJob.getImageTag(), extraParams.get("imageTag"));
        if (imageTag != null) {
            params.put("imageTag", imageTag);
        }
        for (Map.Entry<String, String> entry : extraParams.entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null && !params.containsKey(entry.getKey())) {
                params.put(entry.getKey(), entry.getValue());
            }
        }

        Long selectedBuildNodeId = nodeSelector.selectBuildNodeId(pipeline);
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
            options.setBuildNodeId(selectedBuildNodeId);
            options.setDeployNodeId(pipeline.getDeployNodeId());
        }
        context.setOptions(options);
        return context;
    }

    private PipelineStageListener buildStageListener(Map<Integer, BuildJobStage> stageByOrder) {
        return new PipelineStageListener() {
            private volatile long lastFlushMs = 0L;
            /** 已提示过「日志超限」的步骤序号 */
            private int truncatedNoticeOrder = -1;
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
                    // 单文件保护约 20MB；超出后只提示一次，后面的输出不再记录（失败原因仍会在阶段结束时追加）
                    if (stageLogStore.size(path) > 20L * 1024 * 1024) {
                        if (truncatedNoticeOrder != stepOrder) {
                            truncatedNoticeOrder = stepOrder;
                            stageLogStore.append(path, "\n...... [日志超过 20MB，后续输出不再记录] ......\n");
                        }
                        return;
                    }
                    stageLogStore.append(path, chunk);
                    long now = System.currentTimeMillis();
                    if (now - lastFlushMs >= FLUSH_INTERVAL_MS) {
                        stage.setLogPreview(normalizeLogPreview(
                            stageLogStore.tailPreview(path, StageLogStore.DEFAULT_PREVIEW_CHARS)));
                        stage.setUpdateTime(LocalDateTime.now());
                        buildJobStageMapper.updateById(stage);
                        lastFlushMs = now;
                    }
                }
            }

            @Override
            public void onStageHeartbeat(int stepOrder) {
                BuildJobStage stage = stageByOrder.get(stepOrder);
                if (stage == null || stage.getId() == null) {
                    return;
                }
                synchronized (this) {
                    // 只刷新更新时间：步骤长时间没有输出时，僵死任务检查仍能看到它在运行
                    LocalDateTime now = LocalDateTime.now();
                    stage.setUpdateTime(now);
                    buildJobStageMapper.update(null, new UpdateWrapper<BuildJobStage>()
                        .eq("id", stage.getId())
                        .eq("status", "RUNNING")
                        .set("update_time", now));
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
                        // 步骤返回了整段日志（未实时落盘的情况）：覆盖写入。
                        // 正常运行时日志是实时追加的，步骤不再返回整段日志，文件内容保持不动
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
     * 用于 {@link #launchJob} 预检失败（模板不存在、步骤解析失败等）或
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
        liveness.markInactive(buildJob.getId());
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
        liveness.wakeServiceWaitersAfterCommit(buildJob);
    }
}
