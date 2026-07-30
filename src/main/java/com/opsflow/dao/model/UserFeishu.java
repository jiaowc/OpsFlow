package com.opsflow.dao.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 用户飞书信息实体
 */
@Data
@TableName("user_feishu")
public class UserFeishu {
    
    @TableId(type = IdType.AUTO)
    private Long id;
    
    /**
     * 系统用户ID
     */
    private Long userId;
    
    /**
     * 系统用户名
     */
    private String username;
    
    /**
     * 飞书用户ID
     */
    private String feishuUserId;
    
    /**
     * 飞书Open ID
     */
    private String feishuOpenId;
    
    /**
     * 飞书Union ID
     */
    private String feishuUnionId;
    
    /**
     * 飞书手机号
     */
    private String feishuMobile;
    
    /**
     * 飞书邮箱
     */
    private String feishuEmail;
    
    private LocalDateTime createTime;
    
    private LocalDateTime updateTime;
}










