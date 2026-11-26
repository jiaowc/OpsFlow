package com.opsflow.integration.pipeline;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongConsumer;

/**
 * 流水线任务调度：固定数量的执行线程 + 先进先出排队。
 * <p>流水线步骤大量阻塞在 SSH/kubectl 上，不能放进 ForkJoin 公共池（并发数只有 CPU 核数-1，
 * 且与其它异步任务共用）。超出并发上限的任务在这里排队，并对外暴露排队位置。</p>
 */
@Slf4j
@Component
public class PipelineJobDispatcher {

    @Value("${opsflow.pipeline.max-concurrent-jobs:10}")
    private int maxConcurrentJobs;

    private ThreadPoolExecutor executor;

    /** 已提交但尚未开始执行的任务，按提交顺序排列 */
    private final LinkedHashSet<Long> queuedJobIds = new LinkedHashSet<>();

    private final AtomicInteger runningCount = new AtomicInteger();

    /** 排队中或执行中的任务；同一任务重复提交直接忽略 */
    private final Set<Long> activeJobIds = ConcurrentHashMap.newKeySet();

    @PostConstruct
    public void init() {
        int size = Math.max(1, maxConcurrentJobs);
        AtomicInteger seq = new AtomicInteger();
        executor = new ThreadPoolExecutor(size, size, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(), r -> {
            Thread t = new Thread(r, "pipeline-job-" + seq.incrementAndGet());
            t.setDaemon(false);
            return t;
        });
        log.info("流水线执行线程池已初始化: 最大并发 {}", size);
    }

    @PreDestroy
    public void shutdown() {
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    /**
     * 提交任务。task 收到的参数是排队等待的毫秒数。
     */
    public void submit(Long jobId, LongConsumer task) {
        if (!activeJobIds.add(jobId)) {
            log.warn("流水线任务已在排队或执行中，忽略重复提交: jobId={}", jobId);
            return;
        }
        long submittedAt = System.currentTimeMillis();
        int ahead;
        synchronized (queuedJobIds) {
            ahead = queuedJobIds.size();
            queuedJobIds.add(jobId);
        }
        if (runningCount.get() + ahead >= maxConcurrentJobs()) {
            log.info("流水线任务进入排队: jobId={}, 排队位置={}, 运行中={}/{}", jobId, ahead + 1, runningCount.get(), maxConcurrentJobs());
        }
        try {
            executor.execute(() -> {
                synchronized (queuedJobIds) {
                    queuedJobIds.remove(jobId);
                }
                runningCount.incrementAndGet();
                try {
                    task.accept(System.currentTimeMillis() - submittedAt);
                } finally {
                    runningCount.decrementAndGet();
                    activeJobIds.remove(jobId);
                }
            });
        } catch (RuntimeException e) {
            synchronized (queuedJobIds) {
                queuedJobIds.remove(jobId);
            }
            activeJobIds.remove(jobId);
            throw e;
        }
    }

    /** 是否已提交且尚未执行完毕（排队或执行中） */
    public boolean isActive(Long jobId) {
        return jobId != null && activeJobIds.contains(jobId);
    }

    public boolean isQueued(Long jobId) {
        synchronized (queuedJobIds) {
            return jobId != null && queuedJobIds.contains(jobId);
        }
    }

    /**
     * 排队位置（从 1 开始）；不在队列中返回 0
     */
    public int queuePosition(Long jobId) {
        synchronized (queuedJobIds) {
            int index = 1;
            for (Long id : queuedJobIds) {
                if (id.equals(jobId)) {
                    return index;
                }
                index++;
            }
            return 0;
        }
    }

    public List<Long> queuedJobIds() {
        synchronized (queuedJobIds) {
            return new ArrayList<>(queuedJobIds);
        }
    }

    public int runningCount() {
        return runningCount.get();
    }

    public int maxConcurrentJobs() {
        return Math.max(1, maxConcurrentJobs);
    }
}
