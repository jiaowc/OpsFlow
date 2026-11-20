package com.opsflow.dao.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 项目：多团队隔离的边界，服务、流水线、上线任务都归属于项目
 */
@Data
@TableName("project")
public class Project {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String name;

    private String description;

    private Long ownerUserId;

    /**
     * 1 启用 / 0 禁用
     */
    private Integer status;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
