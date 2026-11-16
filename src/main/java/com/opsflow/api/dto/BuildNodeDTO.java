package com.opsflow.api.dto;

import lombok.Data;

/**
 * 构建/部署节点 DTO
 */
@Data
public class BuildNodeDTO {

    private Long id;

    private String name;

    private String label;

    private String host;

    private Integer port;

    private String authType;

    private String username;

    private String password;

    private String privateKey;

    private String privateKeyPassphrase;

    private String nodeType;

    private String workDir;

    private String status;

    private String description;

    /**
     * SSH 主机指纹（只读，响应用；请求中的值会被忽略）
     */
    private String hostKeyFingerprint;

    /**
     * 是否已保存密码 / 私钥（只读，响应中不再回传密钥原文）
     */
    private Boolean hasPassword;

    private Boolean hasPrivateKey;
}
