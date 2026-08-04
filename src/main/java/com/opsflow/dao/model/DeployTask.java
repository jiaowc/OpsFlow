package com.opsflow.dao.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 上线任务数据库实体（deploy_task 表）。
 * <p>
 * 生命周期：创建 → 审批（{@link #approvalStatus}）→ 审批通过后触发 CD（{@link #taskStatus}）→
 * 各模块 BuildJob 执行完毕汇总为 success/failed。
 * 关联 {@link Pipeline}（CD 模版）、{@link ApprovalFlow}（可选）及 {@link BuildJob}（按模块拆分）。
 * </p>
 */
@Data
@TableName("deploy_task")
public class DeployTask {
    
    @TableId(type = IdType.AUTO)
    private Long id;
    
    /** 任务编号，格式 TASK-yyyyMMddHHmmss */
    private String taskNumber;
    
    /**
     * 任务名称（用户自定义）
     */
    private String taskName;
    
    /**
     * 上线模块列表，JSON 数组存储。
     * 格式：完整或相对镜像地址，如 {@code ["registry/project/service:tag", ...]}
     */
    private String deployModules;
    
    /**
     * 部署环境 ID 列表，JSON 数组存储，如 {@code [1, 2, 3]}。
     * 可与 clusterId + k8sNamespace 配合用于环境解析。
     */
    private String deployEnvs;

    /**
     * 上线目标集群 ID
     */
    private Long clusterId;

    /**
     * 上线目标 K8s 命名空间
     */
    private String k8sNamespace;
    
    /** 关联审批流 ID；为 null 时表示无需审批 */
    private Long approvalFlowId;

    /**
     * 审批结果通知渠道，逗号分隔。
     * 合法值：inbox（站内信）、feishu（飞书），如 {@code inbox,feishu}
     */
    private String notifyChannels;

    /**
     * CD 流水线模版 ID（pipeline 表，类型须为 cd）
     */
    private Long pipelineTemplateId;

    /**
     * 多模块部署策略：serial（串行，默认）/ parallel（有限并行）
     */
    private String deployMode;

    /**
     * 并行部署并发数；serial 时为 1
     */
    private Integer deployParallelism;
    
    /**
     * 审批状态。
     * 合法值：pending（审批中）、approved（已通过）、rejected（已驳回）、none（无需审批）
     */
    private String approvalStatus;
    
    /**
     * 任务执行状态。
     * 合法值：pending（待执行）、building、deploying（部署中）、success、failed、cancelled
     */
    private String taskStatus;

    /**
     * 是否锁定。锁定后不可编辑模块，需解锁后才能再改；发布前须先锁定。
     */
    private Integer locked;

    /** 锁定操作人用户名 */
    private String lockedBy;

    /** 锁定时间 */
    private LocalDateTime lockedAt;
    
    /** 任务描述或 CD 失败原因追加信息 */
    private String description;
    
    private Long creatorId;
    
    private String creatorName;
    
    private LocalDateTime createTime;
    
    private LocalDateTime updateTime;
    
    /** 首次触发 CD 部署的时间 */
    private LocalDateTime deployTime;
}
