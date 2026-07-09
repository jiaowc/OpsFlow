package com.opsflow.dao.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 钥匙串凭证实体
 */
@Data
@TableName("credential")
public class Credential {

    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 钥匙名称
     */
    private String name;

    /**
     * 凭证类型：username_password/token/api_key/oauth/kubeconfig/ssh_password/ssh_key
     */
    private String credentialType;

    /**
     * 凭证配置 JSON
     */
    private String configData;

    private String description;

    private Integer status;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
