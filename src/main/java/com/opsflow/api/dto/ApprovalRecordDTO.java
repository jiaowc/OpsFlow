package com.opsflow.api.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 审批记录 DTO
 */
@Data
public class ApprovalRecordDTO {

    private Long id;

    private Long taskId;

    private String taskNumber;

    private String taskName;

    private Long approvalFlowId;

    private Integer currentStep;

    private String approver;

    private String approverName;

    private String status;

    private String comment;

    private LocalDateTime approveTime;

    private LocalDateTime createTime;

    /**
     * 当前登录人是否可审批本步
     */
    private Boolean actionable;
}
