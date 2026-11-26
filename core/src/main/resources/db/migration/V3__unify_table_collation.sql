-- 统一排序规则：把 4 张历史遗留为 utf8mb4_0900_ai_ci 的表转成与其余 24 张表一致的 utf8mb4_unicode_ci。
--
-- 为什么要改：这 4 张表是在 MySQL 8 默认排序规则下建的，其余表显式指定了 utf8mb4_unicode_ci。
-- 两种排序规则的字符列一旦直接比较或关联（例如 audit_log.username = user.username），
-- MySQL 会报 "Illegal mix of collations"，或者被迫做隐式转换而用不上索引。
--
-- 影响：
--   - 每条语句会重建对应的表，数据不变；表很大时（主要是 audit_log）执行期间该表会被锁住，
--     数据量大的环境请安排在低峰期，或先关闭自动迁移（OPSFLOW_DB_MIGRATE=false）后手动执行 migrate.sh。
--   - 两种规则都不区分大小写和重音，日常查询结果不变。差别在于 utf8mb4_unicode_ci 比较时忽略末尾空格，
--     并且不区分大多数 emoji。只有 credential.name 上有字符列的唯一索引：如果已有两把钥匙的名称
--     仅在这两点上不同，下面第一条语句会因为唯一索引冲突（Duplicate entry）而失败。
--     它排在最前面，失败时 4 张表都还没有改动。恢复步骤：
--       1) 改掉其中一把钥匙的名称；
--       2) bash scripts/database/migrate.sh --repair   清除这次失败的记录；
--       3) 重新启动应用，或执行 bash scripts/database/migrate.sh。

ALTER TABLE `credential` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

ALTER TABLE `pipeline_webhook_trigger` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

ALTER TABLE `inbox_message` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

ALTER TABLE `audit_log` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
