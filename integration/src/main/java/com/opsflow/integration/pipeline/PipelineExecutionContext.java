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
     * 服务信息
     */
    @Data
    public static class ServiceInfo {
        private Long id;
        private String name;
        private String code;
        private String gitRepo;
        private String branch;
        private String buildCommand;
        private String dockerfilePath;
        private String k8sDeployment;
    }
    
    /**
     * 环境信息
     */
    @Data
    public static class EnvironmentInfo {
        private Long id;
        private String name;
        private String k8sNamespace;
        private String harborProject;
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
         * 节点ID（可选）
         */
        private Long nodeId;
    }
}


