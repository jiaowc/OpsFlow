package com.opsflow.service.impl.pipeline;

import com.opsflow.api.dto.BuildResponse;
import com.opsflow.dao.model.BuildJob;
import com.opsflow.spi.BuildJobLifecycleListener;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 生命周期分发：社区版没有监听者时全是空操作；一个监听者出错不影响其他监听者和流水线自身。
 */
class BuildJobLifecycleNotifierTest {

    private static BuildJobLifecycleNotifier notifier(BuildJobLifecycleListener... listeners) {
        DefaultListableBeanFactory factory = new DefaultListableBeanFactory();
        for (int i = 0; i < listeners.length; i++) {
            factory.registerSingleton("listener" + i, listeners[i]);
        }
        BuildJobLifecycleNotifier notifier = new BuildJobLifecycleNotifier();
        ReflectionTestUtils.setField(notifier, "listeners", factory.getBeanProvider(BuildJobLifecycleListener.class));
        return notifier;
    }

    private static BuildJob job(Long deployTaskId) {
        BuildJob job = new BuildJob();
        job.setId(1L);
        job.setDeployTaskId(deployTaskId);
        return job;
    }

    @Test
    void withoutListenersEverythingIsANoOp() {
        BuildJobLifecycleNotifier notifier = notifier();
        assertDoesNotThrow(() -> notifier.jobFinished(job(9L), null));
        assertDoesNotThrow(() -> notifier.serviceReleased(1L, 2L));
        assertDoesNotThrow(() -> notifier.rollbackStarted(job(9L)));
        assertNull(notifier.describeManagedJob(job(9L)));
        assertNull(notifier.retryManagedJob(job(9L)));
    }

    @Test
    void failingListenerDoesNotStopTheOthers() {
        List<String> calls = new ArrayList<>();
        BuildJobLifecycleListener broken = new BuildJobLifecycleListener() {
            @Override
            public void onJobFinished(BuildJob job) {
                throw new IllegalStateException("db down");
            }

            @Override
            public void onServiceReleased(Long serviceId, Long envId) {
                throw new IllegalStateException("db down");
            }
        };
        BuildJobLifecycleListener good = new BuildJobLifecycleListener() {
            @Override
            public void onJobFinished(BuildJob job) {
                calls.add("finished");
            }

            @Override
            public void onServiceReleased(Long serviceId, Long envId) {
                calls.add("released");
            }
        };
        BuildJobLifecycleNotifier notifier = notifier(broken, good);

        notifier.jobFinished(job(null), "fail");
        notifier.serviceReleased(1L, 2L);

        assertEquals(List.of("finished", "released"), calls);
    }

    @Test
    void rollbackErrorsPropagateBecauseTheyShareTheTransaction() {
        BuildJobLifecycleNotifier notifier = notifier(new BuildJobLifecycleListener() {
            @Override
            public void onRollbackStarted(BuildJob job) {
                throw new IllegalStateException("update failed");
            }
        });
        assertThrows(IllegalStateException.class, () -> notifier.rollbackStarted(job(9L)));
    }

    @Test
    void firstListenerThatClaimsTheJobAnswers() {
        BuildResponse retried = new BuildResponse();
        retried.setJobNumber("BUILD-2");
        BuildJobLifecycleListener notMine = new BuildJobLifecycleListener() {
        };
        BuildJobLifecycleListener mine = new BuildJobLifecycleListener() {
            @Override
            public ManagedJobState describeManagedJob(BuildJob job) {
                return new ManagedJobState(false, "前一个模块未成功");
            }

            @Override
            public BuildResponse retryManagedJob(BuildJob job) {
                return retried;
            }
        };
        BuildJobLifecycleNotifier notifier = notifier(notMine, mine);

        BuildJobLifecycleListener.ManagedJobState state = notifier.describeManagedJob(job(9L));
        assertEquals("前一个模块未成功", state.getBlockedReason());
        assertTrue(!state.isRetryable());
        assertEquals("BUILD-2", notifier.retryManagedJob(job(9L)).getJobNumber());
    }
}
