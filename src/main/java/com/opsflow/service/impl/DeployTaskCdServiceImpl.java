package com.opsflow.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opsflow.common.constant.BuildStatus;
import com.opsflow.common.constant.DeployModes;
import com.opsflow.common.constant.PipelineTypes;
import com.opsflow.common.exception.BusinessException;
import com.opsflow.common.util.BuildJobCreatorUtils;
import com.opsflow.common.util.JobNumberGenerator;
import com.opsflow.dao.mapper.BuildJobMapper;
import com.opsflow.dao.mapper.BuildJobStageMapper;
import com.opsflow.dao.mapper.DeployTaskMapper;
import com.opsflow.dao.mapper.EnvMapper;
import com.opsflow.dao.mapper.PipelineMapper;
import com.opsflow.dao.mapper.ServiceMapper;
import com.opsflow.dao.model.BuildJob;
import com.opsflow.dao.model.BuildJobStage;
import com.opsflow.dao.model.DeployTask;
import com.opsflow.dao.model.Env;
import com.opsflow.dao.model.Pipeline;
import com.opsflow.integration.harbor.HarborCredentialService;
import com.opsflow.service.DeployTaskCdService;
import com.opsflow.service.PipelineRunService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 上线任务 CD 执行实现。
 * <p>
 * 审批通过后：解析上线模块 → 匹配环境（集群 + Namespace）→ 为每个模块创建 BuildJob →
 * 事务提交后异步调用 {@link com.opsflow.service.PipelineRunService} 执行 CD 流水线。
 * 构建结束时通过 {@link #onBuildJobFinished(Long)} 聚合更新上线任务状态。
 * </p>
 */
@Slf4j
@Service
public class DeployTaskCdServiceImpl implements DeployTaskCdService {

    private static final String SKIPPED_BY_FAIL_FAST = "前置模块部署失败，已跳过";

    @Autowired
    private DeployTaskMapper deployTaskMapper;

    @Autowired
    private PipelineMapper pipelineMapper;

    @Autowired
    private ServiceMapper serviceMapper;

    @Autowired
    private EnvMapper envMapper;

    @Autowired
    private BuildJobMapper buildJobMapper;

    @Autowired
    private BuildJobStageMapper buildJobStageMapper;

    @Autowired
    private HarborCredentialService harborCredentialService;

    @Autowired
    @Lazy
    private PipelineRunService pipelineRunService;

    private final ObjectMapper objectMapper = new ObjectMapper();

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
     *   <li>读取 Harbor Registry，逐模块调用 {@link #createCdJob} 创建 BuildJob</li>
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
     * <b>失败处理</b>：校验失败或 {@link #createCdJob} 抛异常时，调用 {@link #markFailed} 将
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
        DeployTask fresh = deployTaskMapper.selectById(taskId);
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

        List<String> modules = parseModules(fresh.getDeployModules());
        if (modules.isEmpty()) {
            markFailed(fresh, "上线模块为空");
            return;
        }

        Env env = resolveEnv(fresh);
        if (env == null) {
            markFailed(fresh, "未找到匹配环境（请确认集群 + Namespace 已在环境管理中配置）");
            return;
        }

        String registry = harborCredentialService.getRegistryHost(null);
        if (!StringUtils.hasText(registry)) {
            markFailed(fresh, "Harbor 仓库地址未配置");
            return;
        }

        List<BuildJob> existingJobs = listJobsForDeployTask(taskId);
        if (existingJobs.isEmpty()) {
            try {
                for (int i = 0; i < modules.size(); i++) {
                    createCdJob(fresh, pipeline, env, modules.get(i), registry.trim(), i + 1);
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

        List<Long> toLaunch = pickRunnableCdJobs(fresh, Integer.MAX_VALUE);
        if (toLaunch.isEmpty()) {
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
        List<Long> toLaunch = pickRunnableCdJobs(task, Integer.MAX_VALUE);
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
        DeployTask task = deployTaskMapper.selectById(job.getDeployTaskId());
        if (task == null) {
            return;
        }

        if (BuildStatus.FAILED.equals(job.getStatus())) {
            cancelRemainingPendingCdJobs(task.getId(), job.getId());
        }

        List<BuildJob> jobs = listJobsForDeployTask(task.getId());
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
        if (isSkippedByFailFast(job)) {
            return false;
        }
        List<BuildJob> jobs = listJobsForDeployTask(job.getDeployTaskId());
        if (hasRunningCdJob(jobs)) {
            return false;
        }
        DeployTask task = deployTaskMapper.selectById(job.getDeployTaskId());
        if (task != null && DeployModes.isParallel(task.getDeployMode())) {
            return true;
        }
        return isFirstNonSuccessJob(job, jobs);
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
        if (isSkippedByFailFast(job)) {
            return "因其他模块失败已跳过，请重试失败模块";
        }
        List<BuildJob> jobs = listJobsForDeployTask(job.getDeployTaskId());
        if (hasRunningCdJob(jobs) && BuildStatus.FAILED.equals(job.getStatus())) {
            return "请等待其他模块部署完成后再重试";
        }
        DeployTask task = deployTaskMapper.selectById(job.getDeployTaskId());
        boolean parallel = task != null && DeployModes.isParallel(task.getDeployMode());
        if (!parallel && !isFirstNonSuccessJob(job, jobs)) {
            return "需先完成或重试前序模块";
        }
        if (BuildStatus.FAILED.equals(job.getStatus())) {
            return null;
        }
        if (BuildStatus.PENDING.equals(job.getStatus())) {
            return "等待调度";
        }
        return null;
    }

    private List<BuildJob> listJobsForDeployTask(Long deployTaskId) {
        return buildJobMapper.selectList(
                new QueryWrapper<BuildJob>().eq("deploy_task_id", deployTaskId).orderByAsc("id"));
    }

    /**
     * 按策略挑选可启动的 PENDING Job，数量不超过空闲并发槽。
     * <ul>
     *   <li>serial：无运行中、无失败、前序全 SUCCESS 时取下一条 PENDING</li>
     *   <li>parallel：无真实失败时按 id 取 PENDING，填满并发槽</li>
     * </ul>
     */
    private List<Long> pickRunnableCdJobs(DeployTask task, int maxPick) {
        List<Long> result = new ArrayList<>();
        if (task == null || task.getId() == null || maxPick <= 0) {
            return result;
        }
        List<BuildJob> jobs = listJobsForDeployTask(task.getId());
        int limit = DeployModes.resolveParallelism(task.getDeployMode(), task.getDeployParallelism());
        int running = 0;
        for (BuildJob item : jobs) {
            if (BuildStatus.BUILDING.equals(item.getStatus()) || BuildStatus.DEPLOYING.equals(item.getStatus())) {
                running++;
            }
            if (BuildStatus.FAILED.equals(item.getStatus()) && !isSkippedByFailFast(item)) {
                // 存在真实失败时不再自动启动（需人工重试）
                return result;
            }
        }
        int free = Math.min(limit - running, maxPick);
        if (free <= 0) {
            return result;
        }

        if (DeployModes.isParallel(task.getDeployMode())) {
            for (BuildJob item : jobs) {
                if (result.size() >= free) {
                    break;
                }
                if (BuildStatus.PENDING.equals(item.getStatus())) {
                    result.add(item.getId());
                }
            }
            return result;
        }

        // serial：必须无运行中，且按顺序推进
        if (running > 0) {
            return result;
        }
        for (BuildJob item : jobs) {
            String status = item.getStatus();
            if (BuildStatus.SUCCESS.equals(status)) {
                continue;
            }
            if (BuildStatus.FAILED.equals(status)) {
                return result;
            }
            if (BuildStatus.PENDING.equals(status)) {
                result.add(item.getId());
            }
            return result;
        }
        return result;
    }

    private boolean hasRunningCdJob(List<BuildJob> jobs) {
        if (jobs == null) {
            return false;
        }
        for (BuildJob item : jobs) {
            if (BuildStatus.BUILDING.equals(item.getStatus()) || BuildStatus.DEPLOYING.equals(item.getStatus())) {
                return true;
            }
        }
        return false;
    }

    private boolean isSkippedByFailFast(BuildJob job) {
        return job != null
                && BuildStatus.FAILED.equals(job.getStatus())
                && StringUtils.hasText(job.getErrorMessage())
                && job.getErrorMessage().contains(SKIPPED_BY_FAIL_FAST);
    }

    private boolean isFirstNonSuccessJob(BuildJob target, List<BuildJob> jobs) {
        if (target == null || jobs == null) {
            return false;
        }
        for (BuildJob item : jobs) {
            if (BuildStatus.SUCCESS.equals(item.getStatus())) {
                continue;
            }
            return target.getId().equals(item.getId());
        }
        return false;
    }

    /**
     * 为单个上线模块创建一条 CD BuildJob，并序列化流水线所需参数后落库。
     * <p>
     * <b>业务目的</b>：将「镜像 + 服务 + 环境 + 集群/Namespace」打包为流水线可消费的 BuildJob。
     * CD 场景下 branch/gitType 复用为镜像 tag（{@code gitType=tag}），不触发代码拉取。
     * </p>
     * <p>
     * <b>主流程</b>：
     * <ol>
     *   <li>{@link #parseModule} 解析镜像，提取 project、serviceCode、version</li>
     *   <li>{@link #findServiceByCode} 按 serviceCode 查服务（code 优先，name 兜底）</li>
     *   <li>{@link #resolveImageFullName} 补全为含 registry 的完整镜像地址</li>
     *   <li>组装 BuildJob 实体与 buildParameters（image、port、clusterId、k8sNamespace 等）</li>
     *   <li>insert 后返回（调用方负责事务提交与异步执行）</li>
     * </ol>
     * </p>
     * <p>
     * <b>服务查找</b>：镜像路径最后一段（tag 前）作为服务标识，须能在服务管理中匹配到 code 或 name；
     * 找不到则抛 {@link BusinessException}，由 {@link #startCdAfterApproved} 捕获后 markFailed。
     * </p>
     * <p>
     * <b>端口默认值</b>：服务未配置 servicePort 或 ≤0 时，参数与实体均回退 {@code 8080}，
     * 与前端展示逻辑保持一致，避免 CD 模版渲染时缺少 port。
     * </p>
     *
     * @param task 上线任务（提供 taskName、clusterId、k8sNamespace、deployTaskId）
     * @param pipeline 已校验为 CD 类型的流水线模版
     * @param env 由 {@link #resolveEnv} 匹配到的目标环境
     * @param module 单个上线模块镜像地址（完整或相对路径均可）
     * @param registry Harbor Registry 主机名，用于补全相对路径镜像
     * @return 已 insert 的 BuildJob（status=pending）
     * @throws BusinessException 服务不存在或参数序列化失败
     */
    private BuildJob createCdJob(DeployTask task, Pipeline pipeline, Env env, String module, String registry,
                                 int deploySequence) {
        ParsedModule parsed = parseModule(module);
        com.opsflow.dao.model.Service service = findServiceByCode(parsed.serviceCode);
        if (service == null) {
            throw new BusinessException("找不到服务: " + parsed.serviceCode + "（模块: " + module + "）");
        }

        String imageFullName = resolveImageFullName(module, registry);

        BuildJob buildJob = new BuildJob();
        buildJob.setJobNumber(JobNumberGenerator.generateBuildJobNumber());
        buildJob.setTaskName(task.getTaskName() + " / " + parsed.serviceCode);
        buildJob.setServiceId(service.getId());
        buildJob.setEnvId(env.getId());
        buildJob.setBranch(parsed.version);
        buildJob.setGitType("tag");
        buildJob.setPipelineTemplateId(pipeline.getId());
        buildJob.setDeployTaskId(task.getId());
        buildJob.setImageTag(parsed.version);
        buildJob.setImageFullName(imageFullName);
        buildJob.setStatus(BuildStatus.PENDING);
        buildJob.setCreateTime(LocalDateTime.now());
        buildJob.setUpdateTime(LocalDateTime.now());

        Map<String, Object> params = new HashMap<>();
        params.put("imageFullName", imageFullName);
        params.put("image", imageFullName);
        params.put("imageTag", parsed.version);
        params.put("harborProject", parsed.project);
        params.put("deployTaskId", task.getId());
        params.put("deploySequence", deploySequence);
        params.put("clusterId", task.getClusterId());
        params.put("k8sNamespace", task.getK8sNamespace());
        String servicePort = service.getServicePort() != null && service.getServicePort() > 0
                ? String.valueOf(service.getServicePort()) : "8080";
        params.put("servicePort", servicePort);
        params.put("port", servicePort);
        params.put("serviceName", service.getCode());
        params.put("serviceCode", service.getCode());
        try {
            buildJob.setBuildParameters(objectMapper.writeValueAsString(params));
        } catch (Exception e) {
            throw new BusinessException("序列化 CD 参数失败");
        }
        BuildJobCreatorUtils.stampCreator(objectMapper, buildJob, task.getCreatorId(), task.getCreatorName());

        buildJobMapper.insert(buildJob);
        return buildJob;
    }

    /**
     * 为 CD 部署解析目标环境（Env），决定 BuildJob.envId 与流水线部署目标。
     * <p>
     * <b>匹配策略（按优先级）</b>：
     * <ol>
     *   <li><b>精确匹配</b>：clusterId + k8sNamespace 与任务一致的环境记录，
     *       按 id 升序取第一条。这是首选路径，与创建任务时「选集群 + Namespace」的语义对齐。</li>
     *   <li><b>回退</b>：精确匹配为空时，遍历 deployEnvs JSON 中的环境 ID，返回首个存在的 Env。
     *       兼容历史数据或创建时自动写入的 deployEnvIds，避免仅因环境表未按 Namespace 建记录而阻断 CD。</li>
     * </ol>
     * </p>
     * <p>
     * <b>前置条件</b>：clusterId 非空且 k8sNamespace 有文本；否则直接返回 null（不尝试回退），
     * 因为 Namespace 是 CD 部署的硬性约束，缺失说明任务数据不完整。
     * </p>
     * <p>
     * <b>边界</b>：两条路径均无结果时返回 null，由调用方 markFailed 并提示检查环境管理配置。
     * </p>
     *
     * @param task 含 clusterId、k8sNamespace、deployEnvs 的上线任务
     * @return 匹配到的环境；未找到时返回 {@code null}
     */
    private Env resolveEnv(DeployTask task) {
        if (task.getClusterId() == null || !StringUtils.hasText(task.getK8sNamespace())) {
            return null;
        }
        List<Env> matched = envMapper.selectList(new QueryWrapper<Env>()
                .eq("cluster_id", task.getClusterId())
                .eq("k8s_namespace", task.getK8sNamespace().trim())
                .orderByAsc("id"));
        if (!matched.isEmpty()) {
            return matched.get(0);
        }
        // 回退：仅按部署环境 ID
        List<Long> envIds = parseEnvIds(task.getDeployEnvs());
        for (Long envId : envIds) {
            Env env = envMapper.selectById(envId);
            if (env != null) {
                return env;
            }
        }
        return null;
    }

    /**
     * 按服务 code 或 name 查找服务实体。
     *
     * @param code 服务标识（镜像路径最后一段）
     * @return 服务实体；未找到时返回 {@code null}
     */
    private com.opsflow.dao.model.Service findServiceByCode(String code) {
        if (!StringUtils.hasText(code)) {
            return null;
        }
        com.opsflow.dao.model.Service byCode = serviceMapper.selectOne(
                new QueryWrapper<com.opsflow.dao.model.Service>().eq("code", code.trim()).last("LIMIT 1"));
        if (byCode != null) {
            return byCode;
        }
        return serviceMapper.selectOne(
                new QueryWrapper<com.opsflow.dao.model.Service>().eq("name", code.trim()).last("LIMIT 1"));
    }

    /**
     * 解析 deployModules JSON 字段为镜像地址列表。
     *
     * @param json JSON 数组字符串
     * @return 模块列表；解析失败时返回空列表
     */
    private List<String> parseModules(String json) {
        if (!StringUtils.hasText(json)) {
            return new ArrayList<>();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            log.warn("解析上线模块失败: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    /**
     * 解析 deployEnvs JSON 字段为环境 ID 列表。
     *
     * @param json JSON 数组字符串
     * @return 环境 ID 列表；解析失败时返回空列表
     */
    private List<Long> parseEnvIds(String json) {
        if (!StringUtils.hasText(json)) {
            return new ArrayList<>();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<Long>>() {});
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    /**
     * 解析上线模块镜像字符串，拆出 Harbor 项目、服务标识与版本号。
     * <p>
     * <b>支持的格式</b>：
     * <ul>
     *   <li>{@code project/service:version} — 相对路径，registry 由 {@link #resolveImageFullName} 补全</li>
     *   <li>{@code registry/project/service:version} — 完整路径，registry 可含多级（如 {@code harbor.example.com/a/b}）</li>
     * </ul>
     * 以最后一个 {@code :} 分隔 tag；路径以 {@code /} 分段，倒数第二段为 project，最后一段为 serviceCode。
     * </p>
     * <p>
     * <b>业务约定</b>：serviceCode 须与服务管理中的 code（或 name）对应，供 {@link #createCdJob}
     * 查找服务并注入端口；project 写入 buildParameters.harborProject 供镜像拉取步骤使用。
     * </p>
     * <p>
     * <b>边界</b>：缺少 {@code :}、分段不足 2 段、project/serviceCode/version 任一为空时抛
     * {@link BusinessException}，错误信息含原始 module 便于排查。
     * </p>
     *
     * @param module 上线模块镜像地址
     * @return 解析结果（含可选的 registry 与 imageFullName）
     * @throws BusinessException 格式不完整或无效时
     */
    private ParsedModule parseModule(String module) {
        // 支持:
        //   project/service:version
        //   registry/project/service:version
        String text = module == null ? "" : module.trim();
        int colon = text.lastIndexOf(':');
        if (colon <= 0 || colon >= text.length() - 1) {
            throw new BusinessException("上线模块格式无效: " + module
                    + "，期望 registry/project/service:version 或 project/service:version");
        }
        String left = text.substring(0, colon);
        String version = text.substring(colon + 1).trim();
        String[] parts = left.split("/");
        if (parts.length < 2) {
            throw new BusinessException("上线模块格式无效: " + module
                    + "，期望 registry/project/service:version 或 project/service:version");
        }
        ParsedModule parsed = new ParsedModule();
        parsed.version = version;
        parsed.serviceCode = parts[parts.length - 1].trim();
        parsed.project = parts[parts.length - 2].trim();
        if (parts.length >= 3) {
            StringBuilder registry = new StringBuilder(parts[0].trim());
            for (int i = 1; i < parts.length - 2; i++) {
                registry.append('/').append(parts[i].trim());
            }
            parsed.registry = registry.toString();
            parsed.imageFullName = text;
        }
        if (!StringUtils.hasText(parsed.project) || !StringUtils.hasText(parsed.serviceCode)
                || !StringUtils.hasText(parsed.version)) {
            throw new BusinessException("上线模块不完整: " + module);
        }
        return parsed;
    }

    /**
     * 将上线模块规范为流水线可用的完整镜像地址 {@code registry/project/service:tag}。
     * <p>
     * <b>逻辑</b>：先 {@link #parseModule}；若解析结果已含 registry（输入为完整路径），
     * 直接返回原始文本；否则用 defaultRegistry 拼接：
     * {@code registry + "/" + project + "/" + serviceCode + ":" + version}。
     * </p>
     * <p>
     * <b>为何需要补全</b>：前端创建任务时可能只传 {@code project/service:tag}（相对路径），
     * CD 流水线的 deploy/pull 步骤需要可直连 Harbor 的 FQDN 形态；Registry 来自系统 Harbor 配置，
     * 与 {@link com.opsflow.web.controller.DeployTaskController#normalizeDeployModules} 的规范化规则一致。
     * </p>
     * <p>
     * <b>边界</b>：相对路径且 defaultRegistry 未配置时抛 {@link BusinessException}。
     * </p>
     *
     * @param module 上线模块（完整或相对路径）
     * @param defaultRegistry 系统配置的 Harbor Registry 主机名
     * @return 完整镜像地址，可直接用于 docker pull / K8s image 字段
     * @throws BusinessException 相对路径但 Registry 未配置时
     */
    private String resolveImageFullName(String module, String defaultRegistry) {
        ParsedModule parsed = parseModule(module);
        if (StringUtils.hasText(parsed.imageFullName)) {
            return parsed.imageFullName;
        }
        if (!StringUtils.hasText(defaultRegistry)) {
            throw new BusinessException("Harbor 仓库地址未配置，无法拼接完整镜像地址");
        }
        return defaultRegistry.trim() + "/" + parsed.project + "/" + parsed.serviceCode + ":" + parsed.version;
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
        if (BuildStatus.BUILDING.equals(job.getStatus()) || BuildStatus.DEPLOYING.equals(job.getStatus())) {
            throw new BusinessException("任务正在运行中，请稍后再试");
        }
        if (!isRetryableCdJob(buildJobId)) {
            String reason = getCdJobBlockedReason(buildJobId);
            throw new BusinessException(reason != null ? reason : "当前模块不可重试");
        }

        resetJobStagesForRetry(buildJobId);
        job.setStatus(BuildStatus.PENDING);
        job.setErrorMessage(null);
        job.setEndTime(null);
        job.setUpdateTime(LocalDateTime.now());
        buildJobMapper.updateById(job);

        // 将因 fail-fast 跳过的后续模块恢复为 PENDING，便于重试后继续调度
        reactivateSkippedCdJobs(job.getDeployTaskId());

        DeployTask task = deployTaskMapper.selectById(job.getDeployTaskId());
        if (task != null && "failed".equalsIgnoreCase(task.getTaskStatus())) {
            task.setTaskStatus("deploying");
            task.setUpdateTime(LocalDateTime.now());
            deployTaskMapper.updateById(task);
        }

        log.info("重试 CD job: jobId={}, deployTaskId={}", buildJobId, job.getDeployTaskId());
        List<Long> toLaunch = task != null
                ? pickRunnableCdJobs(task, Integer.MAX_VALUE)
                : new ArrayList<Long>();
        if (!toLaunch.contains(buildJobId)) {
            toLaunch = new ArrayList<>(toLaunch);
            toLaunch.add(0, buildJobId);
        }
        launchCdJobs(job.getDeployTaskId(), toLaunch);

        com.opsflow.api.dto.BuildResponse response = new com.opsflow.api.dto.BuildResponse();
        response.setJobId(job.getId());
        response.setJobNumber(job.getJobNumber());
        response.setStatus(BuildStatus.BUILDING);
        return response;
    }

    private void resetJobStagesForRetry(Long buildJobId) {
        List<BuildJobStage> stages = buildJobStageMapper.selectList(
                new QueryWrapper<BuildJobStage>().eq("build_job_id", buildJobId).orderByAsc("step_order"));
        LocalDateTime now = LocalDateTime.now();
        for (BuildJobStage stage : stages) {
            stage.setStatus("PENDING");
            stage.setErrorMessage(null);
            stage.setLogText(null);
            stage.setStartTime(null);
            stage.setEndTime(null);
            stage.setDurationMs(null);
            stage.setUpdateTime(now);
            buildJobStageMapper.updateById(stage);
        }
    }

    private void reactivateSkippedCdJobs(Long deployTaskId) {
        if (deployTaskId == null) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        for (BuildJob item : listJobsForDeployTask(deployTaskId)) {
            if (!isSkippedByFailFast(item)) {
                continue;
            }
            item.setStatus(BuildStatus.PENDING);
            item.setErrorMessage(null);
            item.setEndTime(null);
            item.setUpdateTime(now);
            buildJobMapper.updateById(item);
            resetJobStagesForRetry(item.getId());
        }
    }

    /**
     * 当前模块失败后，取消其余尚未执行的模块（fail-fast），避免任务长期停留在 deploying。
     */
    private void cancelRemainingPendingCdJobs(Long deployTaskId, Long failedJobId) {
        if (deployTaskId == null || failedJobId == null) {
            return;
        }
        List<BuildJob> jobs = listJobsForDeployTask(deployTaskId);
        LocalDateTime now = LocalDateTime.now();
        for (BuildJob item : jobs) {
            if (item.getId().equals(failedJobId)) {
                continue;
            }
            if (BuildStatus.PENDING.equals(item.getStatus())) {
                item.setStatus(BuildStatus.FAILED);
                item.setErrorMessage(SKIPPED_BY_FAIL_FAST);
                item.setEndTime(now);
                item.setUpdateTime(now);
                buildJobMapper.updateById(item);
            }
        }
    }

    /** 上线模块镜像地址解析结果。 */
    private static class ParsedModule {
        private String registry;
        private String project;
        private String serviceCode;
        private String version;
        private String imageFullName;
    }
}
