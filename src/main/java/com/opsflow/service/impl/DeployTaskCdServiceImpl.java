package com.opsflow.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.opsflow.common.constant.BuildStatus;
import com.opsflow.common.constant.DeployModes;
import com.opsflow.common.constant.PipelineTypes;
import com.opsflow.common.exception.BusinessException;
import com.opsflow.dao.mapper.BuildJobMapper;
import com.opsflow.dao.mapper.DeployTaskMapper;
import com.opsflow.dao.mapper.PipelineMapper;
import com.opsflow.dao.model.BuildJob;
import com.opsflow.dao.model.DeployTask;
import com.opsflow.dao.model.Env;
import com.opsflow.dao.model.Pipeline;
import com.opsflow.integration.harbor.HarborCredentialService;
import com.opsflow.service.DeployTaskCdService;
import com.opsflow.service.PipelineRunService;
import com.opsflow.service.impl.deploy.CdJobFactory;
import com.opsflow.service.impl.deploy.CdJobRetryService;
import com.opsflow.service.impl.deploy.CdJobScheduler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 上线任务 CD 执行实现。
 * <p>
 * 审批通过后：解析上线模块 → 匹配环境（集群 + Namespace）→ 为每个模块创建 BuildJob →
 * 事务提交后异步调用 {@link com.opsflow.service.PipelineRunService} 执行 CD 流水线。
 * 构建结束时通过 {@link #onBuildJobFinished(Long)} 聚合更新上线任务状态。
 * </p>
 * <p>
 * 本类是门面，保留全部事务入口、任务行锁和编排顺序（调度 → 启动 → 结束回写 → 补槽 → 重试）；
 * 不带事务的规则与数据组装在 {@code com.opsflow.service.impl.deploy} 包：
 * {@link CdJobScheduler}（挑选可运行 Job）、{@link CdJobFactory}（建 Job）、{@link CdJobRetryService}（重试/跳过的状态改写）。
 * 这些 Bean 都不声明事务，始终运行在本类方法开启的事务里，事务边界与拆分前一致。
 * </p>
 */
@Slf4j
@Service
public class DeployTaskCdServiceImpl implements DeployTaskCdService {

    @Autowired
    private DeployTaskMapper deployTaskMapper;

    @Autowired
    private com.opsflow.integration.pipeline.PipelineJobDispatcher pipelineJobDispatcher;

    @Autowired
    private PipelineMapper pipelineMapper;

    @Autowired
    private BuildJobMapper buildJobMapper;

    @Autowired
    private HarborCredentialService harborCredentialService;

    @Autowired
    @Lazy
    private PipelineRunService pipelineRunService;

    @Autowired
    @Lazy
    private DeployTaskCdService self;

    @Autowired
    private CdJobScheduler cdJobScheduler;

    @Autowired
    private CdJobFactory cdJobFactory;

    @Autowired
    private CdJobRetryService cdJobRetryService;

    /**
     * 审批通过后启动 CD 部署：为每个上线模块创建 BuildJob，并在事务提交后异步执行流水线。
     * <p>
     * <b>业务目的</b>：将「审批通过」转化为可执行的 CD 流水线任务。一个上线任务可包含多个模块，
     * 每个模块对应一条独立的 BuildJob，由 {@link #onBuildJobFinished(Long)} 聚合回写任务终态。
     * </p>
     * <p>
     * <b>主流程</b>：
     * <ol>
     *   <li>重新从库加载任务，做幂等与前置校验（见下）</li>
     *   <li>校验 CD 流水线模版存在、启用且类型为 CD</li>
     *   <li>解析 deployModules，按集群 + Namespace 匹配目标环境</li>
     *   <li>读取 Harbor Registry，逐模块调用 {@link CdJobFactory#createCdJob} 创建 BuildJob</li>
     *   <li>将任务状态置为 {@code deploying} 并记录 deployTime</li>
     *   <li>注册 afterCommit 回调，逐条调用 {@code pipelineRunService.prepareAndExecuteAsync}</li>
     * </ol>
     * </p>
     * <p>
     * <b>幂等判断</b>（任一命中则直接返回，不重复建 Job）：
     * <ul>
     *   <li>任务为 null 或 id 为空</li>
     *   <li>审批状态非 {@code approved} 且非 {@code none}（none 表示创建时未关联审批流，视同免审通过）</li>
     *   <li>任务状态已是 {@code deploying} / {@code building} / {@code success}（防止审批回调、重试或并发重复触发）</li>
     * </ul>
     * </p>
     * <p>
     * <b>为何只接受 CD 类型流水线</b>：上线任务走的是「已有镜像直接部署」路径，不执行 CI 构建步骤。
     * CI 流水线会 checkout / build / push，与上线场景（镜像地址已确定）语义不符；若误绑 CI 模版，
     * 流水线参数（imageFullName、deployTaskId 等）也无法被正确消费，因此在此硬性拦截。
     * </p>
     * <p>
     * <b>为何 afterCommit 再执行</b>：BuildJob 的 insert 与任务状态更新在同一事务内。
     * 若在 commit 前异步启动流水线，执行器可能读不到刚插入的 Job 记录，导致启动失败或状态不一致。
     * afterCommit 保证落库可见后再调度，顺序不可颠倒。
     * </p>
     * <p>
     * <b>失败处理</b>：校验失败或 {@link CdJobFactory#createCdJob} 抛异常时，调用 {@link #markFailed} 将
     * taskStatus 置为 {@code failed}，并在 description 末尾追加 {@code [CD] 原因}，便于前端展示。
     * 异步启动单条 Job 失败时，会调用 {@link #onBuildJobFinished(Long)} 将该 Job 纳入聚合，
     * 避免任务长期停留在 deploying。
     * </p>
     * <p>
     * <b>上游</b>：{@link com.opsflow.service.impl.ApprovalServiceImpl} 在审批通过或免审时调用。
     * <b>下游</b>：{@link PipelineRunService#prepareAndExecuteAsync(Long)} 执行 CD 流水线；
     * 流水线结束时回调 {@link #onBuildJobFinished(Long)}。
     * </p>
     *
     * @param task 审批已通过（或免审）的上线任务；方法内会重新 selectById，传入对象仅用于取 id
     */
    @Override
    @Transactional
    public void scheduleApprovedTask(Long taskId) {
        if (taskId == null) {
            return;
        }
        DeployTask fresh = lockTask(taskId);
        if (fresh == null) {
            return;
        }
        if (!"approved".equalsIgnoreCase(fresh.getApprovalStatus())
                && !"none".equalsIgnoreCase(fresh.getApprovalStatus())) {
            log.info("上线任务未审批通过，跳过 CD 调度: taskId={}, approvalStatus={}",
                    fresh.getId(), fresh.getApprovalStatus());
            return;
        }
        if ("success".equalsIgnoreCase(fresh.getTaskStatus())) {
            log.info("上线任务已完成，跳过 CD 调度: taskId={}", fresh.getId());
            return;
        }

        if (fresh.getPipelineTemplateId() == null) {
            markFailed(fresh, "未关联 CD 流水线模版");
            return;
        }

        Pipeline pipeline = pipelineMapper.selectById(fresh.getPipelineTemplateId());
        if (pipeline == null || pipeline.getStatus() == null || pipeline.getStatus() != 1) {
            markFailed(fresh, "CD 流水线模版不存在或已禁用");
            return;
        }
        String pipelineType = PipelineTypes.normalize(pipeline.getPipelineType());
        if (!PipelineTypes.CD.equals(pipelineType)) {
            markFailed(fresh, "上线任务只能使用类型为 CD 的流水线模版，当前: "
                    + PipelineTypes.displayName(pipeline.getPipelineType()));
            return;
        }

        List<String> modules = cdJobFactory.parseModules(fresh.getDeployModules());
        if (modules.isEmpty()) {
            markFailed(fresh, "上线模块为空");
            return;
        }

        Env env = cdJobFactory.resolveEnv(fresh);
        if (env == null) {
            markFailed(fresh, "未找到匹配环境（请确认集群 + Namespace 已在环境管理中配置）");
            return;
        }

        String registry = harborCredentialService.getRegistryHost(null);
        if (!StringUtils.hasText(registry)) {
            markFailed(fresh, "Harbor 仓库地址未配置");
            return;
        }

        List<BuildJob> existingJobs = cdJobScheduler.listJobsForDeployTask(taskId);
        if (existingJobs.isEmpty()) {
            try {
                for (int i = 0; i < modules.size(); i++) {
                    cdJobFactory.createCdJob(fresh, pipeline, env, modules.get(i), registry.trim(), i + 1);
                }
                log.info("上线任务已创建 CD jobs: taskId={}, moduleCount={}", taskId, modules.size());
            } catch (BusinessException e) {
                markFailed(fresh, e.getMessage());
                return;
            } catch (Exception e) {
                log.error("创建 CD 任务失败, taskId={}", fresh.getId(), e);
                markFailed(fresh, "创建 CD 任务失败: " + e.getMessage());
                return;
            }
        }

        List<Long> toLaunch = cdJobScheduler.pickRunnableCdJobs(fresh, Integer.MAX_VALUE);
        boolean waiting = toLaunch.isEmpty() && cdJobScheduler.listJobsForDeployTask(taskId).stream()
                .anyMatch(j -> BuildStatus.PENDING.equals(j.getStatus()));
        if (toLaunch.isEmpty() && !waiting) {
            log.info("上线任务暂无待自动启动的 CD job: taskId={}", taskId);
            return;
        }

        if (!"deploying".equalsIgnoreCase(fresh.getTaskStatus())) {
            fresh.setTaskStatus("deploying");
            if (fresh.getDeployTime() == null) {
                fresh.setDeployTime(LocalDateTime.now());
            }
            fresh.setUpdateTime(LocalDateTime.now());
            deployTaskMapper.updateById(fresh);
        }

        if (toLaunch.isEmpty()) {
            log.info("上线任务模块的服务正被占用，排队等待: taskId={}", taskId);
            return;
        }
        log.info("上线任务调度 CD 执行: taskId={}, jobIds={}, mode={}, parallelism={}",
                taskId, toLaunch, DeployModes.normalize(fresh.getDeployMode()),
                DeployModes.resolveParallelism(fresh.getDeployMode(), fresh.getDeployParallelism()));
        launchCdJobs(taskId, toLaunch);
    }

    /**
     * 按部署策略启动可运行的 CD Job（补满并发槽）。
     */
    private void launchAvailableCdJobs(Long taskId) {
        if (taskId == null) {
            return;
        }
        DeployTask task = deployTaskMapper.selectById(taskId);
        if (task == null) {
            return;
        }
        List<Long> toLaunch = cdJobScheduler.pickRunnableCdJobs(task, Integer.MAX_VALUE);
        if (toLaunch.isEmpty()) {
            return;
        }
        if (!"deploying".equalsIgnoreCase(task.getTaskStatus())
                && !"success".equalsIgnoreCase(task.getTaskStatus())) {
            task.setTaskStatus("deploying");
            task.setUpdateTime(LocalDateTime.now());
            deployTaskMapper.updateById(task);
        }
        log.info("补槽启动 CD: taskId={}, jobIds={}", taskId, toLaunch);
        launchCdJobs(taskId, toLaunch);
    }

    @Override
    public void wakeWaitingCdJobs(Long serviceId, Long envId) {
        if (serviceId == null) {
            return;
        }
        QueryWrapper<BuildJob> wrapper = new QueryWrapper<BuildJob>()
                .select("DISTINCT deploy_task_id")
                .isNotNull("deploy_task_id")
                .eq("service_id", serviceId)
                .eq("status", BuildStatus.PENDING);
        if (envId != null) {
            wrapper.eq("env_id", envId);
        }
        resumeTasks(buildJobMapper.selectList(wrapper).stream()
                .map(BuildJob::getDeployTaskId).collect(Collectors.toCollection(LinkedHashSet::new)));
    }

    /**
     * 兜底巡检：重启或唤醒遗漏时，把仍有待执行模块的上线任务继续往下推进
     */
    @Scheduled(fixedDelayString = "${opsflow.deploy.cd-resume-interval-ms:60000}",
            initialDelayString = "${opsflow.deploy.cd-resume-initial-delay-ms:60000}")
    public void resumeWaitingTasks() {
        try {
            resumeTasks(buildJobMapper.selectList(new QueryWrapper<BuildJob>()
                            .select("DISTINCT deploy_task_id")
                            .isNotNull("deploy_task_id")
                            .eq("status", BuildStatus.PENDING))
                    .stream().map(BuildJob::getDeployTaskId).collect(Collectors.toCollection(LinkedHashSet::new)));
        } catch (Exception e) {
            log.error("CD 待执行模块巡检失败", e);
        }
    }

    private void resumeTasks(Set<Long> taskIds) {
        for (Long taskId : taskIds) {
            try {
                self.resumeTask(taskId);
            } catch (Exception e) {
                log.error("唤醒上线任务失败: taskId={}", taskId, e);
            }
        }
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void resumeTask(Long taskId) {
        DeployTask task = lockTask(taskId);
        if (task == null || "success".equalsIgnoreCase(task.getTaskStatus())) {
            return;
        }
        if (!"approved".equalsIgnoreCase(task.getApprovalStatus())
                && !"none".equalsIgnoreCase(task.getApprovalStatus())) {
            return;
        }
        launchAvailableCdJobs(taskId);
    }

    /**
     * 锁住上线任务行（当前读），同一上线任务的调度 / 推进 / 重试串行执行，避免同一模块被重复启动
     */
    private DeployTask lockTask(Long taskId) {
        return deployTaskMapper.selectOne(new QueryWrapper<DeployTask>().eq("id", taskId).last("FOR UPDATE"));
    }

    private void launchCdJobs(Long taskId, List<Long> jobIds) {
        for (Long jobId : jobIds) {
            try {
                pipelineRunService.launchJob(jobId);
            } catch (Exception e) {
                log.error("启动 CD 构建失败, taskId={}, jobId={}", taskId, jobId, e);
                onBuildJobFinished(jobId);
            }
        }
    }

    @Override
    @Transactional
    public void startCdAfterApproved(DeployTask task) {
        if (task == null || task.getId() == null) {
            return;
        }
        scheduleApprovedTask(task.getId());
    }

    /**
     * 单条 BuildJob 结束时，聚合该上线任务下全部关联 Job 的状态并回写任务终态。
     * <p>
     * <b>业务目的</b>：多模块上线时，每个模块一条 BuildJob，任务整体状态需由各 Job 推导，
     * 而非单条 Job 结束时直接覆盖（否则先完成的模块会把任务误标为 success/failed）。
     * </p>
     * <p>
     * <b>聚合规则</b>（遍历该 deployTaskId 下全部 BuildJob，优先级从高到低）：
     * <ul>
     *   <li><b>仍有运行中</b>：任一 Job 为 {@code pending} / {@code building} / {@code deploying}
     *       → 任务保持 {@code deploying}</li>
     *   <li><b>任一失败</b>：无运行中 Job，且存在 {@code failed} → 任务 {@code failed}</li>
     *   <li><b>全部成功</b>：无运行中、无失败，且全部为 {@code success} → 任务 {@code success}</li>
     *   <li><b>兜底</b>：其余组合（如存在未知状态）→ {@code failed}</li>
     * </ul>
     * 仅当推导结果与当前 taskStatus 不同时才 update，避免重复写库。
     * </p>
     * <p>
     * <b>边界情况</b>：buildJobId 为空、Job 不存在、Job 未关联 deployTaskId、任务已删除时静默返回；
     * 关联 Job 列表为空时亦直接返回（不修改任务）。
     * </p>
     * <p>
     * <b>上下游</b>：由流水线执行器在 Job 终态时回调；也可在 {@link #startCdAfterApproved}
     * 异步启动失败时被调用，用于及时将任务从 deploying 收敛到 failed。
     * </p>
     *
     * @param buildJobId 已结束（或启动失败需纳入统计）的构建任务 ID
     */
    @Override
    @Transactional
    public void onBuildJobFinished(Long buildJobId) {
        if (buildJobId == null) {
            return;
        }
        BuildJob job = buildJobMapper.selectById(buildJobId);
        if (job == null || job.getDeployTaskId() == null) {
            return;
        }
        DeployTask task = lockTask(job.getDeployTaskId());
        if (task == null) {
            return;
        }
        job = buildJobMapper.selectById(buildJobId);

        if (BuildStatus.FAILED.equals(job.getStatus())) {
            cdJobRetryService.cancelRemainingPendingCdJobs(task.getId(), job.getId());
        }

        List<BuildJob> jobs = cdJobScheduler.listJobsForDeployTask(task.getId());
        if (jobs.isEmpty()) {
            return;
        }

        boolean anyRunning = false;
        boolean anyFailed = false;
        boolean anyPending = false;
        boolean allSuccess = true;
        for (BuildJob item : jobs) {
            String status = item.getStatus();
            if (BuildStatus.BUILDING.equals(status) || BuildStatus.DEPLOYING.equals(status)) {
                anyRunning = true;
                allSuccess = false;
            } else if (BuildStatus.FAILED.equals(status)) {
                anyFailed = true;
                allSuccess = false;
            } else if (BuildStatus.PENDING.equals(status)) {
                anyPending = true;
                allSuccess = false;
            } else if (!BuildStatus.SUCCESS.equals(status)) {
                allSuccess = false;
            }
        }

        // 仍有 PENDING 且尚未 fail-fast 时保持 deploying，避免串行推进前被误标 failed
        String nextStatus;
        if (anyRunning || (anyPending && !anyFailed)) {
            nextStatus = "deploying";
        } else if (anyFailed) {
            nextStatus = "failed";
        } else if (allSuccess) {
            nextStatus = "success";
        } else {
            nextStatus = "failed";
        }

        if (!nextStatus.equalsIgnoreCase(task.getTaskStatus())) {
            task.setTaskStatus(nextStatus);
            task.setUpdateTime(LocalDateTime.now());
            deployTaskMapper.updateById(task);
            log.info("上线任务状态更新: taskId={}, status={}", task.getId(), nextStatus);
        }

        if (BuildStatus.SUCCESS.equals(job.getStatus())) {
            launchAvailableCdJobs(task.getId());
        }
    }

    @Override
    public boolean isRetryableCdJob(Long buildJobId) {
        BuildJob job = buildJobMapper.selectById(buildJobId);
        if (job == null || job.getDeployTaskId() == null) {
            return false;
        }
        if (BuildStatus.BUILDING.equals(job.getStatus()) || BuildStatus.DEPLOYING.equals(job.getStatus())) {
            return false;
        }
        if (!BuildStatus.FAILED.equals(job.getStatus())) {
            return false;
        }
        if (cdJobScheduler.isSkippedByFailFast(job)) {
            return false;
        }
        List<BuildJob> jobs = cdJobScheduler.listJobsForDeployTask(job.getDeployTaskId());
        if (cdJobScheduler.hasRunningCdJob(jobs)) {
            return false;
        }
        DeployTask task = deployTaskMapper.selectById(job.getDeployTaskId());
        if (task != null && DeployModes.isParallel(task.getDeployMode())) {
            return true;
        }
        return cdJobScheduler.isFirstNonSuccessJob(job, jobs);
    }

    @Override
    public String getCdJobBlockedReason(Long buildJobId) {
        BuildJob job = buildJobMapper.selectById(buildJobId);
        if (job == null || job.getDeployTaskId() == null) {
            return null;
        }
        if (BuildStatus.BUILDING.equals(job.getStatus()) || BuildStatus.DEPLOYING.equals(job.getStatus())) {
            return null;
        }
        if (BuildStatus.SUCCESS.equals(job.getStatus())) {
            return null;
        }
        if (cdJobScheduler.isSkippedByFailFast(job)) {
            return "因其他模块失败已跳过，请重试失败模块";
        }
        List<BuildJob> jobs = cdJobScheduler.listJobsForDeployTask(job.getDeployTaskId());
        if (cdJobScheduler.hasRunningCdJob(jobs) && BuildStatus.FAILED.equals(job.getStatus())) {
            return "请等待其他模块部署完成后再重试";
        }
        DeployTask task = deployTaskMapper.selectById(job.getDeployTaskId());
        boolean parallel = task != null && DeployModes.isParallel(task.getDeployMode());
        if (!parallel && !cdJobScheduler.isFirstNonSuccessJob(job, jobs)) {
            return "需先完成或重试前序模块";
        }
        if (BuildStatus.FAILED.equals(job.getStatus())) {
            return null;
        }
        if (BuildStatus.PENDING.equals(job.getStatus())) {
            BuildJob occupier = buildJobMapper.selectOne(new QueryWrapper<BuildJob>()
                    .eq("service_id", job.getServiceId()).eq("env_id", job.getEnvId())
                    .ne("id", job.getId())
                    .in("status", BuildStatus.BUILDING, BuildStatus.DEPLOYING)
                    .last("LIMIT 1"));
            return occupier != null
                    ? "等待 " + occupier.getJobNumber() + " 部署完成后自动开始（同一服务同时只能部署一个）"
                    : "等待调度";
        }
        return null;
    }

    /**
     * 将上线任务标记为 failed，并在 description 中追加 CD 失败原因。
     *
     * @param task 上线任务
     * @param message 失败原因
     */
    private void markFailed(DeployTask task, String message) {
        log.error("上线任务 CD 启动失败: taskId={}, reason={}", task.getId(), message);
        task.setTaskStatus("failed");
        task.setDescription(appendFailReason(task.getDescription(), message));
        task.setUpdateTime(LocalDateTime.now());
        deployTaskMapper.updateById(task);
    }

    /**
     * 在任务描述末尾追加 CD 失败原因，避免重复追加相同内容。
     *
     * @param description 原有描述
     * @param message 失败原因
     * @return 合并后的描述
     */
    private String appendFailReason(String description, String message) {
        String prefix = "[CD] " + message;
        if (!StringUtils.hasText(description)) {
            return prefix;
        }
        if (description.contains(prefix)) {
            return description;
        }
        return description + "\n" + prefix;
    }

    @Override
    @Transactional
    public com.opsflow.api.dto.BuildResponse retryCdJob(Long buildJobId) {
        BuildJob job = buildJobMapper.selectById(buildJobId);
        if (job == null) {
            throw new BusinessException("构建任务不存在");
        }
        if (job.getDeployTaskId() == null) {
            throw new BusinessException("非上线任务 CD 构建，无法重试");
        }
        // 多人同时重试时排队，后到的请求在锁内会读到已被前一个请求置为运行中的状态
        lockTask(job.getDeployTaskId());
        job = buildJobMapper.selectById(buildJobId);
        if (pipelineJobDispatcher.isActive(buildJobId)) {
            throw new BusinessException("任务正在运行中，请稍后再试");
        }
        if (BuildStatus.BUILDING.equals(job.getStatus()) || BuildStatus.DEPLOYING.equals(job.getStatus())) {
            throw new BusinessException("任务正在运行中，请稍后再试");
        }
        if (!isRetryableCdJob(buildJobId)) {
            String reason = getCdJobBlockedReason(buildJobId);
            throw new BusinessException(reason != null ? reason : "当前模块不可重试");
        }

        cdJobRetryService.resetJobStagesForRetry(buildJobId);
        job.setStatus(BuildStatus.PENDING);
        job.setErrorMessage(null);
        job.setEndTime(null);
        job.setUpdateTime(LocalDateTime.now());
        buildJobMapper.updateById(job);

        // 将因 fail-fast 跳过的后续模块恢复为 PENDING，便于重试后继续调度
        cdJobRetryService.reactivateSkippedCdJobs(job.getDeployTaskId());

        DeployTask task = deployTaskMapper.selectById(job.getDeployTaskId());
        if (task != null && "failed".equalsIgnoreCase(task.getTaskStatus())) {
            task.setTaskStatus("deploying");
            task.setUpdateTime(LocalDateTime.now());
            deployTaskMapper.updateById(task);
        }

        log.info("重试 CD job: jobId={}, deployTaskId={}", buildJobId, job.getDeployTaskId());
        List<Long> toLaunch = task != null
                ? cdJobScheduler.pickRunnableCdJobs(task, Integer.MAX_VALUE)
                : new ArrayList<Long>();
        String occupiedBy = null;
        if (!toLaunch.contains(buildJobId)) {
            occupiedBy = pipelineRunService.findRunningJobNumber(job.getServiceId(), job.getEnvId(), buildJobId);
            if (occupiedBy == null) {
                toLaunch = new ArrayList<>(toLaunch);
                toLaunch.add(0, buildJobId);
            }
        }
        launchCdJobs(job.getDeployTaskId(), toLaunch);

        com.opsflow.api.dto.BuildResponse response = new com.opsflow.api.dto.BuildResponse();
        response.setJobId(job.getId());
        response.setJobNumber(job.getJobNumber());
        if (occupiedBy != null) {
            response.setStatus(BuildStatus.PENDING);
            response.setErrorMessage("该服务正在部署（" + occupiedBy + "），已排队，完成后自动开始");
        } else {
            response.setStatus(BuildStatus.BUILDING);
        }
        return response;
    }

}
