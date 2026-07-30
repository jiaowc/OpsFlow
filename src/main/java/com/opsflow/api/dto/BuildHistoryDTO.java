package com.opsflow.api.dto;

import lombok.Data;
import java.util.Date;
import java.util.List;

/**
 * 构建历史 DTO
 */
@Data
public class BuildHistoryDTO {
    
    /**
     * 构建号
     */
    private Integer buildNumber;
    
    /**
     * 构建URL
     */
    private String buildUrl;
    
    /**
     * 构建状态
     */
    private String status;
    
    /**
     * 构建时间
     */
    private Date buildTime;
    
    /**
     * 构建时间文本（格式化）
     */
    private String buildTimeText;
    
     /**
     * 是否有变更
     */
    private Boolean hasChanges;
    
    /**
     * 变更数量
     */
    private Integer changeCount;
    
    /**
     * 阶段列表
     */
    private List<PipelineStageDTO> stages;
    
    /**
     * 总持续时间（毫秒）
     */
    private Long totalDuration;
    
    /**
     * 总持续时间文本
     */
    private String totalDurationText;

    /**
     * 平台任务 ID
     */
    private Long jobId;

    /**
     * 平台任务编号
     */
    private String jobNumber;

    /**
     * 是否运行中
     */
    private Boolean building;

    /**
     * 错误信息
     */
    private String errorMessage;

    /**
     * 本次构建实际 CI 节点
     */
    private String buildNodeDisplay;

    /**
     * 本次构建实际 CD 节点
     */
    private String deployNodeDisplay;

    /**
     * 构建用户 ID
     */
    private Long creatorId;

    /**
     * 构建用户名称
     */
    private String creatorName;
}

