package com.opsflow.dao.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 构建任务实体
 */
@Data
@TableName("build_job")
public class BuildJob {
    
    @TableId(type = IdType.AUTO)
    private Long id;
    
    private String jobNumber;

    /**
     * 任务名称（用户自定义）
     */
    private String taskName;
    
    private Long serviceId;
    
    private Long envId;
    
    private String branch;

    /**
     * Git 类型：branch / tag
     */
    private String gitType;
    
    private String commitId;
    
    private Integer buildNumber;

    private String buildNode;
    
    private String imageTag;
    
    private String imageFullName;
    
    private String status;
    
    private String buildLogUrl;
    
    private String errorMessage;
    
    private Long creatorId;
    
    private LocalDateTime startTime;
    
    private LocalDateTime endTime;
    
    private LocalDateTime createTime;
    
    private LocalDateTime updateTime;
    
    /**
     * Pipeline模板ID
     */
    private Long pipelineTemplateId;
    
    /**
     * 构建参数（JSON格式存储）
     * 包含：ENV, node, Service_Name, Branch, Code_Path, Harbor, Port, Replicas, 
     * LIMIT_CPU, LIMIT_MEM, REQ_CPU, REQ_MEM 等
     */
    private String buildParameters;
}


