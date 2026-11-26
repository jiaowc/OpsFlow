package com.opsflow.dao.model;

import lombok.Data;

/**
 * 流水线任务列表：按「任务组」聚合后的分页行（latest + anchor）。
 */
@Data
public class BuildJobGroupPageRow {
    /** 组内最新 Job ID（列表展示状态用） */
    private Long latestId;
    /** 组内最早 Job ID（任务锚点，用于阶段视图） */
    private Long anchorId;
}
