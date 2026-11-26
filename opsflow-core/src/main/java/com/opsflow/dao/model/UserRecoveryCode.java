package com.opsflow.dao.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 两步验证恢复码：只存哈希，used_time 非空表示已用 */
@Data
@TableName("user_recovery_code")
public class UserRecoveryCode {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private String codeHash;

    private LocalDateTime usedTime;

    private LocalDateTime createTime;
}
