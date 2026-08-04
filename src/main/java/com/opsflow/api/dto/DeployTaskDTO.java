package com.opsflow.api.dto;

import lombok.Data;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 上线任务 API 传输对象。
 * <p>
 * 用于创建/查询/更新上线任务；创建时提交配置，查询时附带解析后的模块详情、
 * 环境/集群/审批流名称及关联的 CD 构建任务 ID 列表。
 * </p>
 */
@Data
public class DeployTaskDTO {
    
    private Long id;
    
    /** 任务编号 */
    private String taskNumber;
    
    /**
     * 任务名称
     */
    private String taskName;
    
    /**
     * 上线模块镜像地址列表，格式：registry/project/service:tag
     */
    private List<String> deployModules;

    /**
     * 上线模块详情（含服务端口、归属人），供详情页展示及 CD 模版变量注入
     */
    private List<DeployModuleDetailDTO> deployModuleDetails;

    /**
     * 带归属信息的上线模块列表（编辑/协作时优先使用）
     */
    private List<DeployModuleItemDTO> deployModuleItems;
    
    /**
     * 部署环境 ID 列表
     */
    private List<Long> deployEnvIds;
    
    /**
     * 部署环境名称列表（查询时填充）
     */
    private List<String> deployEnvNames;

    /**
     * 上线集群 ID
     */
    private Long clusterId;

    /**
     * 上线集群名称（查询时填充）
     */
    private String clusterName;

    /**
     * 上线 K8s 命名空间
     */
    private String k8sNamespace;
    
    /** 审批流 ID */
    private Long approvalFlowId;
    
    /** 审批流名称（查询时填充） */
    private String approvalFlowName;

    /**
     * 审批通知渠道列表。
     * 合法值：inbox、feishu
     */
    private List<String> notifyChannels;
    
    /**
     * CD 流水线模版 ID（类型须为 cd）
     */
    private Long pipelineTemplateId;

    /**
     * CD 流水线模版名称（查询时填充）
     */
    private String pipelineTemplateName;

    /**
     * 多模块部署策略：serial（默认）/ parallel
     */
    private String deployMode;

    /**
     * 并行部署并发数；serial 时为 1，parallel 默认 3，最大 20
     */
    private Integer deployParallelism;

    /**
     * 该任务触发的 CD 构建任务 ID 列表（按模块拆分）
     */
    private List<Long> buildJobIds;
    
    /**
     * 传递给流水线执行的自定义参数（键值对）
     */
    private java.util.Map<String, String> pipelineParameters;
    
    /**
     * 审批状态：pending / approved / rejected / none
     */
    private String approvalStatus;
    
    /**
     * 任务状态：pending / deploying / success / failed 等
     */
    private String taskStatus;

    /**
     * 是否锁定（1=已锁定）
     */
    private Integer locked;

    private String lockedBy;

    private LocalDateTime lockedAt;

    /** 当前用户是否可编辑（未锁定且有 create 权限） */
    private Boolean canEdit;

    /** 当前用户是否可锁定 */
    private Boolean canLock;

    /** 当前用户是否可解锁 */
    private Boolean canUnlock;

    /** 当前用户是否可提交审批（已锁定且尚未进入审批） */
    private Boolean canSubmitApproval;

    /**
     * 当前用户是否可点击「发布」启动 CD（须审批已通过）。
     * 未通过时前端仍展示按钮但置灰。
     */
    private Boolean canPublish;

    /** 是否展示发布按钮（有 create 权限的用户可见，未审批通过时置灰） */
    private Boolean showPublish;
    
    private String description;
    
    private Long creatorId;
    
    private String creatorName;
    
    /**
     * 审批记录列表（详情查询时返回）
     */
    private List<ApprovalRecordDTO> approvalRecords;
    
    private LocalDateTime createTime;
    
    private LocalDateTime updateTime;
    
    /** 首次触发 CD 的时间 */
    private LocalDateTime deployTime;
}
