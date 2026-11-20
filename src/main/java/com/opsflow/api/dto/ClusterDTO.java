package com.opsflow.api.dto;

import lombok.Data;

/**
 * 集群 DTO
 */
@Data
public class ClusterDTO {

    private Long id;

    private String name;

    private String server;

    private Integer status;

    private String description;

    /**
     * 关联钥匙串 ID
     */
    private Long credentialId;

    /**
     * 关联钥匙串名称（展示用）
     */
    private String credentialName;
}
