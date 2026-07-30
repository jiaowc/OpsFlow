package com.opsflow.service;

/**
 * 通知相关开关（系统设置 → 通知设置）
 */
public interface NotifyConfigService {

    boolean isInboxEnabled();

    boolean isDefaultInbox();

    boolean isDefaultFeishu();
}
