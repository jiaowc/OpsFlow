package com.opsflow.config;

import lombok.extern.slf4j.Slf4j;
import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationInitializer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 应用启动时的数据库迁移。
 * <ul>
 *   <li>默认（{@code OPSFLOW_DB_MIGRATE=true}）：启动时自动执行未执行的版本；空库会先建表并写入默认数据。</li>
 *   <li>{@code OPSFLOW_DB_MIGRATE=false}：不改库；有未执行的版本就拒绝启动，由运维先跑
 *       {@code scripts/database/migrate.sh}。适合对变更窗口有要求的生产环境。</li>
 * </ul>
 * 配置统一在 {@link DbMigrations}，这里自己声明 Flyway 相关 Bean，不使用 spring.flyway.* 配置项。
 */
@Slf4j
@Configuration
public class DatabaseMigrationConfig {

    private static final String INITIALIZER_BEAN = "flywayInitializer";

    @Value("${opsflow.db.migrate-on-startup:true}")
    private boolean migrateOnStartup;

    @Bean
    public Flyway flyway(DataSource dataSource) {
        return DbMigrations.flyway(dataSource);
    }

    @Bean(name = INITIALIZER_BEAN)
    public FlywayMigrationInitializer flywayInitializer(Flyway flyway) {
        return new FlywayMigrationInitializer(flyway, f -> {
            List<String> pending = DbMigrations.migrateOrVerify(f, migrateOnStartup);
            if (pending.isEmpty()) {
                log.info("数据库迁移：已是最新版本");
            } else {
                log.info("数据库迁移：已执行 {}", pending);
            }
        });
    }

    /**
     * 让 MyBatis 等迁移完成后再初始化。
     * <p>
     * Spring Boot 只会自动让 JdbcTemplate、JPA 这类它认识的组件等待数据库初始化，不认识 MyBatis；
     * 而有的 Bean 在 {@code @PostConstruct} 里就查库（如读取许可证）。不加这层依赖，空库上会先查后建表。
     * </p>
     */
    @Bean
    public static BeanFactoryPostProcessor mybatisWaitsForMigration() {
        return beanFactory -> {
            for (String name : Arrays.asList("sqlSessionFactory", "sqlSessionTemplate")) {
                if (!beanFactory.containsBeanDefinition(name)) {
                    continue;
                }
                BeanDefinition definition = beanFactory.getBeanDefinition(name);
                Set<String> dependsOn = new LinkedHashSet<>();
                if (definition.getDependsOn() != null) {
                    dependsOn.addAll(Arrays.asList(definition.getDependsOn()));
                }
                dependsOn.add(INITIALIZER_BEAN);
                definition.setDependsOn(dependsOn.toArray(new String[0]));
            }
        };
    }
}
