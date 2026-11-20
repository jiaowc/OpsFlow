package com.opsflow.service.impl.deploy;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.opsflow.common.constant.BuildStatus;
import com.opsflow.common.constant.DeployModes;
import com.opsflow.dao.mapper.BuildJobMapper;
import com.opsflow.dao.model.BuildJob;
import com.opsflow.dao.model.DeployTask;
import com.opsflow.service.PipelineRunService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * CD Job 挑选规则：串行逐个推进、并行补满并发槽、失败即停、同服务同环境互斥。
 */
@SuppressWarnings("unchecked")
class CdJobSchedulerTest {

    private CdJobScheduler scheduler;
    private BuildJobMapper buildJobMapper;
    private PipelineRunService pipelineRunService;

    @BeforeEach
    void setUp() {
        scheduler = new CdJobScheduler();
        buildJobMapper = mock(BuildJobMapper.class);
        pipelineRunService = mock(PipelineRunService.class);
        ReflectionTestUtils.setField(scheduler, "buildJobMapper", buildJobMapper);
        ReflectionTestUtils.setField(scheduler, "pipelineRunService", pipelineRunService);
    }

    private static DeployTask task(String mode, Integer parallelism) {
        DeployTask task = new DeployTask();
        task.setId(1L);
        task.setDeployMode(mode);
        task.setDeployParallelism(parallelism);
        return task;
    }

    private static BuildJob job(long id, long serviceId, String status) {
        BuildJob job = new BuildJob();
        job.setId(id);
        job.setDeployTaskId(1L);
        job.setServiceId(serviceId);
        job.setEnvId(9L);
        job.setStatus(status);
        return job;
    }

    private static BuildJob skipped(long id, long serviceId) {
        BuildJob job = job(id, serviceId, BuildStatus.FAILED);
        job.setErrorMessage(CdJobScheduler.SKIPPED_BY_FAIL_FAST);
        return job;
    }

    private List<Long> pick(DeployTask task, BuildJob... jobs) {
        when(buildJobMapper.selectList(any(Wrapper.class))).thenReturn(Arrays.asList(jobs));
        return scheduler.pickRunnableCdJobs(task, Integer.MAX_VALUE);
    }

    // ---------- 串行 ----------

    @Test
    void serialPicksOnlyTheNextPendingModule() {
        assertEquals(Collections.singletonList(12L), pick(task(DeployModes.SERIAL, null),
                job(11, 100, BuildStatus.SUCCESS), job(12, 200, BuildStatus.PENDING), job(13, 300, BuildStatus.PENDING)));
    }

    @Test
    void serialWaitsWhileAnotherModuleIsRunning() {
        assertTrue(pick(task(DeployModes.SERIAL, null),
                job(11, 100, BuildStatus.BUILDING), job(12, 200, BuildStatus.PENDING)).isEmpty());
        assertTrue(pick(task(DeployModes.SERIAL, null),
                job(11, 100, BuildStatus.DEPLOYING), job(12, 200, BuildStatus.PENDING)).isEmpty());
    }

    @Test
    void serialDoesNotSkipAheadWhenNextModuleIsOccupied() {
        when(pipelineRunService.findRunningJobNumber(eq(200L), eq(9L), anyLong())).thenReturn("BUILD-OTHER");
        assertTrue(pick(task(DeployModes.SERIAL, null),
                job(11, 100, BuildStatus.SUCCESS), job(12, 200, BuildStatus.PENDING), job(13, 300, BuildStatus.PENDING)).isEmpty(),
                "下一个模块的服务被占用时整体等待，不能越过它先发后面的");
    }

    // ---------- 并行 ----------

    @Test
    void parallelFillsFreeSlotsUpToParallelism() {
        assertEquals(Arrays.asList(12L, 13L), pick(task(DeployModes.PARALLEL, 3),
                job(11, 100, BuildStatus.BUILDING),
                job(12, 200, BuildStatus.PENDING), job(13, 300, BuildStatus.PENDING), job(14, 400, BuildStatus.PENDING)));
    }

    @Test
    void parallelPicksNothingWhenAllSlotsAreBusy() {
        assertTrue(pick(task(DeployModes.PARALLEL, 2),
                job(11, 100, BuildStatus.BUILDING), job(12, 200, BuildStatus.DEPLOYING),
                job(13, 300, BuildStatus.PENDING)).isEmpty());
    }

    @Test
    void parallelStartsOneModulePerServiceAndEnvironment() {
        assertEquals(Arrays.asList(11L, 13L), pick(task(DeployModes.PARALLEL, 10),
                job(11, 100, BuildStatus.PENDING), job(12, 100, BuildStatus.PENDING), job(13, 200, BuildStatus.PENDING)));
    }

    @Test
    void maxPickLimitsTheNumberOfJobs() {
        when(buildJobMapper.selectList(any(Wrapper.class))).thenReturn(Arrays.asList(
                job(11, 100, BuildStatus.PENDING), job(12, 200, BuildStatus.PENDING), job(13, 300, BuildStatus.PENDING)));
        assertEquals(Collections.singletonList(11L),
                scheduler.pickRunnableCdJobs(task(DeployModes.PARALLEL, 10), 1));
        assertTrue(scheduler.pickRunnableCdJobs(task(DeployModes.PARALLEL, 10), 0).isEmpty());
    }

    // ---------- 失败即停 ----------

    @Test
    void realFailureStopsAutomaticLaunchInBothModes() {
        for (String mode : new String[]{DeployModes.SERIAL, DeployModes.PARALLEL}) {
            assertTrue(pick(task(mode, 5),
                    job(11, 100, BuildStatus.FAILED), job(12, 200, BuildStatus.PENDING)).isEmpty(),
                    mode + "：存在真实失败时需人工重试，不再自动启动");
        }
    }

    @Test
    void jobsSkippedByFailFastDoNotBlockParallelLaunch() {
        // 重试后：被跳过的模块标记仍在，但它不算真实失败
        assertEquals(Collections.singletonList(13L), pick(task(DeployModes.PARALLEL, 5),
                skipped(12, 200), job(13, 300, BuildStatus.PENDING)));
    }

    // ---------- 辅助判断 ----------

    @Test
    void skippedByFailFastIsRecognisedByErrorMessage() {
        assertTrue(scheduler.isSkippedByFailFast(skipped(1, 1)));
        assertFalse(scheduler.isSkippedByFailFast(job(1, 1, BuildStatus.FAILED)));
        BuildJob pendingWithMarker = skipped(1, 1);
        pendingWithMarker.setStatus(BuildStatus.PENDING);
        assertFalse(scheduler.isSkippedByFailFast(pendingWithMarker));
        assertFalse(scheduler.isSkippedByFailFast(null));
    }

    @Test
    void firstNonSuccessJobIsTheOnlyOneRetryableInSerialMode() {
        BuildJob done = job(11, 100, BuildStatus.SUCCESS);
        BuildJob failed = job(12, 200, BuildStatus.FAILED);
        BuildJob later = job(13, 300, BuildStatus.FAILED);
        List<BuildJob> jobs = Arrays.asList(done, failed, later);
        assertTrue(scheduler.isFirstNonSuccessJob(failed, jobs));
        assertFalse(scheduler.isFirstNonSuccessJob(later, jobs));
        assertFalse(scheduler.isFirstNonSuccessJob(done, jobs));
    }

    @Test
    void runningDetection() {
        assertTrue(scheduler.hasRunningCdJob(Arrays.asList(job(1, 1, BuildStatus.SUCCESS), job(2, 2, BuildStatus.DEPLOYING))));
        assertFalse(scheduler.hasRunningCdJob(Arrays.asList(job(1, 1, BuildStatus.SUCCESS), job(2, 2, BuildStatus.PENDING))));
        assertFalse(scheduler.hasRunningCdJob(null));
    }
}
