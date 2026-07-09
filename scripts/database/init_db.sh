#!/bin/bash
# 从 config/application.yml 读取数据库配置并初始化 opsflow 库

set -e

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
CONFIG_FILE="${PROJECT_DIR}/config/application.yml"
MIGRATIONS_DIR="${PROJECT_DIR}/scripts/database/migrations"
SEEDS_DIR="${PROJECT_DIR}/scripts/database/seeds"

if [ ! -f "$CONFIG_FILE" ]; then
  echo "配置文件不存在: $CONFIG_FILE"
  exit 1
fi

DB_URL=$(grep -E '^\s*url:' "$CONFIG_FILE" | head -1 | sed 's/.*url:[[:space:]]*//')
DB_USER=$(grep -E '^\s*username:' "$CONFIG_FILE" | head -1 | awk '{print $2}')
DB_PASS=$(grep -E '^\s*password:' "$CONFIG_FILE" | head -1 | sed 's/.*password:[[:space:]]*//')
DB_NAME=$(echo "$DB_URL" | sed -E 's|.*:[0-9]+/([^?]+).*|\1|')

if [ -z "$DB_NAME" ] || [ -z "$DB_USER" ]; then
  echo "无法从配置文件解析数据库信息"
  exit 1
fi

echo "初始化数据库: $DB_NAME"

mysql -h127.0.0.1 -u"$DB_USER" -p"$DB_PASS" --default-character-set=utf8mb4 -e "CREATE DATABASE IF NOT EXISTS \`$DB_NAME\` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"
mysql -h127.0.0.1 -u"$DB_USER" -p"$DB_PASS" --default-character-set=utf8mb4 "$DB_NAME" < "${PROJECT_DIR}/scripts/database/schema.sql"
mysql -h127.0.0.1 -u"$DB_USER" -p"$DB_PASS" --default-character-set=utf8mb4 "$DB_NAME" < "${MIGRATIONS_DIR}/migrate_component.sql" 2>/dev/null || true
mysql -h127.0.0.1 -u"$DB_USER" -p"$DB_PASS" --default-character-set=utf8mb4 "$DB_NAME" < "${MIGRATIONS_DIR}/migrate_pipeline_native.sql" 2>/dev/null || true
mysql -h127.0.0.1 -u"$DB_USER" -p"$DB_PASS" --default-character-set=utf8mb4 "$DB_NAME" < "${SEEDS_DIR}/seed_default_service.sql" 2>/dev/null || true
mysql -h127.0.0.1 -u"$DB_USER" -p"$DB_PASS" --default-character-set=utf8mb4 "$DB_NAME" < "${MIGRATIONS_DIR}/migrate_build_job_task_name.sql" 2>/dev/null || true
mysql -h127.0.0.1 -u"$DB_USER" -p"$DB_PASS" --default-character-set=utf8mb4 "$DB_NAME" < "${MIGRATIONS_DIR}/migrate_pipeline_node.sql" 2>/dev/null || true
mysql -h127.0.0.1 -u"$DB_USER" -p"$DB_PASS" --default-character-set=utf8mb4 "$DB_NAME" < "${MIGRATIONS_DIR}/migrate_pipeline_step_def.sql" 2>/dev/null || true
mysql -h127.0.0.1 -u"$DB_USER" -p"$DB_PASS" --default-character-set=utf8mb4 "$DB_NAME" < "${SEEDS_DIR}/seed_pipeline_step_def.sql" 2>/dev/null || true

echo "数据库初始化完成: $DB_NAME"
