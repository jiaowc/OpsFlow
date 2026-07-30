package com.opsflow.api.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class InboxMessageDTO {
    private Long id;
    private String username;
    private String title;
    private String content;
    private String msgType;
    private String bizType;
    private String bizId;
    private String linkPath;
    private Integer readFlag;
    private LocalDateTime createTime;
    private Boolean actionable;
}
