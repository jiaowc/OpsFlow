package com.opsflow.api.dto;

import lombok.Data;
import java.util.List;

/**
 * 审批流DTO
 */
@Data
public class ApprovalFlowDTO {
    
    private Long id;
    
    private String name;
    
    private String description;
    
    private List<ApprovalStep> steps;
    
    private Integer status;
    
    @Data
    public static class ApprovalStep {
        private Integer step;
        private String approver;
        private String approverName;
        /** 候选审批人（用户名）：多于一人时任一人审批即可，approver 不再生效 */
        private List<String> approvers;
        private Boolean required;
        private String status; // pending, approved, rejected
    }
}


