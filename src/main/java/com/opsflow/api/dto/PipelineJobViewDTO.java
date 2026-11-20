package com.opsflow.api.dto;

import lombok.Data;

import java.util.List;

/**
 * 平台原生流水线任务视图
 */
@Data
public class PipelineJobViewDTO {

    private Long id;

    private String jobNumber;

    private String taskName;

    private String name;

    private Long serviceId;

    private String serviceName;

    private Long envId;

    private String envName;

    private String branch;

    /**
     * Git 类型：branch / tag
     */
    private String gitType;

    /**
     * Git 仓库地址
     */
    private String gitRepo;

    private String status;

    private Boolean building;

    private Long lastDuration;

    private String lastDurationText;

    private Long pipelineTemplateId;

    private String pipelineTemplateName;

    private String createTimeText;

    /**
     * 最近一次构建用户
     */
    private String creatorName;

    /**
     * CI 构建节点展示名
     */
    private String buildNodeDisplay;

    /**
     * CD 部署节点展示名
     */
    private String deployNodeDisplay;

    /**
     * 最近一次构建记录 ID（列表页阶段日志等）
     */
    private Long latestBuildId;

    /**
     * 最近一次构建的阶段（列表页缩略展示）
     */
    private List<PipelineStageDTO> latestStages;

    /**
     * 是否由上线任务自动创建的 CD 任务
     */
    private Boolean fromDeployTask;

    /**
     * 是否可编辑（非运行中，且非上线任务自动创建）
     */
    private Boolean editable;

    /**
     * 是否可删除（非运行中）
     */
    private Boolean deletable;

    /**
     * 代码推送到该分支时是否自动构建（Git Webhook）
     */
    private Boolean webhookEnabled;

    /**
     * 当前用户是否可操作（服务配置了负责人/成员时，仅他们与管理员可操作）
     */
    private Boolean operable;

    /**
     * CD 任务是否可手动重试
     */
    private Boolean retryable;

    /**
     * CD 任务不可操作时的原因（如等待前序模块）
     */
    private String blockedReason;

    /**
     * 是否可回滚（非运行中，且存在可回滚的历史成功镜像）
     */
    private Boolean rollbackable;

    /**
     * 是否生产环境（回滚时需二次确认）
     */
    private Boolean prodEnv;

    /**
     * 是否在排队等待执行线程
     */
    private Boolean queued;

    /**
     * 排队位置（从 1 开始），未排队为 0
     */
    private Integer queuePosition;
}
