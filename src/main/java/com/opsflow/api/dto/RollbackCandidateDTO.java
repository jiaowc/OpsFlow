package com.opsflow.api.dto;

import lombok.Data;

/**
 * 回滚可选的历史成功版本
 */
@Data
public class RollbackCandidateDTO {

    private Long jobId;

    private String jobNumber;

    private String imageFullName;

    private String imageTag;

    private String createTimeText;

    /** 是否为当前线上版本（最近一次成功部署） */
    private Boolean current;
}
