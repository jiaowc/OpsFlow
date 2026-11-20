package com.opsflow.service.impl.deploy;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.opsflow.common.constant.BuildStatus;
import com.opsflow.dao.mapper.BuildJobMapper;
import com.opsflow.dao.mapper.BuildJobStageMapper;
import com.opsflow.dao.model.BuildJob;
import com.opsflow.dao.model.BuildJobStage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * CD Job 的状态改写：重试前重置阶段、恢复因失败即停被跳过的模块、失败后取消其余待执行模块。
 * <p>
 * 不开事务；由 {@code DeployTaskCdServiceImpl} 在持有任务行锁的事务内调用。
 * </p>
 */
@Component
public class CdJobRetryService {

    @Autowired
    private BuildJobMapper buildJobMapper;

    @Autowired
    private BuildJobStageMapper buildJobStageMapper;

    @Autowired
    private CdJobScheduler cdJobScheduler;

    public void resetJobStagesForRetry(Long buildJobId) {
        List<BuildJobStage> stages = buildJobStageMapper.selectList(
                new QueryWrapper<BuildJobStage>().eq("build_job_id", buildJobId).orderByAsc("step_order"));
        LocalDateTime now = LocalDateTime.now();
        for (BuildJobStage stage : stages) {
            stage.setStatus("PENDING");
            stage.setErrorMessage(null);
            stage.setStartTime(null);
            stage.setEndTime(null);
            stage.setDurationMs(null);
            stage.setUpdateTime(now);
            buildJobStageMapper.updateById(stage);
        }
    }

    public void reactivateSkippedCdJobs(Long deployTaskId) {
        if (deployTaskId == null) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        for (BuildJob item : cdJobScheduler.listJobsForDeployTask(deployTaskId)) {
            if (!cdJobScheduler.isSkippedByFailFast(item)) {
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
    public void cancelRemainingPendingCdJobs(Long deployTaskId, Long failedJobId) {
        if (deployTaskId == null || failedJobId == null) {
            return;
        }
        List<BuildJob> jobs = cdJobScheduler.listJobsForDeployTask(deployTaskId);
        LocalDateTime now = LocalDateTime.now();
        for (BuildJob item : jobs) {
            if (item.getId().equals(failedJobId)) {
                continue;
            }
            if (BuildStatus.PENDING.equals(item.getStatus())) {
                item.setStatus(BuildStatus.FAILED);
                item.setErrorMessage(CdJobScheduler.SKIPPED_BY_FAIL_FAST);
                item.setEndTime(now);
                item.setUpdateTime(now);
                buildJobMapper.updateById(item);
            }
        }
    }
}
