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

# 初始管理员：init.sql 只带这一个账号。初始口令为 admin（与用户名相同，属于弱口令，首次登录会被强制改密）
# 下面是该初始口令的 BCrypt 哈希，由 PasswordHasher.hash("admin") 生成；不是任何真实环境的密码
INIT_ADMIN_USER="admin"
INIT_ADMIN_PASSWORD_HASH='$2a$10$SyILMi5aaEuNikAEkr3bEO/h3slK3Z24MZhCsHbV75n.4XWJyWQIO'

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

# 按表导出配置数据；带账号/授权信息的表做裁剪，避免把当前环境的人员和许可证带进仓库：
#   user          只保留初始管理员，密码重置为初始口令（首次登录强制修改）
#   user_role     只保留初始管理员的角色
#   user_feishu   不导出（个人飞书身份）
#   system_config 不导出许可证（license），新环境自行导入
: > "${TMP_DIR}/data.sql"
PLAIN_TABLES=()
for table in "${MASTER_TABLES[@]}"; do
  case "$table" in
    user|user_role|user_feishu|system_config) ;;
    *) PLAIN_TABLES+=("$table") ;;
  esac
done

dump_data() {
  mysqldump "${DUMP_COMMON[@]}" --complete-insert --skip-extended-insert \
    --no-create-info "$@" >> "${TMP_DIR}/data.sql"
}

dump_data "$DB_NAME" "${PLAIN_TABLES[@]}"
dump_data --where="username='${INIT_ADMIN_USER}'" "$DB_NAME" user
dump_data --where="user_id IN (SELECT id FROM \`user\` WHERE username='${INIT_ADMIN_USER}')" "$DB_NAME" user_role
dump_data --where="config_type <> 'license'" "$DB_NAME" system_config

# 初始管理员密码统一重置，不把当前环境的密码哈希带出去
ADMIN_ROWS="$(grep -cE "^INSERT INTO \`user\` .* VALUES \([0-9]+,'${INIT_ADMIN_USER}'," "${TMP_DIR}/data.sql" || true)"
if [ "$ADMIN_ROWS" != "1" ]; then
  echo "导出失败：user 表中没有找到唯一的初始管理员 ${INIT_ADMIN_USER}（匹配 ${ADMIN_ROWS} 行）" >&2
  exit 1
fi
sed -E "s|^(INSERT INTO \`user\` .* VALUES \([0-9]+,'${INIT_ADMIN_USER}',)'[^']*'|\\1'${INIT_ADMIN_PASSWORD_HASH}'|" \
  "${TMP_DIR}/data.sql" > "${TMP_DIR}/data.admin.sql"
mv "${TMP_DIR}/data.admin.sql" "${TMP_DIR}/data.sql"
echo "已裁剪账号数据：仅保留 ${INIT_ADMIN_USER}（密码重置为初始口令），不含飞书绑定和许可证"

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
-- 包含：角色权限、环境/集群/服务、流水线/步骤/模版、组件/钥匙、节点、系统配置
-- 不含：构建历史、阶段日志、上线任务、审批记录、站内信、许可证、飞书绑定
-- 账号：只有初始管理员 ${INIT_ADMIN_USER}，初始口令 admin，首次登录必须修改
--
-- 使用：
--   mysql -u root -p -e "CREATE DATABASE IF NOT EXISTS opsflow DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"
--   mysql -u root -p opsflow < scripts/database/init.sql
--
-- 注意：含当前环境的组件地址、节点和服务配置（加密凭据已置空），用于受控环境初始化。

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
