#!/usr/bin/env bash
# 把两个本地构建节点写入 OpsFlow build_node 表（不删已有远程节点）
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "${SCRIPT_DIR}/../../.." && pwd)"
# shellcheck source=env.sh
source "${SCRIPT_DIR}/env.sh"
# shellcheck source=../../database/db_env.sh
source "${PROJECT_DIR}/scripts/database/db_env.sh"

resolve_db_env "${1:-}"

mysql_q() {
  MYSQL_PWD="$DB_PASS" mysql --default-character-set=utf8mb4 \
    -h"$DB_HOST" -P"$DB_PORT" -u"$DB_USER" "$DB_NAME" "$@"
}

# 探测 SSH
for port in "$BUILD_NODE1_PORT" "$BUILD_NODE2_PORT"; do
  if ! nc -z 127.0.0.1 "$port" 2>/dev/null; then
    echo "[opsflow] 127.0.0.1:${port} 不可达，请先 ./start.sh" >&2
    exit 1
  fi
done

sql_escape() {
  python3 -c 'import sys; print(sys.argv[1].replace("\\", "\\\\").replace("'\''", "'\'''\''"))' "$1"
}

PASS_ESC="$(sql_escape "$BUILD_NODE_SSH_PASSWORD")"
USER_ESC="$(sql_escape "$BUILD_NODE_SSH_USER")"
WD_ESC="$(sql_escape "$BUILD_NODE_WORK_DIR")"

upsert_node() {
  local name="$1" port="$2" label="$3"
  local name_esc port_esc
  name_esc="$(sql_escape "$name")"
  port_esc="$(sql_escape "$port")"

  mysql_q <<SQL
SET NAMES utf8mb4;
INSERT INTO build_node
  (name, label, host, port, auth_type, username, password, private_key, private_key_passphrase,
   node_type, work_dir, status, description, create_time, update_time)
SELECT
  '${name_esc}', '${label}', '127.0.0.1', ${port_esc}, 'password', '${USER_ESC}', '${PASS_ESC}',
  NULL, NULL, 'build', '${WD_ESC}', '在线', '本地 Docker 构建节点（调试）', NOW(), NOW()
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM build_node WHERE name = '${name_esc}');

UPDATE build_node
SET label = '${label}',
    host = '127.0.0.1',
    port = ${port_esc},
    auth_type = 'password',
    username = '${USER_ESC}',
    password = '${PASS_ESC}',
    node_type = 'build',
    work_dir = '${WD_ESC}',
    status = '在线',
    description = '本地 Docker 构建节点（调试）',
    update_time = NOW()
WHERE name = '${name_esc}';
SQL
}

echo "[opsflow] 写入 ${BUILD_NODE1_NAME} / ${BUILD_NODE2_NAME} ..."
upsert_node "$BUILD_NODE1_NAME" "$BUILD_NODE1_PORT" "local-docker"
upsert_node "$BUILD_NODE2_NAME" "$BUILD_NODE2_PORT" "local-docker"

echo "[opsflow] 当前构建节点:"
mysql_q -e "SELECT id,name,host,port,node_type,work_dir,status,username FROM build_node WHERE node_type='build' OR node_type IS NULL OR node_type='' ORDER BY id;"

echo
echo "[opsflow] 完成。在流水线模版里把构建节点勾选为 ${BUILD_NODE1_NAME}、${BUILD_NODE2_NAME}。"
echo "[opsflow] UI「节点管理」可点环境检测（需 JDK/Maven/Git；Docker 为非必检）。"
