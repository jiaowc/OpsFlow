package com.opsflow.api.dto;

import lombok.Data;
import java.util.Map;

/**
 * Pipeline步骤DTO
 */
@Data
public class PipelineStepDTO {
    
    /**
     * 步骤类型：checkout, build, deploy, clean, notify
     */
    private String stepType;
    
    /**
     * 步骤名称
     */
    private String stepName;
    
    /**
     * 是否启用
     */
    private Boolean enabled;
    
    /**
     * 节点选择方式：auto, manual, specific
     * - auto: 自动选择
     * - manual: 手动选择（运行时选择）
     * - specific: 指定节点ID
     */
    private String nodeSelection;
    
    /**
     * 指定的节点ID（当nodeSelection为specific时使用）
     */
    private Long nodeId;
    
    /**
     * 步骤参数（键值对）
     */
    private Map<String, String> parameters;
    
    /**
     * 步骤顺序
     */
    private Integer order;
}

