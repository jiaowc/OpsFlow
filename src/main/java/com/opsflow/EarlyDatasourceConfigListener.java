package com.opsflow;

import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.boot.context.event.ApplicationPreparedEvent;
import org.springframework.boot.logging.DeferredLog;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.env.ConfigurableEnvironment;

import java.util.Arrays;

/**
 * 在数据源初始化前输出即将使用的数据库配置。
 * <p>
 * {@link ApplicationEnvironmentPreparedEvent} 发生时日志系统可能尚未就绪，
 * 因此使用 {@link DeferredLog} 缓存日志，并在 {@link ApplicationPreparedEvent} 时刷出。
 * </p>
 */
public class EarlyDatasourceConfigListener implements ApplicationListener<ApplicationEvent> {

    private final DeferredLog log = new DeferredLog();
    private boolean printed;

    @Override
    public void onApplicationEvent(ApplicationEvent event) {
        if (event instanceof ApplicationEnvironmentPreparedEvent) {
            printDatasourceConfig(((ApplicationEnvironmentPreparedEvent) event).getEnvironment());
            return;
        }
        if (event instanceof ApplicationPreparedEvent) {
            // 日志系统已就绪，把缓存的日志刷到正式日志
            log.switchTo(EarlyDatasourceConfigListener.class);
        }
    }

    private void printDatasourceConfig(ConfigurableEnvironment environment) {
        if (printed) {
            return;
        }
        printed = true;

        String[] activeProfiles = environment.getActiveProfiles();
        String activeProfileText = activeProfiles.length == 0 ? "(default)" : Arrays.toString(activeProfiles);
        String url = maskJdbcUrl(environment.getProperty("spring.datasource.url"));
        String username = maskText(environment.getProperty("spring.datasource.username"));
        String driver = maskText(environment.getProperty("spring.datasource.driver-class-name"));

        // System.out 兜底：即使日志系统未初始化也能在容器 stdout 看到
        System.out.println("[OpsFlow] 启动阶段环境信息: activeProfiles=" + activeProfileText);
        System.out.println("[OpsFlow] 启动阶段数据库配置: url=" + url
            + ", username=" + username
            + ", driver=" + driver);

        log.info("启动阶段环境信息: activeProfiles={}", activeProfileText);
        log.info("启动阶段数据库配置: url={}, username={}, driver={}", url, username, driver);
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
