package com.opsflow.dao.model;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 集群实体
 */
@Data
@TableName("cluster")
public class Cluster {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String name;

    private String server;

    private Integer status;

    private String description;

    /**
     * 关联钥匙串 ID（kubeconfig）
     */
    @TableField(updateStrategy = FieldStrategy.IGNORED)
    private Long credentialId;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
