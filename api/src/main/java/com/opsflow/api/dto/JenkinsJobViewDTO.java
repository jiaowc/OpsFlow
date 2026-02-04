package com.opsflow.api.dto;

import lombok.Data;
import java.util.Date;

/**
 * Jenkins 作业视图 DTO
 */
@Data
public class JenkinsJobViewDTO {
    
    /**
     * 作业名称
     */
    private String name;
    
    /**
     * 作业URL
     */
    private String url;
    
    /**
     * 状态（SUCCESS, FAILURE, UNSTABLE, ABORTED, BUILDING, UNKNOWN）
     */
    private String status;
    
    /**
     * 健康度图标（sun, cloud, storm等）
     */
    private String healthIcon;
    
    /**
     * 健康度描述
     */
    private String healthDescription;
    
    /**
     * 最后成功构建号
     */
    private Integer lastSuccessfulBuild;
    
    /**
     * 最后成功构建时间
     */
    private Date lastSuccessfulBuildTime;
    
    /**
     * 最后失败构建号
     */
    private Integer lastFailedBuild;
    
    /**
     * 最后失败构建时间
     */
    private Date lastFailedBuildTime;
    
    /**
     * 最后构建持续时间（毫秒）
     */
    private Long lastDuration;
    
    /**
     * 最后构建持续时间（格式化字符串，如 "8.9秒"）
     */
    private String lastDurationText;
    
    /**
     * 是否正在构建中
     */
    private Boolean building;
    
    /**
     * 构建队列位置（如果在队列中）
     */
    private Integer queuePosition;
}

