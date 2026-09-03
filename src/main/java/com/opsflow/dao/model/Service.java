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

    /** 所属项目，用于服务分类 */
    private String projectName;
    
    private String code;
    
    private String gitRepo;

    /**
     * 关联 Git 组件（GitLab/GitHub）
     */
    private Long componentId;

    /**
     * 仓库路径（相对组件地址或完整 URL）
     */
    private String gitRepoPath;

    /**
     * Git 类型：branch-分支 tag-标签
     */
    private String gitType;
    
    private String defaultBranch;

    /**
     * 服务类型：backend-后端 frontend-前端 lib-库
     */
    private String serviceType;

    /**
     * 对外提供服务端口
     */
    private Integer servicePort;
    
    private String k8sDeployment;
    
    private String k8sNamespace;
    
    private String dockerfilePath;
    
    private String buildCommand;
    
    private Long ownerUserId;
    
    private Integer status;
    
    private LocalDateTime createTime;
    
    private LocalDateTime updateTime;
}



