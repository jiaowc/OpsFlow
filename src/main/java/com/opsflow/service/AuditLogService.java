package com.opsflow.service;

import com.opsflow.dao.model.AuditLog;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

public interface AuditLogService {

    /**
     * 写入审计日志；失败只记录告警，不影响业务请求
     */
    void record(AuditLog log);

    /**
     * 分页查询，返回 records / total / page / size
     */
    Map<String, Object> page(String username, String module, String keyword, Boolean success,
                             LocalDateTime start, LocalDateTime end, int page, int size);

    List<String> listModules();
}
