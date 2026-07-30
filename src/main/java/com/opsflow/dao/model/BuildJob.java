package com.opsflow.dao.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 构建/部署任务数据库实体（build_job 表）。
 * <p>
 * 可由用户手动发起 CI，或由 {@link com.opsflow.service.DeployTaskCdService}
 * 在上线任务审批通过后按模块自动创建 CD 任务（deployTaskId 非空）。
 * 执行状态参见 {@link com.opsflow.common.constant.BuildStatus}。
 * </p>
 */
@Data
@TableName("build_job")
public class BuildJob {
    
    @TableId(type = IdType.AUTO)
    private Long id;
    
    /** 任务编号，系统生成 */
    private String jobNumber;

    /**
     * 任务显示名称
     */
    private String taskName;
    
    /** 关联服务 ID */
    private Long serviceId;
    
    /** 目标部署环境 ID */
    private Long envId;
    
    /** Git 分支名或镜像 tag（CD 场景下通常为版本号） */
    private String branch;

    /**
     * Git 引用类型。
     * 合法值：branch、tag
     */
    private String gitType;
    
    private String commitId;
    
    private Integer buildNumber;

    /** 实际执行节点标识 */
    private String buildNode;
    
    /** 镜像 tag */
    private String imageTag;
    
    /** 完整镜像地址 registry/project/service:tag */
    private String imageFullName;
    
    /**
     * 执行状态。
     * 合法值：pending、building、deploying、success、failed 等（见 BuildStatus）
     */
    private String status;
    
    private String buildLogUrl;
    
    private String errorMessage;
    
    private Long creatorId;
    
    private LocalDateTime startTime;
    
    private LocalDateTime endTime;
    
    private LocalDateTime createTime;
    
    private LocalDateTime updateTime;
    
    /**
     * 引用的流水线模版 ID
     */
    private Long pipelineTemplateId;
    
    /**
     * 构建/部署参数 JSON，含 imageFullName、servicePort、namespace 等流水线变量
     */
    private String buildParameters;

    /**
     * 关联上线任务 ID；由审批通过后的 CD 流程创建时不为空
     */
    private Long deployTaskId;
}
