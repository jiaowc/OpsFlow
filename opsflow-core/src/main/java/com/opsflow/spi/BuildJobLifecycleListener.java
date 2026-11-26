package com.opsflow.spi;

import com.opsflow.api.dto.BuildResponse;
import com.opsflow.dao.model.BuildJob;

/**
 * 构建任务生命周期回调。可以有多个实现，核心按顺序逐个调用。
 * <p>回调类方法（{@code on*}）的异常由核心捕获并记录，不影响流水线自身的状态；
 * {@link #onRollbackStarted} 例外，它和回滚在同一个事务里，异常照常抛出。</p>
 * <p>{@code describeManagedJob}、{@code retryManagedJob} 用于"由扩展创建和管理的 Job"
 * （如上线任务的 CD Job，{@link BuildJob#getDeployTaskId()} 非空）：返回 null 表示这个 Job 不归该实现管。</p>
 */
public interface BuildJobLifecycleListener {

    /** Job 写入终态（SUCCESS / FAILED，含被回收的中断任务和提前失败）之后调用 */
    default void onJobFinished(BuildJob job) {
    }

    /** 某服务在某环境上的构建占用已释放（事务提交之后调用），可用于唤醒排队的任务 */
    default void onServiceReleased(Long serviceId, Long envId) {
    }

    /** 原 Job 回滚已写库、即将重新执行时调用（与回滚同一事务） */
    default void onRollbackStarted(BuildJob job) {
    }

    /** 列表展示用的重试状态；不归该实现管时返回 null */
    default ManagedJobState describeManagedJob(BuildJob job) {
        return null;
    }

    /** 重新执行由扩展管理的 Job；不归该实现管时返回 null */
    default BuildResponse retryManagedJob(BuildJob job) {
        return null;
    }

    /** 由扩展管理的 Job 在流水线列表里的状态 */
    final class ManagedJobState {
        private final boolean retryable;
        private final String blockedReason;

        public ManagedJobState(boolean retryable, String blockedReason) {
            this.retryable = retryable;
            this.blockedReason = blockedReason;
        }

        public boolean isRetryable() {
            return retryable;
        }

        public String getBlockedReason() {
            return blockedReason;
        }
    }
}
