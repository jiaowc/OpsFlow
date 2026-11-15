#!/usr/bin/env bash
# 启动本地 Nexus 3，并把 admin 密码改成约定值（NEXUS_ADMIN_PASSWORD）。
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=env.sh
source "${SCRIPT_DIR}/env.sh"

mkdir -p "$NEXUS_DATA_DIR"
# Nexus 容器内用户 uid=200，宿主机目录需可写
chmod -R u+rwX "$NEXUS_DATA_DIR" 2>/dev/null || true

echo "[nexus] 启动 sonatype/nexus3，端口 ${NEXUS_HTTP_PORT} ..."
export NEXUS_HTTP_PORT NEXUS_DATA_DIR
docker compose -f "${SCRIPT_DIR}/docker-compose.nexus.yml" up -d

echo "[nexus] 等待就绪 ..."
for i in $(seq 1 90); do
  if curl -sf "http://127.0.0.1:${NEXUS_HTTP_PORT}/service/rest/v1/status" >/dev/null 2>&1; then
    echo "[nexus] 已就绪"
    break
  fi
  if [[ $i -eq 90 ]]; then
    echo "[nexus] 超时未就绪，请检查: docker logs opsflow-nexus" >&2
    exit 1
  fi
  sleep 3
done

# 首次启动：admin 密码在文件里；后续若已是约定密码则跳过
ADMIN_FILE_IN_CONTAINER="/nexus-data/admin.password"
if docker exec opsflow-nexus test -f "$ADMIN_FILE_IN_CONTAINER" 2>/dev/null; then
  OLD_PASS="$(docker exec opsflow-nexus cat "$ADMIN_FILE_IN_CONTAINER" | tr -d '\r\n')"
  echo "[nexus] 检测到首次 admin 密码，改为约定密码 ..."
  HTTP_CODE="$(curl -s -o /tmp/nexus-change-pass.out -w '%{http_code}' \
    -u "admin:${OLD_PASS}" \
    -H 'Content-Type: text/plain' \
    -X PUT \
    --data-binary "${NEXUS_ADMIN_PASSWORD}" \
    "http://127.0.0.1:${NEXUS_HTTP_PORT}/service/rest/v1/security/users/admin/change-password" || true)"
  if [[ "$HTTP_CODE" == "204" || "$HTTP_CODE" == "200" ]]; then
    docker exec opsflow-nexus rm -f "$ADMIN_FILE_IN_CONTAINER" || true
    echo "[nexus] admin 密码已设为约定值（见 env.sh 中 NEXUS_ADMIN_PASSWORD）"
  else
    echo "[nexus] 改密 HTTP ${HTTP_CODE}，响应: $(cat /tmp/nexus-change-pass.out 2>/dev/null || true)" >&2
    echo "[nexus] 可稍后手动登录 UI 改密，或用容器内 admin.password" >&2
  fi
else
  # 验证约定密码是否可用
  CODE="$(curl -s -o /dev/null -w '%{http_code}' -u "admin:${NEXUS_ADMIN_PASSWORD}" \
    "http://127.0.0.1:${NEXUS_HTTP_PORT}/service/rest/v1/repositories" || true)"
  if [[ "$CODE" == "200" ]]; then
    echo "[nexus] 约定 admin 密码可用"
  else
    echo "[nexus] 警告: 用约定密码访问 repositories 返回 HTTP ${CODE}，请检查密码" >&2
  fi
fi

echo "[nexus] UI: ${NEXUS_COMPONENT_URL}  用户: admin"
