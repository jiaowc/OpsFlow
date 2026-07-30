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
    public static final String TYPE_PROD = "prod";
    public static final String TYPE_NON_PROD = "nonprod";
    
    @TableId(type = IdType.AUTO)
    private Long id;
    
    private String name;

    private Long clusterId;
    
    private String k8sCluster;
    
    private String k8sNamespace;

    /**
     * 环境类型：prod / nonprod
     */
    private String envType;
    
    private Integer status;
    
    private LocalDateTime createTime;
    
    private LocalDateTime updateTime;

    public boolean isProdEnv() {
        if (envType != null && !envType.trim().isEmpty()) {
            return TYPE_PROD.equalsIgnoreCase(envType.trim());
        }
        return "prod".equalsIgnoreCase(name);
    }
}



