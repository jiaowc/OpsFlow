package com.opsflow.api.dto;

import lombok.Data;

/**
 * 节点环境检测项
 */
@Data
public class NodeEnvCheckItemDTO {

    private String key;

    private String name;

    /**
     * 是否必检项
     */
    private Boolean required;

    private Boolean passed;

    /**
     * 版本或输出摘要
     */
    private String version;

    private String message;
}
