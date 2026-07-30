package com.opsflow.integration.pipeline;

import lombok.Data;

import java.util.Map;

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
     * 向当前阶段追加实时日志
     */
    public void emitLog(String chunk) {
        if (stageListener == null || currentStepOrder == null || chunk == null || chunk.isEmpty()) {
            return;
        }
        stageListener.onStageLog(currentStepOrder, chunk);
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


