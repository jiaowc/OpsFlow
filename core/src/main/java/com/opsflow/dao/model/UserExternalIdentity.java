package com.opsflow.dao.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 外部身份（扩展提供的登录方式）和本地用户的对应关系，(provider, subject) 唯一 */
@Data
@TableName("user_external_identity")
public class UserExternalIdentity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    /** 登录方式标识，由扩展自己定义 */
    private String provider;

    /** 该登录方式下的用户 ID */
    private String subject;

    private String unionId;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
