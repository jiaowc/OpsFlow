package com.opsflow.api.dto;

import lombok.Data;

import java.util.List;

/**
 * 流水线任务分页结果
 */
@Data
public class PipelineJobPageResult {

    private List<PipelineJobViewDTO> items;

    private long total;

    private int page;

    private int pageSize;

    private int totalPages;
}
