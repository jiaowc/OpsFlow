#!/usr/bin/env bash
# 从当前运行的 MySQL 导出「可一键初始化」脚本（结构 + 配置数据，不含构建/上线历史）
#
#   bash scripts/database/export_init.sh
#   bash scripts/database/export_init.sh --profile=dev
#
# 覆盖连接（优先级最高）：
#   DB_HOST DB_PORT DB_USER DB_PASS DB_NAME
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "${SCRIPT_DIR}/../.." && pwd)"
OUT_FILE="${SCRIPT_DIR}/init.sql"
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
source "${SCRIPT_DIR}/db_env.sh"
resolve_db_env "$PROFILE" || exit 1

# 配置类数据：用户/权限、环境/集群/服务、流水线/步骤/模版、组件/钥匙、节点、系统配置
# 不含运行时历史：build_job / build_job_stage / deploy_task / approval_record / inbox_message
MASTER_TABLES=(
  user role permission user_role role_permission user_feishu
  cluster env service component credential
  pipeline pipeline_step_def pipeline_template pipeline_view pipeline_view_role
  approval_flow build_node system_config
)

echo "导出源: ${DB_USER}@${DB_HOST}:${DB_PORT}/${DB_NAME}"
echo "输出:   ${OUT_FILE}"

TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT

export MYSQL_PWD="$DB_PASS"
DUMP_COMMON=(
  -h"$DB_HOST" -P"$DB_PORT" -u"$DB_USER"
  --default-character-set=utf8mb4
  --single-transaction
  --skip-comments
)

mysqldump "${DUMP_COMMON[@]}" --routines --triggers --no-data \
  --skip-add-drop-table "$DB_NAME" > "${TMP_DIR}/schema.sql"

mysqldump "${DUMP_COMMON[@]}" --complete-insert --skip-extended-insert \
  --no-create-info "$DB_NAME" "${MASTER_TABLES[@]}" > "${TMP_DIR}/data.sql"

# 加密存储的凭据（enc:v1:...）绑定本机主密钥，带到新环境也解不开：导出时一律置空，新环境重新录入
ENC_COUNT="$(grep -oE "'enc:v1:[A-Za-z0-9+/=]+'" "${TMP_DIR}/data.sql" | wc -l | tr -d ' ')"
sed -E "s#'enc:v1:[A-Za-z0-9+/=]+'#NULL#g" "${TMP_DIR}/data.sql" > "${TMP_DIR}/data.scrubbed.sql"
mv "${TMP_DIR}/data.scrubbed.sql" "${TMP_DIR}/data.sql"
echo "已置空加密凭据字段: ${ENC_COUNT} 个"

{
  cat <<EOF
-- OpsFlow 一键初始化（结构 + 当前配置数据）
-- 生成时间: $(date '+%Y-%m-%d %H:%M:%S %z')
-- 来源库: ${DB_HOST}:${DB_PORT}/${DB_NAME}
--
-- 包含：用户/角色权限、环境/集群/服务、流水线/步骤/模版、组件/钥匙、节点、系统配置
-- 不含：构建历史、阶段日志、上线任务、审批记录、站内信
--
-- 使用：
--   mysql -u root -p -e "CREATE DATABASE IF NOT EXISTS opsflow DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"
--   mysql -u root -p opsflow < scripts/database/init.sql
--
-- 注意：含当前环境的账号、组件地址与钥匙内容，仅用于受控环境初始化。

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;
SET UNIQUE_CHECKS = 0;
SET SQL_MODE = 'NO_AUTO_VALUE_ON_ZERO';

CREATE DATABASE IF NOT EXISTS \`opsflow\` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE \`opsflow\`;

EOF
  cat "${TMP_DIR}/schema.sql"
  echo
  echo "-- ===================== 配置数据 ====================="
  echo
  cat "${TMP_DIR}/data.sql"
  echo
  echo "SET FOREIGN_KEY_CHECKS = 1;"
  echo "SET UNIQUE_CHECKS = 1;"
} > "$OUT_FILE"

echo "完成: $(wc -l < "$OUT_FILE" | tr -d ' ') 行 -> $OUT_FILE"
