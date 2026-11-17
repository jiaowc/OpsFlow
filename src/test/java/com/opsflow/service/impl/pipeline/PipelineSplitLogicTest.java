package com.opsflow.service.impl.pipeline;

import com.opsflow.common.constant.BuildStatus;
import com.opsflow.common.constant.PipelineTypes;
import com.opsflow.dao.mapper.BuildJobMapper;
import com.opsflow.dao.mapper.PipelineMapper;
import com.opsflow.dao.model.BuildJob;
import com.opsflow.dao.model.BuildJobStage;
import com.opsflow.dao.model.Pipeline;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * PipelineRunServiceImpl 拆分出来的纯逻辑：节点解析、僵尸任务判定、重建参数、回滚判定、展示格式化
 */
class PipelineSplitLogicTest {

    // ---------- BuildNodeSelector ----------

    @Test
    void parseBuildNodeIdsSkipsBlankInvalidAndDuplicates() {
        BuildNodeSelector selector = new BuildNodeSelector();
        assertEquals(Arrays.asList(3L, 1L, 7L), selector.parseBuildNodeIds(" 3, ,abc,1,3, 7 "));
        assertTrue(selector.parseBuildNodeIds(null).isEmpty());
        assertTrue(selector.parseBuildNodeIds("  ").isEmpty());
    }

    @Test
    void singleCandidateIsSelectedWithoutProbingLoad() {
        BuildNodeSelector selector = new BuildNodeSelector();
        Pipeline pipeline = new Pipeline();
        pipeline.setBuildNodeIds("5");
        // 只有一个候选时不读负载（sshClient 为 null 也不会被调用）
        assertEquals(5L, selector.selectBuildNodeId(pipeline));
        assertEquals(5L, selector.firstBuildNodeId(pipeline));
        assertNull(selector.selectBuildNodeId(null));
    }

    // ---------- PipelineJobLiveness ----------

    @Test
    void stuckPreparedJobNeedsGracePeriodAndAllPendingStages() {
        PipelineJobLiveness liveness = new PipelineJobLiveness();
        BuildJob job = new BuildJob();

        job.setStartTime(LocalDateTime.now().minusSeconds(5));
        assertFalse(liveness.isStuckPreparedJob(job, Collections.emptyList()), "刚创建的任务在宽限期内");

        job.setStartTime(LocalDateTime.now().minusMinutes(2));
        assertTrue(liveness.isStuckPreparedJob(job, Collections.emptyList()), "超过阈值且没有阶段");
        assertTrue(liveness.isStuckPreparedJob(job, Arrays.asList(stage("PENDING"), stage("pending"))));
        assertFalse(liveness.isStuckPreparedJob(job, Arrays.asList(stage("SUCCESS"), stage("PENDING"))),
                "已有阶段开始执行就不是卡住");
    }

    // ---------- PipelineRebuildService ----------

    @Test
    void fullRebuildDropsRollbackMarkers() {
        PipelineRebuildService rebuild = new PipelineRebuildService();
        ReflectionTestUtils.setField(rebuild, "support", new PipelineJobSupport());
        String out = rebuild.copyParamsForFullRebuild(
                "{\"rollback\":\"true\",\"rollbackFromJobId\":\"9\",\"rollbackFromImage\":\"x\",\"k8sNamespace\":\"dev\"}");
        assertEquals("{\"k8sNamespace\":\"dev\"}", out);
        assertEquals("{}", rebuild.copyParamsForFullRebuild("{\"rollback\":\"true\"}"));
        assertNull(rebuild.copyParamsForFullRebuild(null));
        assertNull(rebuild.copyParamsForFullRebuild(" "));
    }

    // ---------- PipelineRollbackService ----------

    @Test
    void ciOnlyStepTypesAreKeptOnRollback() {
        PipelineRollbackService rollback = new PipelineRollbackService();
        for (String t : Arrays.asList("checkout", "BUILD", " docker_build ", "push-image", "clean_workspace")) {
            assertTrue(rollback.isCiOnlyStepType(t), t);
        }
        for (String t : Arrays.asList("deploy", "check_deploy", "render_template", null)) {
            assertFalse(rollback.isCiOnlyStepType(t), String.valueOf(t));
        }
    }

    @Test
    void canRollbackOnlyWhenAnOlderImageExists() {
        PipelineRollbackService rollback = new PipelineRollbackService();
        PipelineMapper pipelineMapper = mock(PipelineMapper.class);
        BuildJobMapper buildJobMapper = mock(BuildJobMapper.class);
        PipelineJobSupport support = new PipelineJobSupport();
        ReflectionTestUtils.setField(rollback, "pipelineMapper", pipelineMapper);
        ReflectionTestUtils.setField(rollback, "buildJobMapper", buildJobMapper);
        ReflectionTestUtils.setField(rollback, "support", support);

        Pipeline cicd = new Pipeline();
        cicd.setPipelineType(PipelineTypes.CICD);
        when(pipelineMapper.selectById(1L)).thenReturn(cicd);

        BuildJob latest = job(10L, BuildStatus.SUCCESS, "repo/app:v2");
        latest.setPipelineTemplateId(1L);

        // 只有当前成功版本：不可回滚
        when(buildJobMapper.selectList(any())).thenReturn(Collections.singletonList(latest));
        assertFalse(rollback.canRollback(latest));

        // 有更早的成功版本：可回滚
        when(buildJobMapper.selectList(any())).thenReturn(Arrays.asList(latest, job(9L, BuildStatus.SUCCESS, "repo/app:v1")));
        assertTrue(rollback.canRollback(latest));

        // CI 流水线不支持回滚
        Pipeline ci = new Pipeline();
        ci.setPipelineType(PipelineTypes.CI);
        when(pipelineMapper.selectById(1L)).thenReturn(ci);
        assertFalse(rollback.canRollback(latest));
    }

    // ---------- PipelineJobQueryService ----------

    @Test
    void stageStatusAndDurationFormatting() {
        PipelineJobQueryService query = new PipelineJobQueryService();
        assertEquals("IN_PROGRESS", query.mapStageStatus("RUNNING"));
        assertEquals("NOT_EXECUTED", query.mapStageStatus("PENDING"));
        assertEquals("UNKNOWN", query.mapStageStatus(null));
        assertEquals("CUSTOM", query.mapStageStatus("CUSTOM"));

        assertEquals("-", query.formatDuration(null));
        assertEquals("-", query.formatDuration(0L));
        assertEquals("500ms", query.formatDuration(500L));
        assertEquals("59s", query.formatDuration(59_999L));
        assertEquals("2m 5s", query.formatDuration(125_000L));
    }

    @Test
    void listKeywordAndPageSizeNormalization() {
        PipelineJobQueryService query = new PipelineJobQueryService();
        assertNull(query.normalizePipelineListKeyword("   "));
        assertEquals("svc", query.normalizePipelineListKeyword("  svc "));
        assertEquals(100, query.normalizePipelineListKeyword(repeat('a', 150)).length());

        assertEquals(20, query.normalizePageSize(20));
        assertEquals(50, query.normalizePageSize(50));
        assertEquals(10, query.normalizePageSize(30));
    }

    private static BuildJobStage stage(String status) {
        BuildJobStage s = new BuildJobStage();
        s.setStatus(status);
        return s;
    }

    private static BuildJob job(Long id, String status, String image) {
        BuildJob j = new BuildJob();
        j.setId(id);
        j.setServiceId(1L);
        j.setEnvId(1L);
        j.setStatus(status);
        j.setImageFullName(image);
        return j;
    }

    private static String repeat(char c, int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            sb.append(c);
        }
        return sb.toString();
    }
}
