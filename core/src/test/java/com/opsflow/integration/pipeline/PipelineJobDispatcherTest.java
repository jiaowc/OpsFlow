package com.opsflow.integration.pipeline;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PipelineJobDispatcherTest {

    private PipelineJobDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        dispatcher = new PipelineJobDispatcher();
        ReflectionTestUtils.setField(dispatcher, "maxConcurrentJobs", 2);
        dispatcher.init();
    }

    @AfterEach
    void tearDown() {
        dispatcher.shutdown();
    }

    @Test
    void jobsBeyondLimitAreQueuedInOrderAndRunWhenSlotFrees() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch bothRunning = new CountDownLatch(2);
        CountDownLatch thirdDone = new CountDownLatch(1);
        AtomicLong thirdWaited = new AtomicLong(-1);

        dispatcher.submit(1L, waited -> { bothRunning.countDown(); await(release); });
        dispatcher.submit(2L, waited -> { bothRunning.countDown(); await(release); });
        assertTrue(bothRunning.await(5, TimeUnit.SECONDS));

        dispatcher.submit(3L, waited -> { thirdWaited.set(waited); thirdDone.countDown(); });
        dispatcher.submit(4L, waited -> { });

        assertEquals(2, dispatcher.runningCount());
        assertFalse(dispatcher.isQueued(1L));
        assertEquals(1, dispatcher.queuePosition(3L));
        assertEquals(2, dispatcher.queuePosition(4L));
        assertEquals(0, dispatcher.queuePosition(99L));

        Thread.sleep(200);
        release.countDown();
        assertTrue(thirdDone.await(5, TimeUnit.SECONDS));
        assertTrue(thirdWaited.get() >= 200, "排队时间应计入 waited: " + thirdWaited.get());
        assertFalse(dispatcher.isQueued(3L));
    }

    @Test
    void duplicateSubmitOfActiveJobIsIgnored() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch started = new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicInteger runs = new java.util.concurrent.atomic.AtomicInteger();

        dispatcher.submit(7L, waited -> { runs.incrementAndGet(); started.countDown(); await(release); });
        assertTrue(started.await(5, TimeUnit.SECONDS));
        dispatcher.submit(7L, waited -> runs.incrementAndGet());
        assertTrue(dispatcher.isActive(7L));

        release.countDown();
        for (int i = 0; i < 50 && dispatcher.isActive(7L); i++) {
            Thread.sleep(20);
        }
        assertFalse(dispatcher.isActive(7L));
        assertEquals(1, runs.get());

        CountDownLatch rerun = new CountDownLatch(1);
        dispatcher.submit(7L, waited -> { runs.incrementAndGet(); rerun.countDown(); });
        assertTrue(rerun.await(5, TimeUnit.SECONDS), "执行完毕后应允许再次提交");
        assertEquals(2, runs.get());
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
