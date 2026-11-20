package com.opsflow.dao.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.opsflow.common.crypto.EncryptedStringTypeHandler;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 构建/部署节点（SSH 远程执行）
 */
@Data
@TableName(value = "build_node", autoResultMap = true)
public class BuildNode {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String name;

    private String label;

    private String host;

    private Integer port;

    private String authType;

    private String username;

    /** 以下三个字段落库加密（AES-GCM），读出自动解密 */
    @TableField(typeHandler = EncryptedStringTypeHandler.class)
    private String password;

    @TableField(typeHandler = EncryptedStringTypeHandler.class)
    private String privateKey;

    @TableField(typeHandler = EncryptedStringTypeHandler.class)
    private String privateKeyPassphrase;

    /**
     * SSH 主机公钥（"类型 Base64"），首次连接时记录（TOFU），之后用于校验
     */
    private String hostKey;

    /**
     * SSH 主机指纹（SHA256:xxx），仅用于展示
     */
    private String hostKeyFingerprint;

    /**
     * 节点类型：build, deploy
     */
    private String nodeType;

    /**
     * 远程工作目录根路径
     */
    private String workDir;

    private String status;

    private String description;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
