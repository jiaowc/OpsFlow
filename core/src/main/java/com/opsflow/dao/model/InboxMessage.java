package com.opsflow.dao.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("inbox_message")
public class InboxMessage {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String username;

    private String title;

    private String content;

    /**
     * 消息类型：approval 等
     */
    private String msgType;

    private String bizType;

    private String bizId;

    private String linkPath;

    /**
     * 0 未读 / 1 已读
     */
    private Integer readFlag;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
