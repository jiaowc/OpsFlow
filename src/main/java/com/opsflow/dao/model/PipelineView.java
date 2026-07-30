package com.opsflow.dao.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 流水线视图（按环境筛选任务，类似 Jenkins View）
 */
@Data
@TableName("pipeline_view")
public class PipelineView {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String name;

    private Long envId;

    private String description;

    private Integer sortOrder;

    private Integer status;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
