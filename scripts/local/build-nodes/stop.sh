#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=env.sh
source "${SCRIPT_DIR}/env.sh"

export BUILD_NODE_IMAGE BUILD_NODE1_PORT BUILD_NODE2_PORT BN_DATA_DIR BUILD_NODE_SSH_PASSWORD
docker compose -f "${SCRIPT_DIR}/docker-compose.yml" down
echo "[build-nodes] 已停止（工作目录数据仍在 ${BN_DATA_DIR}）"
