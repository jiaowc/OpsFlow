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
        private Boolean required;
        private String status; // pending, approved, rejected
    }
}


