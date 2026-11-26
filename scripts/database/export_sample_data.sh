#!/usr/bin/env bash
# 从当前库导出「本地演示数据」到 scripts/database/sample/sample-data.sql。
#
# 表结构和产品默认数据（角色权限、admin、步骤定义、默认模版、通用流水线）由 Flyway 管理
# （core/src/main/resources/db/migration），不在这里导出。这里只导出环境相关的配置，方便本地联调时
# 快速得到一套可用的集群 / 环境 / 节点 / 服务：
#
#   bash scripts/database/export_sample_data.sh
#   bash scripts/database/export_sample_data.sh --profile=dev
#
# 导入（可选，默认不导入；需在应用至少启动过一次、Flyway 建好表之后）：
#   mysql -u root -p opsflow < scripts/database/sample/sample-data.sql
#
# 覆盖连接（优先级最高）：
#   DB_HOST DB_PORT DB_USER DB_PASS DB_NAME
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OUT_FILE="${SCRIPT_DIR}/sample/sample-data.sql"
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

# 环境相关的配置表。不含：
#   - Flyway 基线已带的默认数据（role / permission / pipeline_step_def / pipeline_template / admin）
#   - 账号与授权（user / user_role / user_feishu / project_member / 许可证）
#   - 审批流（步骤里写的是具体用户名，换环境后对不上，需在界面重新配置）
#   - 运行历史（build_job / deploy_task / approval_record / audit_log / inbox_message）
SAMPLE_TABLES=(project cluster env build_node component credential service pipeline_view pipeline_view_role)

# Flyway 基线里已有的通用流水线；示例数据只带它们之外的（本地调试用的流水线）
BASELINE_PIPELINE_IDS="3,4,5,6,7"

echo "导出源: ${DB_USER}@${DB_HOST}:${DB_PORT}/${DB_NAME}"
echo "输出:   ${OUT_FILE}"

TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT

export MYSQL_PWD="$DB_PASS"
# --insert-ignore：可重复导入，已存在的行跳过、不覆盖
DUMP_COMMON=(
  -h"$DB_HOST" -P"$DB_PORT" -u"$DB_USER"
  --default-character-set=utf8mb4
  --single-transaction
  --skip-comments
  --compact
  --complete-insert --skip-extended-insert
  --no-create-info
  --insert-ignore
)

: > "${TMP_DIR}/data.sql"
mysqldump "${DUMP_COMMON[@]}" "$DB_NAME" "${SAMPLE_TABLES[@]}" >> "${TMP_DIR}/data.sql"
mysqldump "${DUMP_COMMON[@]}" --where="id NOT IN (${BASELINE_PIPELINE_IDS})" "$DB_NAME" pipeline >> "${TMP_DIR}/data.sql"

# 加密存储的凭据（enc:v1:...）绑定本机主密钥，带到别的环境也解不开：导出时一律置空，导入后重新录入
ENC_COUNT="$(grep -oE "'enc:v1:[A-Za-z0-9+/=]+'" "${TMP_DIR}/data.sql" | wc -l | tr -d ' ')"
sed -E "s#'enc:v1:[A-Za-z0-9+/=]+'#NULL#g" "${TMP_DIR}/data.sql" > "${TMP_DIR}/data.scrubbed.sql"
mv "${TMP_DIR}/data.scrubbed.sql" "${TMP_DIR}/data.sql"
echo "已置空加密凭据字段: ${ENC_COUNT} 个"

# 节点的 SSH 主机指纹是这台机器上的容器/主机特有的，换个环境一定对不上，会被主机指纹校验拒绝连接：
# 置空后首次连接时重新记录
sed -E "s#'(ssh-(ed25519|rsa|dss)|ecdsa-sha2-[a-z0-9-]+) [A-Za-z0-9+/=]+'#NULL#g; s#'SHA256:[A-Za-z0-9+/=]+'#NULL#g" \
  "${TMP_DIR}/data.sql" > "${TMP_DIR}/data.scrubbed.sql"
mv "${TMP_DIR}/data.scrubbed.sql" "${TMP_DIR}/data.sql"

mkdir -p "$(dirname "$OUT_FILE")"
{
  cat <<HEADER
-- OpsFlow 本地演示数据（可选，默认不导入）
-- 来源库: ${DB_HOST}:${DB_PORT}/${DB_NAME}
--
-- 包含：项目、集群、环境、构建/部署节点、组件、钥匙串（内容已置空）、服务、流水线视图、本地调试流水线
-- 不含：表结构与产品默认数据（由 Flyway 管理）、账号、审批流、许可证、运行历史
--
-- 使用（应用至少启动过一次、表已建好之后）：
--   mysql -u root -p opsflow < scripts/database/sample/sample-data.sql
-- 用 INSERT IGNORE 写入，可重复执行；已存在的同主键记录不会被覆盖。
-- 导入后需要在界面上重新录入钥匙串内容，并按需配置审批流。

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

HEADER
  cat "${TMP_DIR}/data.sql"
  echo
  echo "SET FOREIGN_KEY_CHECKS = 1;"
} > "$OUT_FILE"

echo "完成: $(grep -c '^INSERT' "$OUT_FILE") 条记录 -> $OUT_FILE"
