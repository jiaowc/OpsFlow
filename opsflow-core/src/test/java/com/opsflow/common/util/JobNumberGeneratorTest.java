package com.opsflow.common.util;

import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JobNumberGeneratorTest {

    @Test
    void concurrentGenerationProducesUniqueNumbers() throws Exception {
        int threads = 8;
        int perThread = 200;
        Set<String> numbers = ConcurrentHashMap.newKeySet();
        CountDownLatch done = new CountDownLatch(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        for (int t = 0; t < threads; t++) {
            pool.execute(() -> {
                for (int i = 0; i < perThread; i++) {
                    numbers.add(JobNumberGenerator.generateBuildJobNumber());
                }
                done.countDown();
            });
        }
        assertTrue(done.await(30, TimeUnit.SECONDS));
        pool.shutdown();
        assertEquals(threads * perThread, numbers.size());
        assertTrue(numbers.stream().allMatch(n -> n.matches("BUILD-\\d{14}-\\d{3}")));
    }
}
