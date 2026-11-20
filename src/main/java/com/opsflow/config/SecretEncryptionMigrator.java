package com.opsflow.config;

import com.opsflow.common.crypto.SecretCipher;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 启动时把历史明文凭据加密回写（幂等：已带 enc:v1: 前缀的跳过）。
 * 只记录处理条数，不输出任何字段内容。
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class SecretEncryptionMigrator implements ApplicationRunner {

    /** 表名 → 需要加密的列（均为代码内常量，不来自外部输入） */
    private static final String[][] TARGETS = {
            {"credential", "config_data"},
            {"component", "auth_config"},
            {"build_node", "password"},
            {"build_node", "private_key"},
            {"build_node", "private_key_passphrase"},
    };

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Override
    public void run(ApplicationArguments args) {
        SecretCipher.init();
        int total = 0;
        for (String[] target : TARGETS) {
            try {
                total += migrateColumn(target[0], target[1]);
            } catch (Exception e) {
                log.error("加密历史凭据失败: {}.{} - {}", target[0], target[1], e.getMessage());
            }
        }
        if (total > 0) {
            log.info("已将 {} 个历史明文凭据字段加密存储", total);
        }
    }

    private int migrateColumn(String table, String column) {
        String select = "SELECT id, `" + column + "` AS v FROM `" + table + "` WHERE `" + column + "` IS NOT NULL AND `"
                + column + "` <> '' AND `" + column + "` NOT LIKE 'enc:v1:%'";
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(select);
        int count = 0;
        String update = "UPDATE `" + table + "` SET `" + column + "` = ? WHERE id = ? AND `" + column + "` = ?";
        for (Map<String, Object> row : rows) {
            Object id = row.get("id");
            String plain = (String) row.get("v");
            // 带原值做乐观条件，避免覆盖期间被用户修改的新值
            count += jdbcTemplate.update(update, SecretCipher.encrypt(plain), id, plain);
        }
        if (count > 0) {
            log.info("已加密 {}.{}: {} 行", table, column, count);
        }
        return count;
    }
}
