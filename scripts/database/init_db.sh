#!/bin/bash
# 初始化 opsflow 库：建库 + schema + 种子数据 + 全量迁移（空库才导入 schema，已有库只补跑迁移）
#
#   bash scripts/database/init_db.sh                 # 默认环境
#   bash scripts/database/init_db.sh --profile=dev   # dev 环境
#   连接解析规则见 scripts/database/db_env.sh

set -e

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
MIGRATIONS_DIR="${PROJECT_DIR}/scripts/database/migrations"
SEEDS_DIR="${PROJECT_DIR}/scripts/database/seeds"
PROFILE="${OPSFLOW_PROFILE:-}"

for arg in "$@"; do
  case "$arg" in
    --profile=*) PROFILE="${arg#--profile=}" ;;
    -h|--help)
      echo "用法: $0 [--profile=dev|test|prod]"
      exit 0
      ;;
  esac
done

# shellcheck source=db_env.sh
source "${PROJECT_DIR}/scripts/database/db_env.sh"
resolve_db_env "$PROFILE" || exit 1

export MYSQL_PWD="$DB_PASS"
MYSQL=(mysql -h"$DB_HOST" -P"$DB_PORT" -u"$DB_USER" --default-character-set=utf8mb4)

echo "初始化数据库: ${DB_USER}@${DB_HOST}:${DB_PORT}/${DB_NAME}"

"${MYSQL[@]}" -e "CREATE DATABASE IF NOT EXISTS \`$DB_NAME\` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"

TABLE_COUNT=$("${MYSQL[@]}" -N -e "SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA = '${DB_NAME}'")
if [ "$TABLE_COUNT" -eq 0 ]; then
  echo "空库：导入 schema.sql 与种子数据"
  "${MYSQL[@]}" "$DB_NAME" < "${PROJECT_DIR}/scripts/database/schema.sql"
  # 种子数据须在迁移前导入：migrate_project 会把未归属项目的服务归入默认项目
  for f in \
    "${SEEDS_DIR}/seed_default_service.sql" \
    "${SEEDS_DIR}/seed_pipeline_step_def.sql" \
    "${SEEDS_DIR}/seed_nginx_dockerfile_template.sql"; do
    echo "导入种子: $(basename "$f")"
    "${MYSQL[@]}" "$DB_NAME" < "$f"
  done
else
  echo "库中已有 ${TABLE_COUNT} 张表，跳过 schema.sql 与种子数据，只执行迁移"
fi

bash "${PROJECT_DIR}/scripts/database/run_migrations.sh" ${PROFILE:+--profile="$PROFILE"}

echo "数据库初始化完成: $DB_NAME"
