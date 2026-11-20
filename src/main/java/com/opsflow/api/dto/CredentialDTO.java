package com.opsflow.api.dto;

import lombok.Data;

import java.util.Map;

/**
 * 钥匙串凭证 DTO
 */
@Data
public class CredentialDTO {

    private Long id;

    private String name;

    /**
     * username_password/token/api_key/oauth/kubeconfig/ssh_password/ssh_key
     */
    private String credentialType;

    private Map<String, String> configData;

    private String description;

    private Integer status;
}
