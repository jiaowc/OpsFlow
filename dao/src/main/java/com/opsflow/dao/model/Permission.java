package com.opsflow.dao.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 权限实体
 */
@Data
@TableName("permission")
public class Permission {
    
    @TableId(type = IdType.AUTO)
    private Long id;
    
    /**
     * 权限名称
     */
    private String name;
    
    /**
     * 权限代码
     */
    private String code;
    
    /**
     * 资源路径
     */
    private String resource;
    
    /**
     * HTTP方法（GET, POST, PUT, DELETE等）
     */
    private String method;
    
    /**
     * 权限描述
     */
    private String description;
    
    /**
     * 父权限ID（用于权限树）
     */
    private Long parentId;
    
    /**
     * 状态：1-启用 0-禁用
     */
    private Integer status;
    
    private LocalDateTime createTime;
    
    private LocalDateTime updateTime;
}


