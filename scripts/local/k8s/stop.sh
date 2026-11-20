#!/usr/bin/env bash
# 仅停止部署节点容器；kind 集群保留（彻底清除请用 destroy.sh）
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=env.sh
source "${SCRIPT_DIR}/env.sh"

export DEPLOY_NODE_IMAGE DEPLOY_NODE_PORT DEPLOY_NODE_SSH_PASSWORD DEPLOY_CONTAINER_NAME K8S_DATA_DIR
docker compose -f "${SCRIPT_DIR}/docker-compose.yml" down || true
echo "[k8s] 部署节点已停止；kind 集群仍在。彻底销毁: ${SCRIPT_DIR}/destroy.sh"
