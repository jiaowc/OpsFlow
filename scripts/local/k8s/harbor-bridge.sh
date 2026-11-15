#!/usr/bin/env bash
# 在 kind 节点内把 localhost:80 转到宿主机 Harbor（解决镜像拉取 token URL 指向 localhost）
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=env.sh
source "${SCRIPT_DIR}/env.sh"

CONTROL_PLANE="${KIND_CLUSTER_NAME}-control-plane"
if ! docker inspect "${CONTROL_PLANE}" >/dev/null 2>&1; then
  echo "[k8s] 找不到节点 ${CONTROL_PLANE}，请先 ./start.sh" >&2
  exit 1
fi

echo "[k8s] 配置 Harbor 桥接 (localhost:80 -> host.docker.internal:80) ..."
docker exec "${CONTROL_PLANE}" bash -lc '
set -e
if ! command -v socat >/dev/null 2>&1; then
  apt-get update -qq
  DEBIAN_FRONTEND=noninteractive apt-get install -y -qq socat >/dev/null
fi
pkill -f "socat TCP4-LISTEN:80" 2>/dev/null || true
pkill -f "socat TCP6-LISTEN:80" 2>/dev/null || true
sleep 0.3
nohup socat TCP4-LISTEN:80,bind=127.0.0.1,fork,reuseaddr TCP:host.docker.internal:80 >/tmp/socat-harbor-v4.log 2>&1 &
nohup socat TCP6-LISTEN:80,bind=[::1],fork,reuseaddr,ipv6only=1 TCP:host.docker.internal:80 >/tmp/socat-harbor-v6.log 2>&1 &
sleep 0.5
curl -s -o /dev/null -w "harbor_v4:%{http_code}\n" http://127.0.0.1/api/v2.0/health
curl -g -s -o /dev/null -w "harbor_v6:%{http_code}\n" "http://[::1]/api/v2.0/health"
'
echo "[k8s] Harbor 桥接完成"
