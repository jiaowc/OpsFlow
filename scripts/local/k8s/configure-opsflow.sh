#!/usr/bin/env bash
# 把 local-kind 集群 + local-deploy 节点写入 OpsFlow（不删已有远程节点）
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

DEPLOY_KUBECONFIG="${K8S_DATA_DIR}/kubeconfig"
if [[ ! -f "${DEPLOY_KUBECONFIG}" ]]; then
  echo "[opsflow] 缺少 ${DEPLOY_KUBECONFIG}，请先 ./start.sh" >&2
  exit 1
fi

if ! nc -z 127.0.0.1 "${DEPLOY_NODE_PORT}" 2>/dev/null; then
  echo "[opsflow] 127.0.0.1:${DEPLOY_NODE_PORT} 不可达，请先 ./start.sh" >&2
  exit 1
fi

sql_escape() {
  python3 -c 'import sys; print(sys.argv[1].replace("\\", "\\\\").replace("'\''", "'\'''\''"))' "$1"
}

# kubeconfig → JSON 字符串写入钥匙串
KUBE_JSON="$(python3 - <<PY
import json
from pathlib import Path
content = Path("${DEPLOY_KUBECONFIG}").read_text()
print(json.dumps({"configContent": content}, ensure_ascii=False))
PY
)"
KUBE_JSON_ESC="$(sql_escape "$KUBE_JSON")"
CRED_NAME_ESC="$(sql_escape "$OPSFLOW_KUBE_CREDENTIAL_NAME")"
CLUSTER_NAME_ESC="$(sql_escape "$OPSFLOW_CLUSTER_NAME")"
NODE_NAME_ESC="$(sql_escape "$DEPLOY_NODE_NAME")"
PASS_ESC="$(sql_escape "$DEPLOY_NODE_SSH_PASSWORD")"
USER_ESC="$(sql_escape "$DEPLOY_NODE_SSH_USER")"
WD_ESC="$(sql_escape "$DEPLOY_NODE_WORK_DIR")"
PORT_ESC="$(sql_escape "$DEPLOY_NODE_PORT")"

echo "[opsflow] 写入钥匙串 ${OPSFLOW_KUBE_CREDENTIAL_NAME} ..."
mysql_q <<SQL
SET NAMES utf8mb4;
INSERT INTO credential (name, credential_type, config_data, description, status, create_time, update_time)
SELECT '${CRED_NAME_ESC}', 'kubeconfig', '${KUBE_JSON_ESC}',
       '本地 kind 集群 kubeconfig（Docker，可销毁）', 1, NOW(), NOW()
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM credential WHERE name = '${CRED_NAME_ESC}');

UPDATE credential
SET credential_type = 'kubeconfig',
    config_data = '${KUBE_JSON_ESC}',
    description = '本地 kind 集群 kubeconfig（Docker，可销毁）',
    status = 1,
    update_time = NOW()
WHERE name = '${CRED_NAME_ESC}';
SQL

CRED_ID="$(mysql_q -N -e "SELECT id FROM credential WHERE name='${CRED_NAME_ESC}' LIMIT 1;")"
if [[ -z "$CRED_ID" ]]; then
  echo "[opsflow] 写入钥匙串失败" >&2
  exit 1
fi

echo "[opsflow] 写入集群 ${OPSFLOW_CLUSTER_NAME} (credential_id=${CRED_ID}) ..."
mysql_q <<SQL
SET NAMES utf8mb4;
INSERT INTO cluster (name, server, status, description, credential_id, create_time, update_time)
SELECT '${CLUSTER_NAME_ESC}', 'https://${KIND_CLUSTER_NAME}-control-plane:6443', 1,
       '本地 kind 集群（Docker）', ${CRED_ID}, NOW(), NOW()
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM cluster WHERE name = '${CLUSTER_NAME_ESC}');

UPDATE cluster
SET server = 'https://${KIND_CLUSTER_NAME}-control-plane:6443',
    status = 1,
    description = '本地 kind 集群（Docker）',
    credential_id = ${CRED_ID},
    update_time = NOW()
WHERE name = '${CLUSTER_NAME_ESC}';
SQL

CLUSTER_ID="$(mysql_q -N -e "SELECT id FROM cluster WHERE name='${CLUSTER_NAME_ESC}' LIMIT 1;")"

echo "[opsflow] 写入部署节点 ${DEPLOY_NODE_NAME} ..."
mysql_q <<SQL
SET NAMES utf8mb4;
INSERT INTO build_node
  (name, label, host, port, auth_type, username, password, private_key, private_key_passphrase,
   node_type, work_dir, status, description, create_time, update_time)
SELECT
  '${NODE_NAME_ESC}', 'local-docker', '127.0.0.1', ${PORT_ESC}, 'password', '${USER_ESC}', '${PASS_ESC}',
  NULL, NULL, 'deploy', '${WD_ESC}', '在线', '本地 Docker 部署节点（kind）', NOW(), NOW()
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM build_node WHERE name = '${NODE_NAME_ESC}');

UPDATE build_node
SET label = 'local-docker',
    host = '127.0.0.1',
    port = ${PORT_ESC},
    auth_type = 'password',
    username = '${USER_ESC}',
    password = '${PASS_ESC}',
    node_type = 'deploy',
    work_dir = '${WD_ESC}',
    status = '在线',
    description = '本地 Docker 部署节点（kind）',
    update_time = NOW()
WHERE name = '${NODE_NAME_ESC}';
SQL

NODE_ID="$(mysql_q -N -e "SELECT id FROM build_node WHERE name='${NODE_NAME_ESC}' LIMIT 1;")"

# 把本地 CI/CD 流水线的部署节点指到 local-deploy；dev 环境绑到 local-kind
if [[ -n "$NODE_ID" ]]; then
  echo "[opsflow] 流水线 deploy_node_id -> ${NODE_ID} (${DEPLOY_NODE_NAME})"
  mysql_q -e "UPDATE pipeline SET deploy_node_id=${NODE_ID}, update_time=NOW()
              WHERE pipeline_type IN ('cicd','cd') AND status=1;"
fi

if [[ -n "$CLUSTER_ID" ]]; then
  echo "[opsflow] 环境 cluster_id -> ${CLUSTER_ID}（仅 nonprod 的 dev，若存在）"
  mysql_q -e "UPDATE env SET cluster_id=${CLUSTER_ID}, update_time=NOW()
              WHERE name='dev' AND env_type='nonprod';"
fi

echo
echo "[opsflow] 当前本地 K8s 相关配置:"
mysql_q -e "SELECT id,name,server,credential_id,status FROM cluster WHERE name='${CLUSTER_NAME_ESC}'\G"
mysql_q -e "SELECT id,name,host,port,node_type,status FROM build_node WHERE name='${NODE_NAME_ESC}'\G"
mysql_q -e "SELECT id,name,k8s_namespace,cluster_id FROM env WHERE name='dev'\G"

echo
echo "[opsflow] 完成。在流水线模版里确认部署节点为 ${DEPLOY_NODE_NAME}，环境 dev 的命名空间为 dev。"
echo "[opsflow] 测完销毁: ${SCRIPT_DIR}/destroy.sh"
