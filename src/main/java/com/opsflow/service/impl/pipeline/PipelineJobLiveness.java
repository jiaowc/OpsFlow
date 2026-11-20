package com.opsflow.service.impl.pipeline;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.opsflow.common.constant.BuildStatus;
import com.opsflow.dao.mapper.*;
import com.opsflow.dao.model.*;
import com.opsflow.integration.pipeline.PipelineJobDispatcher;
import com.opsflow.service.DeployTaskCdService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 流水线任务存活判定：本 JVM 内正在执行的任务集合、僵尸 BUILDING 任务回收、启动时恢复中断任务。
 * <p>由 {@link com.opsflow.service.impl.PipelineRunServiceImpl} 拆分而来，行为保持不变。</p>
 */
@Slf4j
@Component
public class PipelineJobLiveness {
    private static final long STALE_RUNNING_TIMEOUT_MINUTES = 10;
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
    private PipelineJobDispatcher pipelineJobDispatcher;

    @Autowired
    @Lazy
    private DeployTaskCdService deployTaskCdService;

    public void markActive(Long buildJobId) {
        activeRunningJobIds.add(buildJobId);
    }

    public void markInactive(Long buildJobId) {
        activeRunningJobIds.remove(buildJobId);
    }

    public boolean isJobRunning(BuildJob job) {
        refreshStaleRunningJob(job);
        return BuildStatus.BUILDING.equals(job.getStatus()) || BuildStatus.DEPLOYING.equals(job.getStatus());
    }

    /**
     * 任务结束后唤醒因同一服务被占用而排队的上线任务模块；有事务时等提交后再唤醒，
     * 否则排队方的新事务会等待本事务持有的行锁
     */
    public void wakeServiceWaitersAfterCommit(BuildJob job) {
        if (job == null || job.getServiceId() == null) {
            return;
        }
        Long serviceId = job.getServiceId();
        Long envId = job.getEnvId();
        Runnable wake = () -> {
            try {
                deployTaskCdService.wakeWaitingCdJobs(serviceId, envId);
            } catch (Exception e) {
                log.error("唤醒排队的上线任务失败: serviceId={}, envId={}", serviceId, envId, e);
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    wake.run();
                }
            });
        } else {
            wake.run();
        }
    }

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

    public void refreshStaleRunningJob(BuildJob job) {
        if (job == null) {
            return;
        }
        if (!BuildStatus.BUILDING.equals(job.getStatus()) && !BuildStatus.DEPLOYING.equals(job.getStatus())) {
            return;
        }
        if (pipelineJobDispatcher.isQueued(job.getId())) {
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

    boolean isStuckPreparedJob(BuildJob job, List<BuildJobStage> stages) {
        LocalDateTime started = job.getStartTime() != null ? job.getStartTime() : job.getUpdateTime();
        if (started == null || started.isAfter(LocalDateTime.now().minusSeconds(PREPARED_BUT_NOT_STARTED_SECONDS))) {
            return false;
        }
        if (stages == null || stages.isEmpty()) {
            return true;
        }
        return stages.stream().allMatch(stage -> "PENDING".equalsIgnoreCase(stage.getStatus()));
    }

    public void markInterruptedRunningJob(BuildJob job, String message) {
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
        wakeServiceWaitersAfterCommit(job);
    }
}
