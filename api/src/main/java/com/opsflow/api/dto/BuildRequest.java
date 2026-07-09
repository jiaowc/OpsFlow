package com.opsflow.api.dto;

import lombok.Data;

/**
 * 构建请求DTO
 */
@Data
public class BuildRequest {
    
    /**
     * 任务名称
     */
    private String taskName;

    /**
     * 服务ID
     */
    private Long serviceId;
    
    /**
     * 环境ID
     */
    private Long envId;
    
    /**
     * Git分支或Tag
     */
    private String branch;

    /**
     * Git 类型：branch / tag
     */
    private String gitType;
    
    /**
     * Pipeline模板ID
     */
    private Long pipelineTemplateId;
    
    /**
     * 构建参数
     */
    private BuildParameters buildParameters;
    
    /**
     * 是否自动部署
     */
    private Boolean autoDeploy;
    
    /**
     * 构建参数类
     */
    @Data
    public static class BuildParameters {
        /**
         * 环境：dev, auto, uat, prod
         */
        private String ENV;
        
        /**
         * 构建节点ID
         */
        private Long node;
        
        /**
         * 服务名称（对应服务管理）
         */
        private String Service_Name;
        
        /**
         * 代码分支
         */
        private String Branch;
        
        /**
         * 代码地址
         */
        private String Code_Path;
        
        /**
         * Harbor地址URL
         */
        private String Harbor;
        
        /**
         * 服务端口
         */
        private Integer Port;
        
        /**
         * 副本数
         */
        private Integer Replicas;
        
        /**
         * CPU限制，如：500m
         */
        private String LIMIT_CPU;
        
        /**
         * 内存使用限制，如：512Mi
         */
        private String LIMIT_MEM;
        
        /**
         * 初始CPU使用分配，如：100m
         */
        private String REQ_CPU;
        
        /**
         * 初始内存分配，如：128Mi
         */
        private String REQ_MEM;
    }
}


