package com.opsflow.dao.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

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
     * 候选审批人（多人任一审批即可），格式 ",user1,user2,"；为空时按 approver 匹配
     */
    private String approverCandidates;

    public static final String CANDIDATE_SEPARATOR = ",";

    public static String joinCandidates(List<String> usernames) {
        return CANDIDATE_SEPARATOR + String.join(CANDIDATE_SEPARATOR, usernames) + CANDIDATE_SEPARATOR;
    }

    public List<String> listApproverCandidates() {
        if (approverCandidates == null || approverCandidates.trim().isEmpty()) {
            return Collections.emptyList();
        }
        List<String> result = new ArrayList<>();
        for (String name : approverCandidates.split(CANDIDATE_SEPARATOR)) {
            if (!name.trim().isEmpty()) {
                result.add(name.trim());
            }
        }
        return result;
    }

    /**
     * 飞书审批卡片消息 ID（用于更新卡片）
     */
    private String feishuMessageId;
    
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










