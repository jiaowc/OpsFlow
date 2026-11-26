package com.opsflow.service.impl.pipeline;

import com.opsflow.api.dto.BuildResponse;
import com.opsflow.dao.model.BuildJob;
import com.opsflow.spi.BuildJobLifecycleListener;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 把流水线事件分发给所有 {@link BuildJobLifecycleListener}。
 * <p>监听者在调用时才取（{@link ObjectProvider}）：实现（如上线任务 CD）通常又依赖流水线服务，
 * 启动时注入会形成循环依赖。社区版没有监听者，所有方法都是空操作。</p>
 */
@Slf4j
@Component
public class BuildJobLifecycleNotifier {

    @Autowired
    private ObjectProvider<BuildJobLifecycleListener> listeners;

    private List<BuildJobLifecycleListener> listeners() {
        return listeners.orderedStream().collect(Collectors.toList());
    }

    /**
     * Job 已写入终态。每个监听者单独兜底，失败只记日志，不影响流水线自身。
     *
     * @param where 日志里区分调用位置（如 fail、recover），可为空
     */
    public void jobFinished(BuildJob job, String where) {
        for (BuildJobLifecycleListener listener : listeners()) {
            try {
                listener.onJobFinished(job);
            } catch (Exception e) {
                log.error("Job 终态回调失败{}, jobId={}, deployTaskId={}",
                        where != null ? "(" + where + ")" : "", job.getId(), job.getDeployTaskId(), e);
            }
        }
    }

    /** 服务在该环境上的构建占用已释放（调用方保证在事务提交之后） */
    public void serviceReleased(Long serviceId, Long envId) {
        for (BuildJobLifecycleListener listener : listeners()) {
            try {
                listener.onServiceReleased(serviceId, envId);
            } catch (Exception e) {
                log.error("唤醒排队的任务失败: serviceId={}, envId={}", serviceId, envId, e);
            }
        }
    }

    /** 回滚即将重新执行原 Job；和回滚同一事务，异常向上抛出 */
    public void rollbackStarted(BuildJob job) {
        for (BuildJobLifecycleListener listener : listeners()) {
            listener.onRollbackStarted(job);
        }
    }

    /** 第一个认领该 Job 的监听者给出的状态；没有监听者认领时返回 null */
    public BuildJobLifecycleListener.ManagedJobState describeManagedJob(BuildJob job) {
        for (BuildJobLifecycleListener listener : listeners()) {
            BuildJobLifecycleListener.ManagedJobState state = listener.describeManagedJob(job);
            if (state != null) {
                return state;
            }
        }
        return null;
    }

    /** 交给认领该 Job 的监听者重新执行；没有监听者认领时返回 null */
    public BuildResponse retryManagedJob(BuildJob job) {
        for (BuildJobLifecycleListener listener : listeners()) {
            BuildResponse response = listener.retryManagedJob(job);
            if (response != null) {
                return response;
            }
        }
        return null;
    }
}
