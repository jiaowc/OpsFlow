package com.opsflow.service.impl;

import com.opsflow.common.constant.BuildStatus;
import com.opsflow.common.constant.DeployModes;
import com.opsflow.dao.mapper.BuildJobMapper;
import com.opsflow.dao.mapper.DeployTaskMapper;
import com.opsflow.dao.model.BuildJob;
import com.opsflow.dao.model.DeployTask;
import com.opsflow.service.PipelineRunService;
import com.opsflow.service.impl.deploy.CdJobScheduler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 同一服务同一环境的 CD 模块互斥：被占用时保持排队，空闲时才启动
 */
class DeployTaskCdServiceImplTest {

    private DeployTaskCdServiceImpl service;
    private DeployTaskMapper deployTaskMapper;
    private BuildJobMapper buildJobMapper;
    private PipelineRunService pipelineRunService;

    @BeforeEach
    void setUp() {
        service = new DeployTaskCdServiceImpl();
        deployTaskMapper = mock(DeployTaskMapper.class);
        buildJobMapper = mock(BuildJobMapper.class);
        pipelineRunService = mock(PipelineRunService.class);
        ReflectionTestUtils.setField(service, "deployTaskMapper", deployTaskMapper);
        ReflectionTestUtils.setField(service, "buildJobMapper", buildJobMapper);
        ReflectionTestUtils.setField(service, "pipelineRunService", pipelineRunService);
        // 拆分后挑选规则在 CdJobScheduler 里，这里装配真实对象，断言保持不变
        CdJobScheduler scheduler = new CdJobScheduler();
        ReflectionTestUtils.setField(scheduler, "buildJobMapper", buildJobMapper);
        ReflectionTestUtils.setField(scheduler, "pipelineRunService", pipelineRunService);
        ReflectionTestUtils.setField(service, "cdJobScheduler", scheduler);
    }

    @Test
    void serialModuleWaitsWhileSameServiceIsDeploying() {
        givenTask(DeployModes.SERIAL, pendingJob(11L, 100L));
        when(pipelineRunService.findRunningJobNumber(100L, 9L, 11L)).thenReturn("BUILD-OTHER-001");

        service.resumeTask(1L);

        verify(pipelineRunService, never()).launchJob(anyLong());
    }

    @Test
    void serialModuleStartsWhenServiceIsIdle() {
        givenTask(DeployModes.SERIAL, pendingJob(11L, 100L));

        service.resumeTask(1L);

        verify(pipelineRunService).launchJob(11L);
    }

    @Test
    void parallelModeStartsOnlyOneModulePerServiceAndSkipsBusyOnes() {
        givenTask(DeployModes.PARALLEL,
                pendingJob(11L, 100L),
                pendingJob(12L, 100L),
                pendingJob(13L, 200L),
                pendingJob(14L, 300L));
        when(pipelineRunService.findRunningJobNumber(eq(200L), eq(9L), anyLong())).thenReturn("BUILD-OTHER-002");

        service.resumeTask(1L);

        verify(pipelineRunService).launchJob(11L);
        verify(pipelineRunService, never()).launchJob(12L);
        verify(pipelineRunService, never()).launchJob(13L);
        verify(pipelineRunService).launchJob(14L);
    }

    private void givenTask(String mode, BuildJob... jobs) {
        DeployTask task = new DeployTask();
        task.setId(1L);
        task.setApprovalStatus("approved");
        task.setTaskStatus("deploying");
        task.setDeployMode(mode);
        task.setDeployParallelism(10);
        when(deployTaskMapper.selectOne(any())).thenReturn(task);
        when(deployTaskMapper.selectById(1L)).thenReturn(task);
        when(buildJobMapper.selectList(any())).thenReturn(Arrays.asList(jobs));
    }

    private static BuildJob pendingJob(Long id, Long serviceId) {
        BuildJob job = new BuildJob();
        job.setId(id);
        job.setDeployTaskId(1L);
        job.setServiceId(serviceId);
        job.setEnvId(9L);
        job.setStatus(BuildStatus.PENDING);
        return job;
    }
}
