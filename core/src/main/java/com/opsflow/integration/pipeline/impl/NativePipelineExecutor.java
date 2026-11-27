package com.opsflow.integration.pipeline.impl;

import com.opsflow.api.dto.PipelineDTO;
import com.opsflow.api.dto.PipelineStepDTO;
import com.opsflow.dao.mapper.BuildNodeMapper;
import com.opsflow.dao.model.BuildNode;
import com.opsflow.integration.pipeline.*;
import com.opsflow.integration.pipeline.step.StepExecutor;
import com.opsflow.integration.pipeline.step.StepExecutionResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.File;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * 原生 Pipeline 执行器：按步骤顺序调度 {@link StepExecutor}，合并参数并处理超时与阶段回调。
 * <p>
 * 由 {@link com.opsflow.service.impl.pipeline.PipelineJobRunner} 注入调用；
 * 阶段落库由上下文中的 {@link PipelineStageListener} 负责，本类仅维护内存级 executionStatusMap。
 * </p>
 */
@Slf4j
@Component("nativePipelineExecutor")
public class NativePipelineExecutor implements PipelineExecutor {

    private static final int DEFAULT_STEP_TIMEOUT_SECONDS = 60;

    /**
     * 等待步骤结果期间发送阶段心跳的间隔，需远小于僵死任务的判定阈值（10 分钟）
     */
    static final long STAGE_HEARTBEAT_INTERVAL_MS = 30000;

    /**
     * 步骤超时被中断后，最多等它多久收尾
     */
    static final long ABANDONED_STEP_GRACE_MS = 5000;
    
    @Autowired
    private List<StepExecutor> stepExecutors;

    @Autowired
    private BuildNodeMapper buildNodeMapper;

    private final ExecutorService stepExecutorPool = Executors.newCachedThreadPool(r -> {
        Thread thread = new Thread(r, "pipeline-step");
        thread.setDaemon(true);
        return thread;
    });
    
    /**
     * 运行中任务的执行状态；任务结束后移除
     */
    private Map<String, PipelineExecutionStatus> executionStatusMap = new ConcurrentHashMap<>();
    
    /**
     * 按顺序执行 Pipeline 全部启用步骤，任一步失败即中断并返回失败结果。
     * <p><b>步骤循环</b></p>
     * <ol>
     *   <li>解析 workspace 目录；过滤 enabled 步骤并按 order 排序；</li>
     *   <li>逐步：{@code onStageStart} → 查找 StepExecutor → 合并参数 → 执行 → {@code onStageComplete}；</li>
     *   <li>失败：设置 result.success=false，不再执行后续步骤；</li>
     *   <li>全部成功：从 {@code stepOutputs} 提取 imageTag/imageFullName 写入 result。</li>
     * </ol>
     * <p><b>参数合并优先级</b>（后者覆盖前者）：</p>
     * <ol>
     *   <li>{@code step.parameters}（模板步骤级配置）；</li>
     *   <li>{@code context.parameters}（{@code PipelineJobRunner#buildContext} 写入的全局参数）；</li>
     *   <li>{@code stepOutputs}（前置步骤 outputData，如 checkout 的 commitId、push_image 的 imageFullName、
     *       render_template 的 manifestDir/renderedManifests）。</li>
     * </ol>
     * <p><b>阶段回调</b>：若 context 注册了 {@link PipelineStageListener}，每步开始/结束触发回调，
     * PipelineRunServiceImpl 据此更新 {@code build_job_stage}。</p>
     * <p><b>超时</b>：每步 timeout 取自 {@code step.timeoutSeconds}，缺省 60 秒，由
     * {@link #executeStepWithTimeout} 在线程池中限时执行。</p>
     *
     * @param pipeline 含步骤列表的 Pipeline DTO
     * @param context  执行上下文（服务、环境、节点、stageListener 等）
     * @return 整体成功/失败、错误信息及镜像产出
     */
    @Override
    public PipelineExecutionResult execute(PipelineDTO pipeline, PipelineExecutionContext context) {
        String executionId = context.getExecutionId();
        if (executionId == null || executionId.isEmpty()) {
            executionId = UUID.randomUUID().toString();
            context.setExecutionId(executionId);
        }
        
        // 初始化执行状态
        PipelineExecutionStatus status = new PipelineExecutionStatus();
        status.setExecutionId(executionId);
        status.setStatus("RUNNING");
        status.setStartTime(System.currentTimeMillis());
        status.setProgress(0);
        executionStatusMap.put(executionId, status);
        
        PipelineExecutionResult result = new PipelineExecutionResult();
        result.setExecutionId(executionId);
        
        try {
            // 创建工作目录（按服务/环境/分支固定路径，便于清理空间步骤复用）
            String workspace = context.getWorkspace();
            if (workspace == null || workspace.isEmpty()) {
                workspace = PipelineWorkspaceResolver.resolve(context, context.getParameters());
            }
            context.setWorkspace(workspace);
            File workspaceDir = new File(WorkspacePathHelper.toLocalPath(workspace));
            if (!workspaceDir.exists()) {
                workspaceDir.mkdirs();
            }
            
            // 解析Pipeline步骤
            List<PipelineStepDTO> steps = pipeline.getSteps();
            // 如果DTO中没有steps，说明需要从外部传入，这里先跳过
            // 实际使用时，应该在调用execute之前将Pipeline模型转换为包含steps的DTO
            
            if (steps == null || steps.isEmpty()) {
                throw new RuntimeException("Pipeline步骤配置为空");
            }
            
            // 按顺序排序，跳过未启用步骤
            steps = steps.stream()
                .filter(step -> step.getEnabled() == null || Boolean.TRUE.equals(step.getEnabled()))
                .sorted(Comparator.comparing(PipelineStepDTO::getOrder))
                .collect(Collectors.toList());

            boolean rollbackRun = context.getParameters() != null
                && "true".equalsIgnoreCase(context.getParameters().get("rollback"));
            
            // 执行各个步骤
            Map<String, String> stepOutputs = new HashMap<>();
            int totalSteps = steps.size();
            int currentStepIndex = 0;
            String defaultWorkspaceBase = context.getParameters() != null
                ? context.getParameters().get("workspaceBase")
                : null;
            
            for (PipelineStepDTO step : steps) {
                currentStepIndex++;
                status.setCurrentStep(step.getStepName());
                status.setProgress((int) (currentStepIndex * 100.0 / totalSteps));

                int stepOrder = step.getOrder() != null ? step.getOrder() : currentStepIndex;
                PipelineStageListener stageListener = context.getStageListener();

                // 回滚：跳过纯 CI 步骤，保留原 Job 上已成功的构建阶段，不改写其状态
                if (rollbackRun && isCiOnlyStep(step.getStepType())) {
                    log.info("回滚任务跳过 CI 步骤 [{}]: {}", step.getStepName(), step.getStepType());
                    continue;
                }

                long stageStart = System.currentTimeMillis();
                context.setCurrentStepOrder(stepOrder);
                if (stageListener != null) {
                    stageListener.onStageStart(stepOrder, step.getStepType(), step.getStepName());
                }

                log.info("执行步骤 [{}]: {}", step.getStepName(), step.getStepType());
                
                // 查找步骤执行器
                StepExecutor executor = findExecutor(step.getStepType());
                if (executor == null) {
                    throw new RuntimeException("不支持步骤类型: " + step.getStepType());
                }
                
                // 合并步骤参数和上下文参数
                Map<String, String> stepParams = new HashMap<>();
                if (step.getParameters() != null) {
                    stepParams.putAll(step.getParameters());
                }
                // 添加上下文参数
                if (context.getParameters() != null) {
                    stepParams.putAll(context.getParameters());
                }
                // 添加步骤输出数据
                stepParams.putAll(stepOutputs);

                applyStepExecutionContext(step, context, stepParams, defaultWorkspaceBase);

                int timeoutSeconds = resolveStepTimeout(step);
                context.setStepTimeoutSeconds(timeoutSeconds);
                
                // 执行步骤（带超时）
                StepExecutionResult stepResult = executeStepWithTimeout(
                    executor, step, stepParams, context, timeoutSeconds);
                long stageDuration = System.currentTimeMillis() - stageStart;

                if (stageListener != null) {
                    stageListener.onStageComplete(
                        stepOrder,
                        Boolean.TRUE.equals(stepResult.getSuccess()),
                        stepResult.getLog(),
                        stepResult.getErrorMessage(),
                        stageDuration
                    );
                }
                context.setCurrentStepOrder(null);

                if (!stepResult.getSuccess()) {
                    // 步骤失败
                    result.setSuccess(false);
                    result.setErrorMessage(stepResult.getErrorMessage());
                    status.setStatus("FAILED");
                    status.setEndTime(System.currentTimeMillis());
                    return result;
                }
                
                // 保存步骤输出
                if (stepResult.getOutputData() != null) {
                    stepOutputs.putAll(stepResult.getOutputData());
                }
                
                log.info("步骤 [{}] 执行成功", step.getStepName());
            }
            
            // 所有步骤执行成功
            result.setSuccess(true);
            status.setStatus("SUCCESS");
            status.setProgress(100);
            
            // 从输出中提取镜像信息
            if (stepOutputs.containsKey("imageTag")) {
                result.setImageTag(stepOutputs.get("imageTag"));
            }
            if (stepOutputs.containsKey("imageFullName")) {
                result.setImageFullName(stepOutputs.get("imageFullName"));
            }
            
        } catch (Exception e) {
            log.error("Pipeline执行失败", e);
            result.setSuccess(false);
            result.setErrorMessage(e.getMessage());
            status.setStatus("FAILED");
        } finally {
            status.setEndTime(System.currentTimeMillis());
            // 执行结束即移除：终态已由调用方落库，这里只保留运行中的任务，否则每跑一次任务就多一条永不释放的记录
            executionStatusMap.remove(executionId);
        }
        
        return result;
    }

    /**
     * 按步骤配置调整当前步骤的执行上下文：节点覆盖、workspaceBase 与 workspace 路径。
     * <p>
     * 若步骤 {@code nodeSelection=specific}，将 {@code options.nodeId} 设为步骤指定节点；
     * workspaceBase 优先取 stepParams，其次指定节点 workDir，最后回退 Pipeline 级默认值；
     * 并据此重新解析 {@code context.workspace}。
     * </p>
     */
    private void applyStepExecutionContext(PipelineStepDTO step, PipelineExecutionContext context,
            Map<String, String> stepParams, String defaultWorkspaceBase) {
        if (context.getOptions() == null) {
            context.setOptions(new PipelineExecutionContext.ExecutionOptions());
        }

        Long overrideNodeId = null;
        if ("specific".equalsIgnoreCase(step.getNodeSelection()) && step.getNodeId() != null) {
            overrideNodeId = step.getNodeId();
        }
        context.getOptions().setNodeId(overrideNodeId);

        String workspaceBase = null;
        if (stepParams != null && stepParams.get("workspaceBase") != null && !stepParams.get("workspaceBase").trim().isEmpty()) {
            workspaceBase = stepParams.get("workspaceBase").trim();
        } else if (overrideNodeId != null) {
            BuildNode node = buildNodeMapper.selectById(overrideNodeId);
            if (node != null && node.getWorkDir() != null && !node.getWorkDir().trim().isEmpty()) {
                workspaceBase = node.getWorkDir().trim();
            }
        }
        if (workspaceBase == null || workspaceBase.isEmpty()) {
            workspaceBase = defaultWorkspaceBase;
        }

        if (context.getParameters() != null) {
            if (workspaceBase != null && !workspaceBase.isEmpty()) {
                context.getParameters().put("workspaceBase", workspaceBase);
            } else {
                context.getParameters().remove("workspaceBase");
            }
        }
        context.setWorkspace(PipelineWorkspaceResolver.resolve(context, stepParams));
    }

    private int resolveStepTimeout(PipelineStepDTO step) {
        if (step != null && step.getTimeoutSeconds() != null && step.getTimeoutSeconds() > 0) {
            return step.getTimeoutSeconds();
        }
        return DEFAULT_STEP_TIMEOUT_SECONDS;
    }

    /**
     * 在 {@code timeoutSeconds} 内等待步骤结果；等待期间每隔 {@code heartbeatIntervalMs} 通知一次阶段监听器，
     * 这样步骤即使长时间没有任何输出，阶段的更新时间也会持续刷新。
     */
    static StepExecutionResult awaitWithHeartbeat(Future<StepExecutionResult> future, int timeoutSeconds,
            PipelineExecutionContext context, long heartbeatIntervalMs)
            throws InterruptedException, ExecutionException, TimeoutException {
        long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
        while (true) {
            long remainingNanos = deadlineNanos - System.nanoTime();
            if (remainingNanos <= 0) {
                throw new TimeoutException();
            }
            long waitNanos = Math.min(remainingNanos, TimeUnit.MILLISECONDS.toNanos(heartbeatIntervalMs));
            try {
                return future.get(waitNanos, TimeUnit.NANOSECONDS);
            } catch (TimeoutException e) {
                if (deadlineNanos - System.nanoTime() <= 0) {
                    throw e;
                }
                emitHeartbeat(context);
            }
        }
    }

    private static void emitHeartbeat(PipelineExecutionContext context) {
        if (context == null || context.getStageListener() == null || context.getCurrentStepOrder() == null) {
            return;
        }
        try {
            context.getStageListener().onStageHeartbeat(context.getCurrentStepOrder());
        } catch (Exception e) {
            // 心跳只是辅助，写库失败不影响步骤执行
            log.warn("阶段心跳回调失败: {}", e.getMessage());
        }
    }

    /**
     * 在独立线程中执行单步，并在 {@code timeoutSeconds} 内等待结果；超时则 cancel 并返回失败。
     */
    private StepExecutionResult executeStepWithTimeout(StepExecutor executor, PipelineStepDTO step,
            Map<String, String> stepParams, PipelineExecutionContext context, int timeoutSeconds) {
        AtomicBoolean started = new AtomicBoolean();
        CountDownLatch finished = new CountDownLatch(1);
        Callable<StepExecutionResult> task = () -> {
            started.set(true);
            try {
                return executor.execute(step.getStepType(), stepParams, context);
            } finally {
                finished.countDown();
            }
        };
        Future<StepExecutionResult> future = stepExecutorPool.submit(
            context != null ? context.bindToStepLog(task) : task);
        try {
            return awaitWithHeartbeat(future, timeoutSeconds, context, STAGE_HEARTBEAT_INTERVAL_MS);
        } catch (TimeoutException e) {
            stopAbandonedStep(future, started, finished, context);
            StepExecutionResult timeoutResult = new StepExecutionResult();
            timeoutResult.setSuccess(false);
            String msg = String.format("步骤执行超时（%d秒）: %s", timeoutSeconds, step.getStepName());
            timeoutResult.setErrorMessage(msg);
            // 保留执行中已流式写入的日志，同时追加超时说明
            if (context != null) {
                context.emitLog("\n[超时] " + msg + "\n");
            }
            log.warn("步骤 [{}] 执行超时: {}s", step.getStepName(), timeoutSeconds);
            return timeoutResult;
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            StepExecutionResult errorResult = new StepExecutionResult();
            errorResult.setSuccess(false);
            String msg = "步骤执行异常: " + cause.getMessage();
            errorResult.setErrorMessage(msg);
            if (context != null) {
                context.emitLog("\n[异常] " + msg + "\n");
            }
            log.error("步骤 [{}] 执行异常", step.getStepName(), cause);
            return errorResult;
        } catch (InterruptedException e) {
            stopAbandonedStep(future, started, finished, context);
            Thread.currentThread().interrupt();
            StepExecutionResult errorResult = new StepExecutionResult();
            errorResult.setSuccess(false);
            errorResult.setErrorMessage("步骤执行被中断");
            if (context != null) {
                context.emitLog("\n[中断] 步骤执行被中断\n");
            }
            return errorResult;
        }
    }
    
    /**
     * 中断已超时（或不再等待）的步骤，并稍等它收尾：本机命令会随中断杀掉整棵进程树后返回，
     * 它最后写的「退出码」等日志排在超时说明之前。之后放弃这一代步骤日志，
     * 迟迟不响应中断的步骤再写的内容不会重新打开已关闭的日志文件，也不会混进下一阶段。
     */
    private static void stopAbandonedStep(Future<StepExecutionResult> future, AtomicBoolean started,
            CountDownLatch finished, PipelineExecutionContext context) {
        future.cancel(true);
        if (started.get()) {
            boolean wasInterrupted = Thread.interrupted();
            try {
                if (!finished.await(ABANDONED_STEP_GRACE_MS, TimeUnit.MILLISECONDS)) {
                    log.warn("步骤被中断后 {}ms 仍未结束，后续输出不再写入阶段日志", ABANDONED_STEP_GRACE_MS);
                }
            } catch (InterruptedException ie) {
                wasInterrupted = true;
            } finally {
                if (wasInterrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        }
        if (context != null) {
            context.abandonStepLog();
        }
    }

    @Override
    public void cancel(String executionId) {
        PipelineExecutionStatus status = executionStatusMap.get(executionId);
        if (status != null && "RUNNING".equals(status.getStatus())) {
            status.setStatus("CANCELLED");
            status.setEndTime(System.currentTimeMillis());
            log.info("Pipeline执行已取消: {}", executionId);
        }
    }
    
    @Override
    public PipelineExecutionStatus getStatus(String executionId) {
        return executionStatusMap.get(executionId);
    }
    
    /**
     * 回滚时跳过的纯 CI 步骤（不重新拉代码/构建/推镜像）
     */
    private boolean isCiOnlyStep(String stepType) {
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

    /**
     * 查找步骤执行器
     */
    private StepExecutor findExecutor(String stepType) {
        if (stepExecutors == null) {
            return null;
        }
        return stepExecutors.stream()
            .filter(executor -> executor.supports(stepType))
            .findFirst()
            .orElse(null);
    }
}

