package com.opsflow.dao.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 审批记录实体
 */
@Data
@TableName("approval_record")
public class ApprovalRecord {
    
    @TableId(type = IdType.AUTO)
    private Long id;
    
    /**
     * 任务ID（deploy_task.id）
     */
    private Long taskId;
    
    /**
     * 任务编号
     */
    private String taskNumber;
    
    /**
     * 审批流ID
     */
    private Long approvalFlowId;
    
    /**
     * 当前审批步骤
     */
    private Integer currentStep;
    
    /**
     * 审批人（用户名或用户ID）
     */
    private String approver;
    
    /**
     * 审批人姓名
     */
    private String approverName;
    
    /**
     * 审批人飞书用户ID
     */
    private String approverFeishuId;
    
    /**
     * 审批状态：pending, approved, rejected, cancelled
     */
    private String status;
    
    /**
     * 审批意见
     */
    private String comment;
    
    /**
     * 审批时间
     */
    private LocalDateTime approveTime;
    
    private LocalDateTime createTime;
    
    private LocalDateTime updateTime;
}










