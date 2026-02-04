package com.opsflow.dao.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * Pipeline模板实体
 */
@Data
@TableName("pipeline")
public class Pipeline {
    
    @TableId(type = IdType.AUTO)
    private Long id;
    
    /**
     * Pipeline名称
     */
    private String name;
    
    /**
     * Pipeline描述
     */
    private String description;
    
    /**
     * Pipeline步骤配置（JSON格式存储）
     * 包含：拉取代码、构建打包、部署、通知等步骤
     */
    private String stepsConfig;
    
    /**
     * Pipeline脚本内容（Jenkinsfile，可选）
     */
    private String script;
    
    /**
     * Jenkins Job名称模板
     */
    private String jenkinsJobTemplate;
    
    /**
     * Pipeline参数定义（JSON格式存储，参数名称 -> 参数描述）
     */
    private String parameterDefinitions;
    
    /**
     * 状态：1-启用 0-禁用
     */
    private Integer status;
    
    private LocalDateTime createTime;
    
    private LocalDateTime updateTime;
}

