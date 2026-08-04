package com.opsflow.web.controller;

import com.opsflow.service.AppVersionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.sql.DataSource;
import java.sql.Connection;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 健康检查接口（免登录），供探活 / 负载均衡 / K8s 探针使用。
 */
@RestController
public class HealthController {

    private static final DateTimeFormatter TS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Autowired
    private DataSource dataSource;

    @Autowired
    private AppVersionService appVersionService;

    /**
     * 应用健康检查。
     * <p>
     * 返回整体状态及子项（应用进程、数据库连通性）。
     * 任一关键依赖不可用时 HTTP 503，否则 200。
     * </p>
     */
    @GetMapping({"/api/health", "/health"})
    public ResponseEntity<Map<String, Object>> health() {
        Map<String, Object> checks = new LinkedHashMap<>();
        checks.put("app", "UP");

        boolean dbOk = checkDatabase();
        checks.put("database", dbOk ? "UP" : "DOWN");

        boolean overallUp = dbOk;
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", overallUp ? "UP" : "DOWN");
        body.put("version", appVersionService.getVersion());
        body.put("checks", checks);
        body.put("timestamp", LocalDateTime.now().format(TS));

        return ResponseEntity
                .status(overallUp ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE)
                .body(body);
    }

    private boolean checkDatabase() {
        try (Connection conn = dataSource.getConnection()) {
            return conn.isValid(3);
        } catch (Exception e) {
            return false;
        }
    }
}
