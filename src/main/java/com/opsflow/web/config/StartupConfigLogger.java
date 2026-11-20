package com.opsflow.web.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Arrays;

/**
 * 应用启动完成后打印关键运行配置，便于排查环境与数据源加载问题。
 */
@Slf4j
@Component
public class StartupConfigLogger {

    private final Environment environment;
    private final DataSourceProperties dataSourceProperties;

    public StartupConfigLogger(Environment environment, DataSourceProperties dataSourceProperties) {
        this.environment = environment;
        this.dataSourceProperties = dataSourceProperties;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void logStartupConfig() {
        String[] activeProfiles = environment.getActiveProfiles();
        String activeProfileText = activeProfiles.length == 0 ? "(default)" : Arrays.toString(activeProfiles);

        log.info("当前激活环境: {}", activeProfileText);
        log.info("数据库连接信息: url={}, username={}, driver={}",
            maskJdbcUrl(dataSourceProperties.getUrl()),
            maskText(dataSourceProperties.getUsername()),
            maskText(dataSourceProperties.getDriverClassName()));
    }

    private String maskJdbcUrl(String url) {
        if (url == null || url.trim().isEmpty()) {
            return "(empty)";
        }
        int questionMark = url.indexOf('?');
        if (questionMark < 0) {
            return url;
        }
        return url.substring(0, questionMark) + "?...";
    }

    private String maskText(String value) {
        return value == null || value.trim().isEmpty() ? "(empty)" : value.trim();
    }
}
