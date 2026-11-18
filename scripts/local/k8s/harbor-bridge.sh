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
# 注意：不要用 pkill -f "socat ..."，它会匹配到本次 bash -lc 进程自身（参数里含同样文字）把脚本杀掉；
# 改为按进程名精确匹配（kind 节点里的 socat 只有这两个桥接进程）
docker exec "${CONTROL_PLANE}" bash -lc '
set -e
if ! command -v socat >/dev/null 2>&1; then
  apt-get update -qq
  DEBIAN_FRONTEND=noninteractive apt-get install -y -qq socat >/dev/null
fi
pkill -x socat 2>/dev/null || true
sleep 0.3
'
# 用 docker exec -d 起常驻进程，不依赖 exec 会话里 nohup 的后台进程能否存活
docker exec -d "${CONTROL_PLANE}" socat \
  TCP4-LISTEN:80,bind=127.0.0.1,fork,reuseaddr TCP:host.docker.internal:80
docker exec -d "${CONTROL_PLANE}" socat \
  'TCP6-LISTEN:80,bind=[::1],fork,reuseaddr,ipv6only=1' TCP:host.docker.internal:80
sleep 1

v4="$(docker exec "${CONTROL_PLANE}" curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1/api/v2.0/health || true)"
v6="$(docker exec "${CONTROL_PLANE}" curl -g -s -o /dev/null -w '%{http_code}' 'http://[::1]/api/v2.0/health' || true)"
echo "harbor_v4:${v4} harbor_v6:${v6}"
if [ "${v4}" != "200" ] || [ "${v6}" != "200" ]; then
  echo "[k8s] Harbor 桥接后健康检查未通过（宿主机 Harbor 是否已启动？http://localhost/api/v2.0/health）" >&2
  exit 1
fi
echo "[k8s] Harbor 桥接完成（Docker 重启后需重新执行本脚本）"
