package com.opsflow.integration.pipeline.impl;

import com.opsflow.integration.pipeline.PipelineExecutionContext;
import com.opsflow.integration.pipeline.PipelineStageListener;
import com.opsflow.integration.pipeline.step.StepExecutionResult;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NativePipelineExecutorHeartbeatTest {

    /** 只记录心跳的监听器 */
    private static class RecordingListener implements PipelineStageListener {
        final List<Integer> heartbeats = Collections.synchronizedList(new ArrayList<>());
        boolean failOnHeartbeat;

        @Override
        public void onStageStart(int stepOrder, String stepType, String stepName) {
        }

        @Override
        public void onStageLog(int stepOrder, String chunk) {
        }

        @Override
        public void onStageHeartbeat(int stepOrder) {
            heartbeats.add(stepOrder);
            if (failOnHeartbeat) {
                throw new IllegalStateException("db down");
            }
        }

        @Override
        public void onStageComplete(int stepOrder, boolean success, String log, String errorMessage, long durationMs) {
        }
    }

    private static PipelineExecutionContext contextWith(PipelineStageListener listener, Integer stepOrder) {
        PipelineExecutionContext context = new PipelineExecutionContext();
        context.setStageListener(listener);
        context.setCurrentStepOrder(stepOrder);
        return context;
    }

    private static CompletableFuture<StepExecutionResult> completeAfter(long millis, StepExecutionResult result) {
        CompletableFuture<StepExecutionResult> future = new CompletableFuture<>();
        Thread thread = new Thread(() -> {
            try {
                Thread.sleep(millis);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            future.complete(result);
        });
        thread.setDaemon(true);
        thread.start();
        return future;
    }

    @Test
    void silentLongRunningStepKeepsSendingHeartbeats() throws Exception {
        RecordingListener listener = new RecordingListener();
        StepExecutionResult expected = new StepExecutionResult();

        StepExecutionResult actual = NativePipelineExecutor.awaitWithHeartbeat(
            completeAfter(400, expected), 10, contextWith(listener, 3), 50);

        assertSame(expected, actual);
        assertTrue(listener.heartbeats.size() >= 3, "心跳次数: " + listener.heartbeats.size());
        assertTrue(listener.heartbeats.stream().allMatch(order -> order == 3));
    }

    @Test
    void quickStepSendsNoHeartbeat() throws Exception {
        RecordingListener listener = new RecordingListener();
        StepExecutionResult expected = new StepExecutionResult();

        assertSame(expected, NativePipelineExecutor.awaitWithHeartbeat(
            CompletableFuture.completedFuture(expected), 10, contextWith(listener, 1), 50));
        assertEquals(0, listener.heartbeats.size());
    }

    @Test
    void stepTimeoutStillApplies() {
        RecordingListener listener = new RecordingListener();
        long start = System.currentTimeMillis();

        assertThrows(TimeoutException.class, () -> NativePipelineExecutor.awaitWithHeartbeat(
            new CompletableFuture<>(), 1, contextWith(listener, 2), 200));

        long elapsed = System.currentTimeMillis() - start;
        assertTrue(elapsed >= 900 && elapsed < 3000, "耗时: " + elapsed);
        assertTrue(listener.heartbeats.size() >= 3, "心跳次数: " + listener.heartbeats.size());
    }

    @Test
    void heartbeatFailureDoesNotBreakTheStep() throws Exception {
        RecordingListener listener = new RecordingListener();
        listener.failOnHeartbeat = true;
        StepExecutionResult expected = new StepExecutionResult();

        assertSame(expected, NativePipelineExecutor.awaitWithHeartbeat(
            completeAfter(200, expected), 10, contextWith(listener, 1), 50));
        assertTrue(listener.heartbeats.size() >= 1);
    }

    @Test
    void missingListenerOrStepOrderIsTolerated() throws Exception {
        StepExecutionResult expected = new StepExecutionResult();
        assertSame(expected, NativePipelineExecutor.awaitWithHeartbeat(
            completeAfter(150, expected), 10, null, 50));
        assertSame(expected, NativePipelineExecutor.awaitWithHeartbeat(
            completeAfter(150, expected), 10, contextWith(null, 1), 50));
        assertSame(expected, NativePipelineExecutor.awaitWithHeartbeat(
            completeAfter(150, expected), 10, contextWith(new RecordingListener(), null), 50));
    }

    @Test
    void stepExceptionIsPropagated() {
        CompletableFuture<StepExecutionResult> failed = new CompletableFuture<>();
        failed.completeExceptionally(new IllegalStateException("boom"));

        ExecutionException error = assertThrows(ExecutionException.class,
            () -> NativePipelineExecutor.awaitWithHeartbeat(failed, 10, contextWith(new RecordingListener(), 1), 50));
        assertEquals("boom", error.getCause().getMessage());
    }
}
