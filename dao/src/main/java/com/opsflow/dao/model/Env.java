package com.opsflow.dao.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 环境实体
 */
@Data
@TableName("env")
public class Env {
    
    @TableId(type = IdType.AUTO)
    private Long id;
    
    private String name;

    private Long clusterId;
    
    private String k8sCluster;
    
    private String k8sNamespace;
    
    private Integer status;
    
    private LocalDateTime createTime;
    
    private LocalDateTime updateTime;
}



