package com.opsflow.dao.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 上线任务实体
 */
@Data
@TableName("deploy_task")
public class DeployTask {
    
    @TableId(type = IdType.AUTO)
    private Long id;
    
    private String taskNumber;
    
    /**
     * 任务名称
     */
    private String taskName;
    
    /**
     * 上线模块列表，JSON格式存储，例如：["user-service:v1.0.0", "order-service:v2.0.0"]
     * 格式：服务名称:版本号（镜像名称:tag）
     */
    private String deployModules;
    
    /**
     * 部署环境列表，JSON格式存储，例如：[1, 2, 3] 表示环境ID列表
     */
    private String deployEnvs;
    
    private Long approvalFlowId;
    
    /**
     * 审批状态：pending, approved, rejected
     */
    private String approvalStatus;
    
    /**
     * 任务状态：pending, building, deploying, success, failed, cancelled
     */
    private String taskStatus;
    
    private String description;
    
    private Long creatorId;
    
    private String creatorName;
    
    private LocalDateTime createTime;
    
    private LocalDateTime updateTime;
    
    private LocalDateTime deployTime;
}

