package com.opsflow.api.dto;

import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 流水线阶段视图（某 Job 的构建历史 + 阶段信息）
 */
@Data
public class PipelineViewDTO {

    private String jobName;

    private String taskName;

    private String branch;

    private String serviceName;

    private String envName;

    private String pipelineTemplateName;

    private String buildNodeDisplay;

    private String deployNodeDisplay;

    private Long highlightJobId;

    private String currentStatus;

    private List<BuildHistoryDTO> buildHistory;

    private Integer totalBuilds;

    private Integer currentPage;

    private Integer pageSize;

    private Integer totalPages;

    private Map<String, Long> averageStageTimes;

    private Map<String, String> averageStageTimesText;

    private Long averageFullRunTime;

    private String averageFullRunTimeText;
}
