package com.opsflow.dao.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 流水线视图角色授权
 */
@Data
@TableName("pipeline_view_role")
public class PipelineViewRole {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long viewId;

    private Long roleId;

    private LocalDateTime createTime;
}
