package com.opsflow.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.opsflow.dao.mapper.AuditLogMapper;
import com.opsflow.dao.model.AuditLog;
import com.opsflow.service.AuditLogService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Slf4j
@Service
public class AuditLogServiceImpl implements AuditLogService {

    private static final int MAX_PAGE_SIZE = 200;

    @Autowired
    private AuditLogMapper auditLogMapper;

    @Override
    public void record(AuditLog auditLog) {
        try {
            if (auditLog.getCreateTime() == null) {
                auditLog.setCreateTime(LocalDateTime.now());
            }
            auditLogMapper.insert(auditLog);
        } catch (Exception e) {
            log.warn("写入审计日志失败: {} {} - {}", auditLog.getHttpMethod(), auditLog.getPath(), e.getMessage());
        }
    }

    @Override
    public Map<String, Object> page(String username, String module, String keyword, Boolean success,
                                    LocalDateTime start, LocalDateTime end, int page, int size) {
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);

        LambdaQueryWrapper<AuditLog> query = new LambdaQueryWrapper<AuditLog>()
                .like(StringUtils.hasText(username), AuditLog::getUsername, username)
                .eq(StringUtils.hasText(module), AuditLog::getModule, module)
                .eq(success != null, AuditLog::getSuccess, Boolean.TRUE.equals(success) ? 1 : 0)
                .ge(start != null, AuditLog::getCreateTime, start)
                .le(end != null, AuditLog::getCreateTime, end);
        if (StringUtils.hasText(keyword)) {
            query.and(w -> w.like(AuditLog::getAction, keyword)
                    .or().like(AuditLog::getPath, keyword)
                    .or().like(AuditLog::getTargetId, keyword)
                    .or().like(AuditLog::getRequestSummary, keyword));
        }

        Long total = auditLogMapper.selectCount(query);
        query.orderByDesc(AuditLog::getId)
                .last("LIMIT " + ((safePage - 1) * safeSize) + ", " + safeSize);
        List<AuditLog> records = auditLogMapper.selectList(query);

        Map<String, Object> result = new HashMap<>();
        result.put("records", records);
        result.put("total", total);
        result.put("page", safePage);
        result.put("size", safeSize);
        return result;
    }

    @Override
    public List<String> listModules() {
        QueryWrapper<AuditLog> query = new QueryWrapper<AuditLog>().select("DISTINCT module").isNotNull("module");
        return auditLogMapper.selectObjs(query).stream()
                .filter(Objects::nonNull)
                .map(String::valueOf)
                .sorted()
                .collect(Collectors.toList());
    }
}
