package com.opsflow.api.dto;

import lombok.Data;
import java.util.List;
import java.util.Map;

/**
 * Pipeline DTO
 */
@Data
public class PipelineDTO {
    
    private Long id;
    
    private String name;
    
    private String description;
    
    /**
     * Pipeline步骤配置列表
     */
    private List<PipelineStepDTO> steps;
    
    /**
     * Pipeline 脚本内容（可选）
     */
    private String script;
    
    /**
     * Pipeline参数定义（参数名称 -> 参数描述）
     */
    private Map<String, String> parameterDefinitions;

    /**
     * CI 构建节点 ID
     */
    private Long buildNodeId;

    /**
     * CI 构建节点 ID 列表（支持多选）
     */
    private List<Long> buildNodeIds;

    /**
     * CD 部署节点 ID
     */
    private Long deployNodeId;

    /**
     * CI 构建节点名称（展示用）
     */
    private String buildNodeName;

    /**
     * CD 部署节点名称（展示用）
     */
    private String deployNodeName;
    
    /**
     * 状态：1-启用 0-禁用
     */
    private Integer status;
}

