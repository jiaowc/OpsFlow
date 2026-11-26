package com.opsflow.config;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.configuration.FluentConfiguration;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * 数据库迁移（Flyway）的统一入口：应用启动（{@link DatabaseMigrationConfig}）和命令行
 * （{@link DbMigrateCli}）共用同一份配置和同一套检查，避免两边行为不一致。
 * <p>
 * 版本文件在 {@code classpath:db/migration}：V1 表结构、V2 默认数据是基线，之后每个变更一个新版本，只执行一次。
 * </p>
 */
public final class DbMigrations {

    /** 基线版本：已有业务表但没有迁移历史的库，视为已经处于这个版本（V1 结构 + V2 默认数据都不再执行） */
    public static final String BASELINE_VERSION = "2";

    /**
     * 给「已有库」打基线前必须存在的结构，取自最近几次 legacy 迁移。
     * 缺任何一项说明这个库还没追平到基线，直接打基线会把缺表缺列的库误标成最新。
     * 每项为 {表名, 列名}，列名为 null 表示只检查表。
     */
    private static final String[][] BASELINE_SENTINELS = {
            {"project", null},
            {"audit_log", null},
            {"pipeline_webhook_trigger", null},
            {"pipeline_template", null},
            {"build_node", "host_key"},
            {"deploy_task", "project_id"},
            {"service", "project_id"},
    };

    private DbMigrations() {
    }

    public static Flyway flyway(DataSource dataSource) {
        return configure(Flyway.configure().dataSource(dataSource)).load();
    }

    public static Flyway flyway(String url, String username, String password) {
        return configure(Flyway.configure().dataSource(url, username, password)).load();
    }

    private static FluentConfiguration configure(FluentConfiguration configuration) {
        return configuration
                .locations("classpath:db/migration")
                .baselineOnMigrate(true)
                .baselineVersion(BASELINE_VERSION)
                .baselineDescription("已有库：结构与默认数据视为已到位")
                // 默认模版、步骤配置里有大量 ${serviceName} 这类平台变量，不能被 Flyway 当成它自己的占位符
                .placeholderReplacement(false)
                // 版本文件一旦发布就不该再改；被改过时启动报错，而不是静默继续
                .validateOnMigrate(true);
    }

    /**
     * 执行迁移，或只检查。
     *
     * @param migrate true：执行所有未执行的版本；false：不改库，有未执行的版本就抛异常
     * @return 本次执行（或待执行）的版本号
     */
    public static List<String> migrateOrVerify(Flyway flyway, boolean migrate) {
        assertSafeToBaseline(flyway);
        assertNoFailedMigration(flyway);
        List<String> pending = pendingVersions(flyway);
        if (!migrate) {
            if (!pending.isEmpty()) {
                throw new IllegalStateException("数据库有未执行的迁移 " + pending
                        + "，而自动迁移已关闭（OPSFLOW_DB_MIGRATE=false）。"
                        + "请先执行 bash scripts/database/migrate.sh，再启动应用");
            }
            return pending;
        }
        flyway.migrate();
        return pending;
    }

    /**
     * 上一次迁移执行到一半失败时（MySQL 的表结构变更不能回滚），历史表里会留下一条失败记录，
     * Flyway 在它被清掉之前拒绝继续。这里提前给出可操作的提示，而不是让使用者去读 Flyway 的英文报错。
     */
    static void assertNoFailedMigration(Flyway flyway) {
        List<String> failed = failedVersions(flyway);
        if (!failed.isEmpty()) {
            throw new IllegalStateException("上一次数据库迁移执行失败，停在 " + failed + "。"
                    + "请先处理导致失败的原因（见当时的报错和对应版本文件开头的说明），"
                    + "然后执行 bash scripts/database/migrate.sh --repair 清除失败记录，再重新启动或执行迁移");
        }
    }

    /** 执行失败、尚未修复的版本 */
    public static List<String> failedVersions(Flyway flyway) {
        List<String> versions = new ArrayList<>();
        if (!historyTableExists(flyway)) {
            return versions;
        }
        for (MigrationInfo info : flyway.info().all()) {
            if (info.getState() != null && info.getState().isFailed() && info.getVersion() != null) {
                versions.add(info.getVersion().getVersion() + " " + info.getDescription());
            }
        }
        return versions;
    }

    /**
     * 清除历史表里的失败记录，之后可以重新执行那个版本。
     * 只在确认导致失败的原因已处理、且失败的版本没有留下做了一半的改动时使用。
     *
     * @return 被清除的失败版本
     */
    public static List<String> repair(Flyway flyway) {
        List<String> failed = failedVersions(flyway);
        flyway.repair();
        return failed;
    }

    /** 尚未执行的版本号（空库时包含基线版本；已有库打基线后不含） */
    public static List<String> pendingVersions(Flyway flyway) {
        List<String> versions = new ArrayList<>();
        boolean unbaselinedExistingDb = !historyTableExists(flyway) && businessTableCount(flyway) > 0;
        for (MigrationInfo info : flyway.info().pending()) {
            if (info.getVersion() == null) {
                continue;
            }
            // 已有库在迁移时会被打上基线，基线及更早的版本不会真的执行
            if (unbaselinedExistingDb
                    && info.getVersion().compareTo(org.flywaydb.core.api.MigrationVersion.fromVersion(BASELINE_VERSION)) <= 0) {
                continue;
            }
            versions.add(info.getVersion().getVersion() + " " + info.getDescription());
        }
        if (unbaselinedExistingDb) {
            versions.add(0, "基线 " + BASELINE_VERSION + "（记录迁移历史，不改业务表）");
        }
        return versions;
    }

    /**
     * 已有业务表、但没有迁移历史的库，在打基线前确认它已经追平到基线版本。
     */
    static void assertSafeToBaseline(Flyway flyway) {
        if (historyTableExists(flyway) || businessTableCount(flyway) == 0) {
            return;
        }
        List<String> missing = new ArrayList<>();
        for (String[] sentinel : BASELINE_SENTINELS) {
            if (!exists(flyway, sentinel[0], sentinel[1])) {
                missing.add(sentinel[1] == null ? "表 " + sentinel[0] : "列 " + sentinel[0] + "." + sentinel[1]);
            }
        }
        if (!missing.isEmpty()) {
            throw new IllegalStateException("这个数据库的结构落后于迁移基线（缺少 " + String.join("、", missing) + "）。"
                    + "请先执行一次旧的迁移脚本把它追平：bash scripts/database/legacy/run_migrations.sh，然后再启动应用");
        }
    }

    private static boolean historyTableExists(Flyway flyway) {
        return exists(flyway, flyway.getConfiguration().getTable(), null);
    }

    private static int businessTableCount(Flyway flyway) {
        String sql = "SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME <> ?";
        try (Connection connection = flyway.getConfiguration().getDataSource().getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, flyway.getConfiguration().getTable());
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("检查数据库现有表失败: " + e.getMessage(), e);
        }
    }

    private static boolean exists(Flyway flyway, String table, String column) {
        String sql = column == null
                ? "SELECT 1 FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ?"
                : "SELECT 1 FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND COLUMN_NAME = ?";
        try (Connection connection = flyway.getConfiguration().getDataSource().getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, table);
            if (column != null) {
                statement.setString(2, column);
            }
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("检查数据库结构失败: " + e.getMessage(), e);
        }
    }
}
