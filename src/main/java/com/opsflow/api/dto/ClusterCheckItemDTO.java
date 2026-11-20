package com.opsflow.api.dto;

import lombok.Data;

/**
 * 集群连通性检测项
 */
@Data
public class ClusterCheckItemDTO {

    private String key;

    private String name;

    private Boolean required;

    private Boolean passed;

    private String detail;

    private String message;
}
