package com.opsflow.api.dto;

import lombok.Data;

import java.util.Map;

/**
 * Pipeline 步骤定义 DTO
 */
@Data
public class PipelineStepDefDTO {

    private Long id;

    private String name;

    private String stepType;

    /**
     * 阶段：ci / cd
     */
    private String phase;

    private String description;

    /**
     * 自定义内容，如 dockerfileContent、buildCommand 等
     */
    private Map<String, String> contentConfig;

    private Integer status;
}
