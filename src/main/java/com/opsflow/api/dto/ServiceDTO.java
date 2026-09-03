package com.opsflow.api.dto;

import lombok.Data;

/**
 * 服务DTO
 */
@Data
public class ServiceDTO {
    
    private Long id;
    
    private String name;

    /**
     * 所属项目，用于服务分类与筛选
     */
    private String projectName;

    /**
     * 内部标识（由服务名称自动生成，不在表单中维护）
     */
    private String code;
    
    private String gitRepo;

    /**
     * 关联 Git 组件 ID
     */
    private Long componentId;

    /**
     * 关联 Git 组件名称（展示）
     */
    private String componentName;

    /**
     * 仓库路径
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

    private Integer status;
}


