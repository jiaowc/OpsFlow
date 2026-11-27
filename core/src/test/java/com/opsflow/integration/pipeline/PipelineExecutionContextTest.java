package com.opsflow.integration.pipeline;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PipelineExecutionContextTest {

    private final List<String> written = Collections.synchronizedList(new ArrayList<>());

    private PipelineExecutionContext context() {
        PipelineExecutionContext context = new PipelineExecutionContext();
        context.setCurrentStepOrder(1);
        context.setStageListener(new PipelineStageListener() {
            @Override
            public void onStageStart(int stepOrder, String stepType, String stepName) {
            }

            @Override
            public void onStageLog(int stepOrder, String chunk) {
                written.add(stepOrder + ":" + chunk);
            }

            @Override
            public void onStageComplete(int stepOrder, boolean success, String log, String errorMessage,
                                        long durationMs) {
            }
        });
        return context;
    }

    @Test
    void abandonedStepAndTheThreadsItStartedNoLongerWriteToTheStageLog() throws Exception {
        PipelineExecutionContext context = context();
        CountDownLatch abandoned = new CountDownLatch(1);
        Callable<Void> step = context.bindToStepLog(() -> {
            context.emitLog("还在步骤里");
            // 读命令输出的线程是步骤线程创建的，同样受约束
            Thread reader = new Thread(() -> {
                try {
                    abandoned.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                context.emitLog("读线程迟到的输出");
            });
            reader.start();
            abandoned.await(10, TimeUnit.SECONDS);
            context.emitLog("步骤线程迟到的收尾");
            reader.join(10000);
            return null;
        });
        Thread stepThread = new Thread(() -> {
            try {
                step.call();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        stepThread.start();
        waitUntil(() -> written.size() == 1);

        context.abandonStepLog();
        context.emitLog("执行器写的超时说明");
        // 下一步开始后，被放弃的步骤也不能写进新阶段
        context.setCurrentStepOrder(2);
        abandoned.countDown();
        stepThread.join(10000);

        assertEquals(Arrays.asList("1:还在步骤里", "1:执行器写的超时说明"), written);
    }

    @Test
    void stepBoundAfterAnAbandonedOneWritesNormally() throws Exception {
        PipelineExecutionContext context = context();
        context.abandonStepLog();
        context.bindToStepLog(() -> {
            context.emitLog("下一步的输出");
            return null;
        }).call();

        assertEquals(Collections.singletonList("1:下一步的输出"), written);
    }

    private static void waitUntil(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.getAsBoolean()) {
            assertTrue(System.nanoTime() < deadline, "等待超时");
            Thread.sleep(10);
        }
    }
}
