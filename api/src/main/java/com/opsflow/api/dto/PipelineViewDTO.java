package com.opsflow.api.dto;

import lombok.Data;
import java.util.List;
import java.util.Map;

/**
 * Pipeline 视图 DTO
 */
@Data
public class PipelineViewDTO {
    
    /**
     * 作业名称
     */
    private String jobName;
    
    /**
     * 当前构建状态
     */
    private String currentStatus;
    
    /**
     * 平均阶段时间（阶段名称 -> 平均时间毫秒）
     */
    private Map<String, Long> averageStageTimes;
    
    /**
     * 平均阶段时间文本（阶段名称 -> 格式化时间文本）
     */
    private Map<String, String> averageStageTimesText;
    
    /**
     * 完整运行平均时间（毫秒）
     */
    private Long averageFullRunTime;
    
    /**
     * 完整运行平均时间文本
     */
    private String averageFullRunTimeText;
    
    /**
     * 构建历史列表
     */
    private List<BuildHistoryDTO> buildHistory;
    
    /**
     * 总构建数（用于分页）
     */
    private Integer totalBuilds;
    
    /**
     * 当前页码
     */
    private Integer currentPage;
    
    /**
     * 每页大小
     */
    private Integer pageSize;
    
    /**
     * 总页数
     */
    private Integer totalPages;
}

