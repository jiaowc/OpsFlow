package com.opsflow.service;

/**
 * 通知相关开关（系统设置 → 通知设置）
 */
public interface NotifyConfigService {

    boolean isInboxEnabled();

    boolean isDefaultInbox();

    /** 流水线构建/发布结果是否给触发人发站内信（默认开） */
    boolean isPipelineInboxEnabled();
}
