package com.opsflow.dao.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.opsflow.common.crypto.EncryptedStringTypeHandler;
import lombok.Data;

import java.time.LocalDateTime;

/** 用户两步验证绑定（每个用户一条，确认绑定后才写入） */
@Data
@TableName(value = "user_two_factor", autoResultMap = true)
public class UserTwoFactor {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    /** Base32 密钥，库里加密存储 */
    @TableField(typeHandler = EncryptedStringTypeHandler.class)
    private String secret;

    private Integer enabled;

    private LocalDateTime confirmedTime;

    /** 最后一次用过的时间窗序号，防重放 */
    private Long lastUsedStep;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
