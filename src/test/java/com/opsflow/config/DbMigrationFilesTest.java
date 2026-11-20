package com.opsflow.config;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.util.StreamUtils;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 守护 Flyway 版本文件：命名、版本连续，以及两个基线文件不被混入不该有的内容。
 * 迁移本身的执行在真实 MySQL 上验证，这里只做不依赖数据库的静态检查。
 */
class DbMigrationFilesTest {

    private static final Pattern FILE_NAME = Pattern.compile("V(\\d+)__[a-z0-9_]+\\.sql");
    private static final Pattern INSERT_TABLE = Pattern.compile("(?m)^INSERT INTO `(\\w+)`");

    private static TreeMap<Integer, Resource> migrations() throws Exception {
        TreeMap<Integer, Resource> byVersion = new TreeMap<>();
        for (Resource resource : new PathMatchingResourcePatternResolver().getResources("classpath:db/migration/*")) {
            String name = resource.getFilename();
            Matcher matcher = FILE_NAME.matcher(name == null ? "" : name);
            assertTrue(matcher.matches(), "版本文件命名应为 V<序号>__<小写英文描述>.sql，实际: " + name);
            int version = Integer.parseInt(matcher.group(1));
            assertFalse(byVersion.containsKey(version), "版本号重复: " + version);
            byVersion.put(version, resource);
        }
        return byVersion;
    }

    private static String read(Resource resource) throws Exception {
        return StreamUtils.copyToString(resource.getInputStream(), StandardCharsets.UTF_8);
    }

    @Test
    void versionsAreContinuousFromOne() throws Exception {
        List<Integer> versions = new ArrayList<>(migrations().keySet());
        for (int i = 0; i < versions.size(); i++) {
            assertEquals(i + 1, versions.get(i), "版本号应从 1 开始连续，不能跳号: " + versions);
        }
        assertTrue(versions.size() >= Integer.parseInt(DbMigrations.BASELINE_VERSION),
                "基线版本 " + DbMigrations.BASELINE_VERSION + " 对应的文件必须存在");
    }

    @Test
    void baselineSchemaOnlyCreatesTables() throws Exception {
        String sql = read(migrations().get(1));
        Matcher tables = Pattern.compile("(?m)^CREATE TABLE `(\\w+)`").matcher(sql);
        int count = 0;
        while (tables.find()) {
            count++;
        }
        assertEquals(28, count, "V1 是已发布的基线，不能增删表；表结构变更请新增版本文件");
        assertFalse(Pattern.compile("(?m)^(INSERT|UPDATE|DELETE|DROP|ALTER|USE|CREATE DATABASE)\\b").matcher(sql).find(),
                "V1 只应包含建表语句");
        assertFalse(sql.contains("AUTO_INCREMENT="), "V1 不应带自增计数（那是数据，不是结构）");
    }

    @Test
    void baselineDataContainsOnlyProductDefaults() throws Exception {
        String sql = read(migrations().get(2));
        Set<String> allowed = new HashSet<>(Arrays.asList("role", "permission", "role_permission",
                "user", "user_role", "pipeline_step_def", "pipeline_template", "pipeline"));
        Matcher inserts = INSERT_TABLE.matcher(sql);
        Set<String> actual = new HashSet<>();
        while (inserts.find()) {
            actual.add(inserts.group(1));
        }
        assertEquals(allowed, actual, "V2 只带产品默认数据，不能混入集群/环境/节点/服务/钥匙串等环境相关的表");
    }

    @Test
    void baselineDataHasExactlyOneAccountWithoutLegacyHash() throws Exception {
        String sql = read(migrations().get(2));
        Matcher users = Pattern.compile("(?m)^INSERT INTO `user` .* VALUES \\(\\d+,'([^']*)','([^']*)'").matcher(sql);
        List<String> names = new ArrayList<>();
        while (users.find()) {
            names.add(users.group(1));
            assertTrue(users.group(2).startsWith("$2"), "初始账号的密码必须是 BCrypt 哈希");
        }
        assertEquals(Arrays.asList("admin"), names, "默认数据里只能有初始管理员一个账号");
        assertFalse(Pattern.compile("'[0-9a-f]{32}'").matcher(sql).find(), "不应出现 MD5 形态的值");
        assertFalse(sql.contains("enc:v1:"), "不应带任何加密凭据");
    }

    @Test
    void defaultPipelinesAreNotBoundToNodes() throws Exception {
        String sql = read(migrations().get(2));
        Matcher pipelines = Pattern.compile("(?m)^INSERT INTO `pipeline` \\(([^)]*)\\) VALUES \\((.*)\\);$").matcher(sql);
        int count = 0;
        while (pipelines.find()) {
            count++;
            String tail = pipelines.group(2);
            // 列顺序固定为 ...,build_node_ids,deploy_node_id,status,create_time,update_time
            assertTrue(tail.matches(".*,NULL,NULL,\\d+,'[^']*','[^']*'$"),
                    "默认流水线不能绑定具体的构建/部署节点（新环境里这些节点不存在）");
        }
        assertEquals(5, count);
    }

    @Test
    void tablesCreatedAfterBaselineDeclareTheProjectCollation() throws Exception {
        // 基线之后新建的表必须显式写排序规则，不能依赖 MySQL 默认值（V3 就是为了收拾这个遗留问题）
        Pattern createTable = Pattern.compile("(?s)CREATE TABLE[^;]*;");
        for (java.util.Map.Entry<Integer, Resource> entry : migrations().entrySet()) {
            if (entry.getKey() <= Integer.parseInt(DbMigrations.BASELINE_VERSION)) {
                continue;
            }
            Matcher tables = createTable.matcher(read(entry.getValue()));
            while (tables.find()) {
                assertTrue(tables.group().contains("COLLATE=utf8mb4_unicode_ci"),
                        entry.getValue().getFilename() + " 里的建表语句需要显式写 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci");
            }
        }
    }

    @Test
    void collationMigrationCoversExactlyTheLegacyTables() throws Exception {
        String baseline = read(migrations().get(1));
        Set<String> legacy = new HashSet<>();
        Matcher tables = Pattern.compile("(?s)CREATE TABLE `(\\w+)` \\((.*?)\\n\\) ENGINE=InnoDB([^;]*);").matcher(baseline);
        while (tables.find()) {
            if (!tables.group(3).contains("COLLATE=utf8mb4_unicode_ci")) {
                legacy.add(tables.group(1));
            }
        }
        Set<String> converted = new HashSet<>();
        Matcher alters = Pattern.compile("(?m)^ALTER TABLE `(\\w+)` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;")
                .matcher(read(migrations().get(3)));
        while (alters.find()) {
            converted.add(alters.group(1));
        }
        assertEquals(legacy, converted, "V3 应该正好覆盖基线里排序规则不是 utf8mb4_unicode_ci 的表");
        assertEquals(4, converted.size());
    }
}
