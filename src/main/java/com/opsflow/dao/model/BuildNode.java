package com.opsflow.dao.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 构建/部署节点（SSH 远程执行）
 */
@Data
@TableName("build_node")
public class BuildNode {

    @TableId(type = IdType.AUTO)
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
