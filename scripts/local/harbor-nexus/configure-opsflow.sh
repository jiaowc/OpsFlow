#!/usr/bin/env bash
# 把本机 OpsFlow 的 Harbor / Nexus 组件与钥匙串指到本地实例。
# 不改 seeds；只改正在用的数据库。
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

echo "[opsflow] 数据库 ${DB_HOST}:${DB_PORT}/${DB_NAME}（来源: ${DB_CONFIG_SOURCE:-?}）"

# 校验本地服务可达
HARBOR_CODE="$(curl -s -o /dev/null -w '%{http_code}' -u "admin:${HARBOR_ADMIN_PASSWORD}" \
  "${HARBOR_COMPONENT_URL}/api/v2.0/projects?page_size=1" || true)"
if [[ "$HARBOR_CODE" != "200" ]]; then
  echo "[opsflow] Harbor 不可达（HTTP ${HARBOR_CODE}），请先运行 start-harbor.sh" >&2
  exit 1
fi
NEXUS_CODE="$(curl -s -o /dev/null -w '%{http_code}' -u "admin:${NEXUS_ADMIN_PASSWORD}" \
  "${NEXUS_COMPONENT_URL}/service/rest/v1/repositories" || true)"
if [[ "$NEXUS_CODE" != "200" ]]; then
  echo "[opsflow] Nexus 不可达（HTTP ${NEXUS_CODE}），请先运行 start-nexus.sh" >&2
  exit 1
fi

# JSON 字符串转义
json_escape() {
  python3 -c 'import json,sys; print(json.dumps(sys.argv[1])[1:-1])' "$1"
}
HARBOR_PASS_ESC="$(json_escape "$HARBOR_ADMIN_PASSWORD")"
NEXUS_PASS_ESC="$(json_escape "$NEXUS_ADMIN_PASSWORD")"
HARBOR_URL_ESC="$(json_escape "$HARBOR_COMPONENT_URL")"
NEXUS_URL_ESC="$(json_escape "$NEXUS_COMPONENT_URL")"

echo "[opsflow] 更新 / 插入钥匙串与组件 ..."
mysql_q <<SQL
SET NAMES utf8mb4;

-- Harbor 钥匙串
INSERT INTO credential (name, credential_type, config_data, description, status, create_time, update_time)
SELECT 'harbor-local', 'username_password',
       '{"username":"admin","password":"${HARBOR_PASS_ESC}"}',
       '本地 Docker Harbor', 1, NOW(), NOW()
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM credential WHERE name = 'harbor-local');

UPDATE credential
SET credential_type = 'username_password',
    config_data = '{"username":"admin","password":"${HARBOR_PASS_ESC}"}',
    description = '本地 Docker Harbor',
    status = 1,
    update_time = NOW()
WHERE name = 'harbor-local';

-- 兼容旧种子名 harbor：同步密码，避免其它引用落到空密码
UPDATE credential
SET config_data = '{"username":"admin","password":"${HARBOR_PASS_ESC}"}',
    status = 1,
    update_time = NOW()
WHERE name = 'harbor';

-- Nexus 钥匙串
INSERT INTO credential (name, credential_type, config_data, description, status, create_time, update_time)
SELECT 'nexus-local', 'username_password',
       '{"username":"admin","password":"${NEXUS_PASS_ESC}"}',
       '本地 Docker Nexus', 1, NOW(), NOW()
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM credential WHERE name = 'nexus-local');

UPDATE credential
SET credential_type = 'username_password',
    config_data = '{"username":"admin","password":"${NEXUS_PASS_ESC}"}',
    description = '本地 Docker Nexus',
    status = 1,
    update_time = NOW()
WHERE name = 'nexus-local';

-- 停用其它启用中的 harbor，避免 resolveComponent 拿到远程最新一条
UPDATE component
SET status = 0, update_time = NOW()
WHERE type = 'harbor' AND name <> 'harbor-local' AND status = 1;

INSERT INTO component (name, type, url, auth_type, auth_config, credential_id, description, status, create_time, update_time)
SELECT 'harbor-local', 'harbor', '${HARBOR_URL_ESC}', 'username_password',
       '{"username":"admin","password":"${HARBOR_PASS_ESC}"}',
       (SELECT id FROM credential WHERE name = 'harbor-local' LIMIT 1),
       '本地 Docker Harbor', 1, NOW(), NOW()
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM component WHERE name = 'harbor-local' AND type = 'harbor');

UPDATE component
SET url = '${HARBOR_URL_ESC}',
    auth_type = 'username_password',
    auth_config = '{"username":"admin","password":"${HARBOR_PASS_ESC}"}',
    credential_id = (SELECT id FROM credential WHERE name = 'harbor-local' LIMIT 1),
    description = '本地 Docker Harbor',
    status = 1,
    update_time = NOW()
WHERE name = 'harbor-local' AND type = 'harbor';

-- 若仍有旧名 harbor 组件，一并改指向本地（并启用），create_time 保持旧值时 harbor-local 更新时间更新即可成为最新
UPDATE component
SET url = '${HARBOR_URL_ESC}',
    auth_type = 'username_password',
    auth_config = '{"username":"admin","password":"${HARBOR_PASS_ESC}"}',
    credential_id = (SELECT id FROM credential WHERE name = 'harbor-local' LIMIT 1),
    status = 0,
    update_time = NOW()
WHERE type = 'harbor' AND name = 'harbor';

-- Nexus：停用其它，写入 nexus-local
UPDATE component
SET status = 0, update_time = NOW()
WHERE type = 'nexus' AND name <> 'nexus-local' AND status = 1;

INSERT INTO component (name, type, url, auth_type, auth_config, credential_id, description, status, create_time, update_time)
SELECT 'nexus-local', 'nexus', '${NEXUS_URL_ESC}', 'username_password',
       '{"username":"admin","password":"${NEXUS_PASS_ESC}"}',
       (SELECT id FROM credential WHERE name = 'nexus-local' LIMIT 1),
       '本地 Docker Nexus', 1, NOW(), NOW()
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM component WHERE name = 'nexus-local' AND type = 'nexus');

UPDATE component
SET url = '${NEXUS_URL_ESC}',
    auth_type = 'username_password',
    auth_config = '{"username":"admin","password":"${NEXUS_PASS_ESC}"}',
    credential_id = (SELECT id FROM credential WHERE name = 'nexus-local' LIMIT 1),
    description = '本地 Docker Nexus',
    status = 1,
    update_time = NOW()
WHERE name = 'nexus-local' AND type = 'nexus';

UPDATE component
SET status = 0, update_time = NOW()
WHERE type = 'nexus' AND name = 'nexus';
SQL

echo "[opsflow] 当前启用的 Harbor / Nexus 组件:"
mysql_q -e "SELECT id,name,type,url,status,credential_id,update_time FROM component WHERE type IN ('harbor','nexus') ORDER BY type, id;"

echo
echo "[opsflow] 完成。请在 UI「凭据与组件」对 harbor-local / nexus-local 点检测。"
echo "[opsflow] 镜像前缀示例: ${HARBOR_REGISTRY_HOST}/${HARBOR_TEST_PROJECT}/<serviceCode>:<tag>"
