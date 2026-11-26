package com.opsflow.api.dto;

import lombok.Data;

/**
 * 要发出的站内信内容，与业务无关；由调用方组装标题、正文和跳转地址。
 * <p>{@code bizType} + {@code bizId} 用于事后按业务对象批量标记已读（如审批完成后清掉待办）。</p>
 */
@Data
public class InboxMessageDraft {

    private String title;
    private String content;
    /** 点击后跳转的页面路径，如 /tasks */
    private String linkPath;
    /** 消息分类，如 approval、pipeline */
    private String msgType;
    private String bizType;
    private String bizId;
}
