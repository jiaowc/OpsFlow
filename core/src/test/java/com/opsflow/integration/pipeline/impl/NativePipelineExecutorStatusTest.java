package com.opsflow.integration.pipeline.impl;

import com.opsflow.api.dto.PipelineDTO;
import com.opsflow.integration.pipeline.PipelineExecutionContext;
import com.opsflow.integration.pipeline.PipelineExecutionResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

class NativePipelineExecutorStatusTest {

    @TempDir
    Path tempDir;

    @Test
    void executionStatusIsNotKeptAfterTheRunEnds() {
        // 执行状态只在运行期间保留；结束后还留着的话，每跑一次任务内存里就多一条永不释放的记录
        NativePipelineExecutor executor = new NativePipelineExecutor();
        PipelineDTO pipeline = new PipelineDTO();
        pipeline.setSteps(Collections.emptyList());

        for (int i = 0; i < 3; i++) {
            PipelineExecutionContext context = new PipelineExecutionContext();
            context.setExecutionId("job-" + i);
            context.setWorkspace(tempDir.resolve("ws").toString());

            PipelineExecutionResult result = executor.execute(pipeline, context);

            assertFalse(Boolean.TRUE.equals(result.getSuccess()));
            assertNull(executor.getStatus("job-" + i));
        }
    }
}
