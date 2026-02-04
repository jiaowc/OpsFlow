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
     * Pipeline脚本内容（Jenkinsfile，可选）
     */
    private String script;
    
    /**
     * Jenkins Job名称模板
     */
    private String jenkinsJobTemplate;
    
    /**
     * Pipeline参数定义（参数名称 -> 参数描述）
     */
    private Map<String, String> parameterDefinitions;
    
    /**
     * 状态：1-启用 0-禁用
     */
    private Integer status;
}

