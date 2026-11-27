package com.opsflow.integration.pipeline;

import lombok.AccessLevel;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;

import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Pipeline执行上下文
 */
@Data
public class PipelineExecutionContext {
    
    /**
     * 执行ID（唯一标识一次Pipeline执行）
     */
    private String executionId;
    
    /**
     * 工作目录
     */
    private String workspace;
    
    /**
     * 服务信息
     */
    private ServiceInfo service;
    
    /**
     * 环境信息
     */
    private EnvironmentInfo environment;
    
    /**
     * Pipeline参数
     */
    private Map<String, String> parameters;
    
    /**
     * 执行选项
     */
    private ExecutionOptions options;

    /**
     * 关联的构建任务 ID
     */
    private Long buildJobId;

    /**
     * 阶段执行回调（持久化阶段状态）
     */
    private PipelineStageListener stageListener;

    /**
     * 当前步骤超时时间（秒）
     */
    private Integer stepTimeoutSeconds;

    /**
     * 当前正在执行的步骤序号（用于实时日志回调）
     */
    private Integer currentStepOrder;

    /**
     * 当前步骤的日志是否在实时写入阶段日志文件。
     * 为 true 时命令输出已经逐行落盘，步骤不必再在内存里保留一份完整输出。
     */
    public boolean isLogStreaming() {
        return stageListener != null && currentStepOrder != null;
    }

    /**
     * 步骤线程（及其创建的读输出线程）绑定的是哪个上下文的哪一代步骤日志
     */
    private static final InheritableThreadLocal<StepLogBinding> STEP_LOG_BINDING = new InheritableThreadLocal<>();

    /**
     * 步骤日志的代次。步骤超时被放弃时加一，之后被放弃的步骤线程再写的日志一律丢弃，
     * 不会在阶段结束后重新打开日志文件，也不会混进下一个阶段的日志。
     */
    @Getter(AccessLevel.NONE)
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private final AtomicLong stepLogGeneration = new AtomicLong();

    /**
     * 让 {@code task} 以当前这一代步骤日志的身份运行：之后 {@link #abandonStepLog()} 了，
     * 它和它创建的线程再调用 {@link #emitLog} 都不会写进阶段日志。
     */
    public <T> Callable<T> bindToStepLog(Callable<T> task) {
        StepLogBinding binding = new StepLogBinding(this, stepLogGeneration.get());
        return () -> {
            STEP_LOG_BINDING.set(binding);
            try {
                return task.call();
            } finally {
                STEP_LOG_BINDING.remove();
            }
        };
    }

    /**
     * 放弃当前步骤（如已超时）：它此后写的日志不再记录
     */
    public void abandonStepLog() {
        stepLogGeneration.incrementAndGet();
    }

    /**
     * 向当前阶段追加实时日志
     */
    public void emitLog(String chunk) {
        if (stageListener == null || currentStepOrder == null || chunk == null || chunk.isEmpty()) {
            return;
        }
        StepLogBinding binding = STEP_LOG_BINDING.get();
        if (binding != null && binding.context == this && binding.generation != stepLogGeneration.get()) {
            return;
        }
        stageListener.onStageLog(currentStepOrder, chunk);
    }

    private static final class StepLogBinding {
        private final PipelineExecutionContext context;
        private final long generation;

        private StepLogBinding(PipelineExecutionContext context, long generation) {
            this.context = context;
            this.generation = generation;
        }
    }
    
    /**
     * 服务信息
     */
    @Data
    public static class ServiceInfo {
        private Long id;
        private String name;
        private String code;
        private Long componentId;
        private String gitRepo;
        private String branch;
        private String buildCommand;
        private String dockerfilePath;
        private String k8sDeployment;
        /** 服务对外端口（对应服务管理中的 service_port） */
        private Integer servicePort;
    }
    
    /**
     * 环境信息
     */
    @Data
    public static class EnvironmentInfo {
        private Long id;
        private String name;
        private Long clusterId;
        private String k8sNamespace;
        private String harborRegistry;
    }
    
    /**
     * 执行选项
     */
    @Data
    public static class ExecutionOptions {
        /**
         * 是否自动部署
         */
        private Boolean autoDeploy;
        
        /**
         * CI 构建节点 ID
         */
        private Long buildNodeId;

        /**
         * CD 部署节点 ID
         */
        private Long deployNodeId;

        /**
         * 节点ID（兼容旧字段）
         */
        private Long nodeId;
    }
}


