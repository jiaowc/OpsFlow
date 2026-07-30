package com.opsflow.dao.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Pipeline 模版（Dockerfile / Deployment / Service）
 */
@Data
@TableName("pipeline_template")
public class PipelineTemplate {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String name;

    /**
     * dockerfile / deployment / service
     */
    private String type;

    private String description;

    private String content;

    private String baseImage;

    private Long envId;

    private String serviceType;

    private Integer status;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
