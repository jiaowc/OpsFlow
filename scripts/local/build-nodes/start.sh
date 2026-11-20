#!/usr/bin/env bash
# 构建并启动两个本地 SSH 构建节点
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=env.sh
source "${SCRIPT_DIR}/env.sh"

mkdir -p "${BN_DATA_DIR}/build-1" "${BN_DATA_DIR}/build-2"

echo "[build-nodes] 构建镜像 ${BUILD_NODE_IMAGE} ..."
export BUILD_NODE_IMAGE BUILD_NODE_SSH_PASSWORD BUILD_NODE1_PORT BUILD_NODE2_PORT BN_DATA_DIR
docker compose -f "${SCRIPT_DIR}/docker-compose.yml" build

echo "[build-nodes] 启动容器 ..."
docker compose -f "${SCRIPT_DIR}/docker-compose.yml" up -d

echo "[build-nodes] 等待 SSH ..."
for port in "$BUILD_NODE1_PORT" "$BUILD_NODE2_PORT"; do
  ok=0
  for i in $(seq 1 40); do
    if (echo >"/dev/tcp/127.0.0.1/${port}") >/dev/null 2>&1; then
      ok=1
      break
    fi
    # macOS 可能没有 /dev/tcp，改用 nc
    if nc -z 127.0.0.1 "$port" 2>/dev/null; then
      ok=1
      break
    fi
    sleep 1
  done
  if [[ "$ok" != "1" ]]; then
    echo "[build-nodes] 端口 ${port} 未就绪" >&2
    docker compose -f "${SCRIPT_DIR}/docker-compose.yml" ps >&2 || true
    exit 1
  fi
  echo "[build-nodes] 127.0.0.1:${port} 可连"
done

# 冒烟：SSH 执行 java/mvn/node/npm/git/docker
smoke() {
  local port="$1" name="$2"
  echo "[build-nodes] 检测 ${name} (port ${port}) ..."
  sshpass -p "$BUILD_NODE_SSH_PASSWORD" ssh -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null \
    -p "$port" "${BUILD_NODE_SSH_USER}@127.0.0.1" \
    'java -version 2>&1 | head -n1; mvn -version 2>&1 | head -n1; node -v; npm -v; git --version; docker --version; pwd; ls -la ~ | head' \
    || {
      echo "[build-nodes] 提示: 未安装 sshpass 时跳过自动检测，请手动: ssh -p ${port} root@127.0.0.1" >&2
      return 0
    }
}

if command -v sshpass >/dev/null 2>&1; then
  smoke "$BUILD_NODE1_PORT" "$BUILD_NODE1_NAME"
  smoke "$BUILD_NODE2_PORT" "$BUILD_NODE2_NAME"
else
  echo "[build-nodes] 未安装 sshpass，跳过 SSH 命令冒烟（configure 后可在 OpsFlow 点环境检测）"
fi

echo
echo "[build-nodes] 已启动:"
echo "  ${BUILD_NODE1_NAME}: ssh -p ${BUILD_NODE1_PORT} ${BUILD_NODE_SSH_USER}@127.0.0.1  密码 ${BUILD_NODE_SSH_PASSWORD}"
echo "  ${BUILD_NODE2_NAME}: ssh -p ${BUILD_NODE2_PORT} ${BUILD_NODE_SSH_USER}@127.0.0.1  密码 ${BUILD_NODE_SSH_PASSWORD}"
echo "接下来: ${SCRIPT_DIR}/configure-opsflow.sh"
