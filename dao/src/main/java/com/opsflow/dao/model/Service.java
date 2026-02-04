package com.opsflow.dao.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 服务实体
 */
@Data
@TableName("service")
public class Service {
    
    @TableId(type = IdType.AUTO)
    private Long id;
    
    private String name;
    
    private String code;
    
    private String gitRepo;
    
    private String defaultBranch;
    
    private String k8sDeployment;
    
    private String k8sNamespace;
    
    private String dockerfilePath;
    
    private String buildCommand;
    
    private Long ownerUserId;
    
    private Integer status;
    
    private LocalDateTime createTime;
    
    private LocalDateTime updateTime;
}



