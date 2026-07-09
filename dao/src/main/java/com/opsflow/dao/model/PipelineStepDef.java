package com.opsflow.dao.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Pipeline 步骤定义（可复用）
 */
@Data
@TableName("pipeline_step_def")
public class PipelineStepDef {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String name;

    private String stepType;

    /**
     * 阶段：ci / cd
     */
    private String phase;

    private String description;

    /**
     * 自定义内容 JSON
     */
    private String contentConfig;

    private Integer status;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
