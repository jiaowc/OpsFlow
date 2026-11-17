package com.opsflow.service.impl.deploy;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.opsflow.common.constant.BuildStatus;
import com.opsflow.common.constant.DeployModes;
import com.opsflow.dao.mapper.BuildJobMapper;
import com.opsflow.dao.model.BuildJob;
import com.opsflow.dao.model.DeployTask;
import com.opsflow.service.PipelineRunService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 上线任务 CD Job 的挑选规则：按部署策略（串行 / 并行 / 失败即停）和同服务同环境互斥，
 * 决定此刻哪些 PENDING Job 可以启动。
 * <p>
 * 只读判断，不开事务、不改状态；调用方（{@code DeployTaskCdServiceImpl}）在自己的事务和任务行锁内调用。
 * </p>
 */
@Slf4j
@Component
public class CdJobScheduler {

    /** 因前置模块失败被跳过的 Job 的 errorMessage 标记 */
    public static final String SKIPPED_BY_FAIL_FAST = "前置模块部署失败，已跳过";

    @Autowired
    private BuildJobMapper buildJobMapper;

    @Autowired
    @Lazy
    private PipelineRunService pipelineRunService;

    public List<BuildJob> listJobsForDeployTask(Long deployTaskId) {
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
    public List<Long> pickRunnableCdJobs(DeployTask task, int maxPick) {
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

        Set<String> pickedTargets = new HashSet<>();
        if (DeployModes.isParallel(task.getDeployMode())) {
            for (BuildJob item : jobs) {
                if (result.size() >= free) {
                    break;
                }
                if (BuildStatus.PENDING.equals(item.getStatus()) && claimTarget(item, pickedTargets)) {
                    result.add(item.getId());
                }
            }
            return result;
        }

        // serial：必须无运行中，且按顺序推进；下一个模块的服务被占用时整体等待
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
            if (BuildStatus.PENDING.equals(status) && claimTarget(item, pickedTargets)) {
                result.add(item.getId());
            }
            return result;
        }
        return result;
    }

    /**
     * 同一服务同一环境同时只允许一个任务部署（含其他上线任务和普通流水线）。
     * 被占用时模块保持 PENDING，占用方结束后由 {@code DeployTaskCdService#wakeWaitingCdJobs} 唤醒。
     */
    private boolean claimTarget(BuildJob item, Set<String> pickedTargets) {
        if (!pickedTargets.add(item.getServiceId() + ":" + item.getEnvId())) {
            return false;
        }
        String runningJob = pipelineRunService.findRunningJobNumber(item.getServiceId(), item.getEnvId(), item.getId());
        if (runningJob != null) {
            log.info("CD 模块等待同服务部署完成: jobId={}, deployTaskId={}, 占用方={}",
                    item.getId(), item.getDeployTaskId(), runningJob);
            return false;
        }
        return true;
    }

    public boolean hasRunningCdJob(List<BuildJob> jobs) {
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

    public boolean isSkippedByFailFast(BuildJob job) {
        return job != null
                && BuildStatus.FAILED.equals(job.getStatus())
                && StringUtils.hasText(job.getErrorMessage())
                && job.getErrorMessage().contains(SKIPPED_BY_FAIL_FAST);
    }

    public boolean isFirstNonSuccessJob(BuildJob target, List<BuildJob> jobs) {
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
}
