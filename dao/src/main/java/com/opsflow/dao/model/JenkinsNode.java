package com.opsflow.dao.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 节点实体（支持SSH连接）
 */
@Data
@TableName("jenkins_node")
public class JenkinsNode {
    
    @TableId(type = IdType.AUTO)
    private Long id;
    
    private String name;
    
    private String label;
    
    /**
     * SSH主机地址
     */
    private String host;
    
    /**
     * SSH端口（默认22）
     */
    private Integer port;
    
    /**
     * 认证类型：password, private_key
     */
    private String authType;
    
    /**
     * SSH用户名
     */
    private String username;
    
    /**
     * SSH密码（当authType为password时使用）
     */
    private String password;
    
    /**
     * SSH私钥（当authType为private_key时使用）
     */
    private String privateKey;
    
    /**
     * SSH私钥密码（可选，当私钥有密码时使用）
     */
    private String privateKeyPassphrase;
    
    /**
     * 节点类型：build, deploy
     */
    private String nodeType;
    
    private String status;
    
    private String description;
    
    private LocalDateTime createTime;
    
    private LocalDateTime updateTime;
}


