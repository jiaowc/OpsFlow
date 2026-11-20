package com.opsflow.dao.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 审批流实体
 */
@Data
@TableName("approval_flow")
public class ApprovalFlow {
    
    @TableId(type = IdType.AUTO)
    private Long id;
    
    private String name;
    
    private String description;
    
    /**
     * 审批步骤JSON，格式：[{"step":1,"approver":"user1","required":true},...]
     */
    private String steps;
    
    private Integer status;
    
    private LocalDateTime createTime;
    
    private LocalDateTime updateTime;
}


