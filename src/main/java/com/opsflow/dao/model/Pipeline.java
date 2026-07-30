package com.opsflow.dao.model;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 流水线模版数据库实体（pipeline 表）。
 * <p>
 * 定义 CI/CD 步骤、参数与执行节点，被 {@link BuildJob} 引用后由流水线引擎执行。
 * 上线任务仅允许关联类型为 {@code cd} 的模版。
 * </p>
 */
@Data
@TableName("pipeline")
public class Pipeline {
    
    @TableId(type = IdType.AUTO)
    private Long id;
    
    /**
     * 模版名称
     */
    private String name;

    /**
     * 流水线类型。
     * 合法值：ci（构建）、cd（部署）、cicd（全链路）
     */
    private String pipelineType;
    
    /**
     * 模版描述
     */
    private String description;
    
    /**
     * 步骤配置 JSON，包含 checkout、build、deploy、notify 等步骤定义
     */
    private String stepsConfig;
    
    /**
     * 可选的自定义脚本内容
     */
    private String script;
    
    /**
     * 参数定义 JSON，键为参数名、值为参数描述/默认值说明
     */
    private String parameterDefinitions;

    /**
     * 主 CI 构建节点 ID（兼容单选，与 buildNodeIds 首项同步）
     */
    @TableField(updateStrategy = FieldStrategy.IGNORED)
    private Long buildNodeId;

    /**
     * CI 构建节点 ID 列表，逗号分隔，支持多节点候选调度
     */
    @TableField(updateStrategy = FieldStrategy.IGNORED)
    private String buildNodeIds;

    /**
     * CD 部署节点 ID，执行 deploy 类步骤时使用
     */
    @TableField(updateStrategy = FieldStrategy.IGNORED)
    private Long deployNodeId;
    
    /**
     * 模版状态：1-启用 0-禁用
     */
    private Integer status;
    
    private LocalDateTime createTime;
    
    private LocalDateTime updateTime;
}
