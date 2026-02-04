package com.opsflow.dao.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 系统配置实体
 */
@Data
@TableName("system_config")
public class SystemConfig {
    
    @TableId(type = IdType.AUTO)
    private Long id;
    
    /**
     * 配置类型：ldap, harbor, maven, jenkins
     */
    private String configType;
    
    private String configKey;
    
    private String configValue;
    
    private String description;
    
    private LocalDateTime createTime;
    
    private LocalDateTime updateTime;
}


