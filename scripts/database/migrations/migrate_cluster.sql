-- 集群表及 env.cluster_id（可重复执行）

CREATE TABLE IF NOT EXISTS `cluster` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `name` varchar(100) NOT NULL COMMENT '集群名称',
  `server` varchar(255) COMMENT '集群地址/API Server',
  `status` tinyint DEFAULT 1 COMMENT '状态：1-启用 0-禁用',
  `description` varchar(500) COMMENT '描述',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_cluster_name` (`name`)
) COMMENT='集群配置表';

SET @dbname = DATABASE();
SET @tablename = 'env';
SET @columnname = 'cluster_id';
SET @preparedStatement = (SELECT IF(
  (
    SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = @dbname AND TABLE_NAME = @tablename AND COLUMN_NAME = @columnname
  ) > 0,
  'SELECT 1',
  CONCAT('ALTER TABLE `', @tablename, '` ADD COLUMN `', @columnname,
         '` bigint COMMENT ''关联集群ID'';')
));
PREPARE alterIfNotExists FROM @preparedStatement;
EXECUTE alterIfNotExists;
DEALLOCATE PREPARE alterIfNotExists;

-- 从 env.k8s_cluster 迁移历史数据（列存在时）
SET @hasK8sCluster = (
  SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
  WHERE TABLE_SCHEMA = @dbname AND TABLE_NAME = 'env' AND COLUMN_NAME = 'k8s_cluster'
);

SET @migrateClusters = IF(@hasK8sCluster > 0,
  'INSERT INTO `cluster` (`name`, `server`, `status`, `description`)
   SELECT DISTINCT `k8s_cluster`, `k8s_cluster`, 1, ''由环境配置自动迁移''
   FROM `env`
   WHERE `k8s_cluster` IS NOT NULL AND TRIM(`k8s_cluster`) <> ''''
     AND NOT EXISTS (
       SELECT 1 FROM `cluster` c WHERE c.`name` = `env`.`k8s_cluster`
     )',
  'SELECT 1'
);
PREPARE migrateClustersStmt FROM @migrateClusters;
EXECUTE migrateClustersStmt;
DEALLOCATE PREPARE migrateClustersStmt;

SET @linkEnvs = IF(@hasK8sCluster > 0,
  'UPDATE `env` e
   JOIN `cluster` c ON c.`name` = e.`k8s_cluster`
   SET e.`cluster_id` = c.`id`
   WHERE e.`cluster_id` IS NULL',
  'SELECT 1'
);
PREPARE linkEnvsStmt FROM @linkEnvs;
EXECUTE linkEnvsStmt;
DEALLOCATE PREPARE linkEnvsStmt;
