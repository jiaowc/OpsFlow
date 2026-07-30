package com.opsflow.api.dto;

import lombok.Data;
import java.util.List;
import java.util.Map;

/**
 * 流水线模版 API 传输对象。
 * <p>
 * 与 {@link com.opsflow.dao.model.Pipeline} 实体互转，步骤与参数定义以结构化对象传输，
 * 持久化时序列化为 JSON。上线任务创建时从 CD 类型模版中选择。
 * </p>
 */
@Data
public class PipelineDTO {
    
    private Long id;
    
    /** 模版名称 */
    private String name;

    /**
     * 流水线类型：ci / cd / cicd
     */
    private String pipelineType;
    
    private String description;
    
    /**
     * 步骤配置列表（checkout、build、deploy 等）
     */
    private List<PipelineStepDTO> steps;
    
    /**
     * 可选自定义脚本
     */
    private String script;
    
    /**
     * 参数定义：参数名 → 描述或默认值说明
     */
    private Map<String, String> parameterDefinitions;

    /**
     * 主 CI 构建节点 ID（兼容单选）
     */
    private Long buildNodeId;

    /**
     * CI 构建节点 ID 列表（多选）
     */
    private List<Long> buildNodeIds;

    /**
     * CD 部署节点 ID
     */
    private Long deployNodeId;

    /**
     * CI 构建节点名称（查询时填充，仅展示）
     */
    private String buildNodeName;

    /**
     * CD 部署节点名称（查询时填充，仅展示）
     */
    private String deployNodeName;
    
    /**
     * 模版状态：1-启用 0-禁用
     */
    private Integer status;
}
