package com.opsflow.api.dto;

import lombok.Data;
import java.util.List;

/**
 * Pipeline 阶段 DTO
 */
@Data
public class PipelineStageDTO {
    
    /**
     * 阶段ID
     */
    private String id;
    
    /**
     * 阶段名称
     */
    private String name;
    
    /**
     * 状态（SUCCESS, FAILURE, UNSTABLE, ABORTED, IN_PROGRESS, NOT_EXECUTED）
     */
    private String status;
    
    /**
     * 持续时间（毫秒）
     */
    private Long durationMillis;
    
    /**
     * 持续时间文本（格式化，如 "7s", "98ms"）
     */
    private String durationText;
    
    /**
     * 开始时间戳（毫秒）
     */
    private Long startTimeMillis;
    
    /**
     * 子阶段列表
     */
    private List<PipelineStageDTO> subStages;
}

