package com.opsflow.api.dto;

import lombok.Data;

/**
 * Pipeline 模版 DTO
 */
@Data
public class PipelineTemplateDTO {

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

    /**
     * 关联环境名称（展示用）
     */
    private String envName;

    private String serviceType;

    private Integer status;
}
