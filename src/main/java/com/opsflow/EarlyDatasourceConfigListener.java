package com.opsflow;

import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.boot.context.event.ApplicationPreparedEvent;
import org.springframework.boot.logging.DeferredLog;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.util.Arrays;

/**
 * 打印数据源配置：环境准备时先打一次，配置全部加载后再打一次最终值。
 */
public class EarlyDatasourceConfigListener implements ApplicationListener<ApplicationEvent> {

    private final DeferredLog log = new DeferredLog();
    private boolean printedEarly;
    private boolean printedFinal;

    @Override
    public void onApplicationEvent(ApplicationEvent event) {
        if (event instanceof ApplicationEnvironmentPreparedEvent) {
            printDatasourceConfig(
                ((ApplicationEnvironmentPreparedEvent) event).getEnvironment(),
                "启动早期(profile 文档可能尚未完全合并)",
                false);
            return;
        }
        if (event instanceof ApplicationPreparedEvent) {
            printDatasourceConfig(
                ((ApplicationPreparedEvent) event).getApplicationContext().getEnvironment(),
                "配置加载完成(最终生效)",
                true);
            log.switchTo(EarlyDatasourceConfigListener.class);
        }
    }

    private void printDatasourceConfig(ConfigurableEnvironment environment, String stage, boolean finalStage) {
        if (finalStage) {
            if (printedFinal) {
                return;
            }
            printedFinal = true;
        } else {
            if (printedEarly) {
                return;
            }
            printedEarly = true;
        }

        String[] activeProfiles = environment.getActiveProfiles();
        String activeProfileText = activeProfiles.length == 0 ? "(default)" : Arrays.toString(activeProfiles);
        String configuredActive = maskText(environment.getProperty("spring.profiles.active"));
        String url = maskJdbcUrl(environment.getProperty("spring.datasource.url"));
        String username = maskText(environment.getProperty("spring.datasource.username"));
        String driver = maskText(environment.getProperty("spring.datasource.driver-class-name"));
        String configFiles = resolveConfigFiles();

        String line1 = "[OpsFlow] " + stage + ": activeProfiles=" + activeProfileText
            + ", spring.profiles.active=" + configuredActive;
        String line2 = "[OpsFlow] " + stage + " 数据库配置: url=" + url
            + ", username=" + username
            + ", driver=" + driver;
        String line3 = "[OpsFlow] 可见配置文件: " + configFiles;

        System.out.println(line1);
        System.out.println(line2);
        System.out.println(line3);

        log.info(line1);
        log.info(line2);
        log.info(line3);
    }

    private String resolveConfigFiles() {
        try {
            PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
            StringBuilder sb = new StringBuilder();
            for (String pattern : new String[]{
                "classpath*:/application.yml",
                "classpath*:/application-*.yml",
                "classpath*:/application.properties",
                "file:./config/application.yml",
                "file:./config/application-*.yml"
            }) {
                Resource[] resources = resolver.getResources(pattern);
                for (Resource resource : resources) {
                    if (!resource.exists()) {
                        continue;
                    }
                    if (sb.length() > 0) {
                        sb.append(", ");
                    }
                    try {
                        sb.append(resource.getURL());
                    } catch (Exception e) {
                        sb.append(resource.getDescription());
                    }
                }
            }
            return sb.length() == 0 ? "(none)" : sb.toString();
        } catch (Exception e) {
            return "resolve-failed: " + e.getMessage();
        }
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
