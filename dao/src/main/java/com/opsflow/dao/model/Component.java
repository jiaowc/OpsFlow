package com.opsflow.dao.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 组件实体
 */
@Data
@TableName("component")
public class Component {
    
    @TableId(type = IdType.AUTO)
    private Long id;
    
    /**
     * 组件名称，如：GitLab、Harbor、Jenkins等
     */
    private String name;
    
    /**
     * 组件类型，如：gitlab、harbor、jenkins等
     */
    private String type;
    
    /**
     * 访问地址
     */
    private String url;
    
    /**
     * 认证类型：api_key, username_password, token, oauth等
     */
    private String authType;
    
    /**
     * 认证信息（JSON格式存储，根据authType不同而不同）
     * 例如：
     * - api_key: {"apiKey": "xxx"}
     * - username_password: {"username": "xxx", "password": "xxx"}
     * - token: {"token": "xxx"}
     */
    private String authConfig;
    
    /**
     * 描述
     */
    private String description;
    
    private Integer status;
    
    private LocalDateTime createTime;
    
    private LocalDateTime updateTime;
}


