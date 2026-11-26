package com.opsflow.config;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;

import java.util.List;

/**
 * 命令行执行数据库迁移（不启动应用），供 {@code scripts/database/migrate.sh} 调用：
 * <pre>
 *   java -cp opsflow.jar -Dloader.main=com.opsflow.config.DbMigrateCli \
 *        org.springframework.boot.loader.PropertiesLauncher [migrate|info|repair]
 * </pre>
 * 连接信息从环境变量读取：OPSFLOW_MIGRATE_URL / OPSFLOW_MIGRATE_USER / OPSFLOW_MIGRATE_PASSWORD
 * （密码不走命令行参数）。与应用启动时的迁移共用 {@link DbMigrations} 的配置和检查。
 */
public final class DbMigrateCli {

    private DbMigrateCli() {
    }

    public static void main(String[] args) {
        quietLogging();
        String command = args.length > 0 ? args[0] : "migrate";
        String url = requireEnv("OPSFLOW_MIGRATE_URL");
        String user = requireEnv("OPSFLOW_MIGRATE_USER");
        String password = System.getenv("OPSFLOW_MIGRATE_PASSWORD");
        try {
            Flyway flyway = DbMigrations.flyway(url, user, password == null ? "" : password);
            if ("info".equals(command)) {
                printInfo(flyway);
            } else if ("migrate".equals(command)) {
                List<String> executed = DbMigrations.migrateOrVerify(flyway, true);
                System.out.println(executed.isEmpty() ? "已是最新版本，没有需要执行的迁移" : "已执行: " + executed);
            } else if ("repair".equals(command)) {
                List<String> cleared = DbMigrations.repair(flyway);
                System.out.println(cleared.isEmpty()
                        ? "没有失败的迁移记录，无需修复"
                        : "已清除失败记录: " + cleared + "，现在可以重新执行迁移");
            } else {
                System.err.println("未知命令: " + command + "（可用: migrate | info | repair）");
                System.exit(2);
            }
        } catch (RuntimeException e) {
            System.err.println("迁移失败: " + e.getMessage());
            System.exit(1);
        }
    }

    private static void printInfo(Flyway flyway) {
        DbMigrations.assertSafeToBaseline(flyway);
        System.out.println("已记录的版本:");
        MigrationInfo[] applied = flyway.info().applied();
        if (applied.length == 0) {
            System.out.println("  （无：这个库还没有迁移历史）");
        }
        for (MigrationInfo info : applied) {
            System.out.printf("  %-6s %-40s %s%s%n", info.getVersion(), info.getDescription(), info.getInstalledOn(),
                    info.getState() != null && info.getState().isFailed() ? "  【执行失败】" : "");
        }
        List<String> failed = DbMigrations.failedVersions(flyway);
        if (!failed.isEmpty()) {
            System.out.println("有执行失败的版本: " + failed);
            System.out.println("  处理导致失败的原因后，执行 migrate.sh --repair 清除失败记录，再重新迁移");
        }
        List<String> pending = DbMigrations.pendingVersions(flyway);
        System.out.println(pending.isEmpty() ? "待执行: 无" : "待执行:");
        for (String version : pending) {
            System.out.println("  " + version);
        }
    }

    /**
     * 命令行模式没有 Spring Boot 的日志初始化，logback 默认会打出全部 DEBUG；
     * 这里只保留 Flyway 的执行过程（INFO）和其他组件的告警。
     */
    private static void quietLogging() {
        org.slf4j.ILoggerFactory factory = org.slf4j.LoggerFactory.getILoggerFactory();
        if (factory instanceof ch.qos.logback.classic.LoggerContext) {
            ch.qos.logback.classic.LoggerContext context = (ch.qos.logback.classic.LoggerContext) factory;
            context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).setLevel(ch.qos.logback.classic.Level.WARN);
            context.getLogger("org.flywaydb.core.internal.command").setLevel(ch.qos.logback.classic.Level.INFO);
        }
    }

    private static String requireEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.trim().isEmpty()) {
            System.err.println("缺少环境变量 " + name);
            System.exit(2);
        }
        return value;
    }
}
