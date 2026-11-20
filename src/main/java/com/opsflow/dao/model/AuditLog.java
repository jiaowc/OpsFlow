package com.opsflow.dao.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 操作审计日志：记录所有写操作（谁、何时、对什么、做了什么、结果）。
 */
@Data
@TableName("audit_log")
public class AuditLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private String username;

    private String module;

    private String action;

    private String httpMethod;

    private String path;

    private String targetId;

    /**
     * 请求参数摘要，敏感字段已脱敏
     */
    private String requestSummary;

    /**
     * 1 成功 / 0 失败
     */
    private Integer success;

    private Integer statusCode;

    private String errorMessage;

    private String clientIp;

    private Long durationMs;

    private LocalDateTime createTime;
}
