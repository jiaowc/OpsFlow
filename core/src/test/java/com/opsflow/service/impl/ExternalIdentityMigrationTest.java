package com.opsflow.service.impl;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** V7 外部身份表：subject 区分大小写，(provider, subject) 和 (user_id, provider) 都唯一 */
class ExternalIdentityMigrationTest {

    @Test
    void 外部身份表的列和唯一键() throws Exception {
        String sql;
        try (InputStream in = new ClassPathResource("db/migration/V7__two_factor_and_external_identity.sql").getInputStream()) {
            sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        assertTrue(sql.contains("`subject` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL"));
        assertTrue(sql.contains("UNIQUE KEY `uk_user_external_identity_subject` (`provider`, `subject`)"));
        assertTrue(sql.contains("UNIQUE KEY `uk_user_external_identity_user_provider` (`user_id`, `provider`)"));
        assertFalse(sql.contains("idx_user_external_identity_user"));
    }
}
