package com.opsflow.service;

import java.util.Map;

/**
 * 构建日志 / 构建记录 / 审计日志的保留清理（见 opsflow.retention.*）
 */
public interface BuildRetentionService {

    /**
     * 执行一次清理
     *
     * @param dryRun true 只统计将要清理的数量，不实际删除
     * @return 各类清理数量
     */
    Map<String, Object> runCleanup(boolean dryRun);
}
