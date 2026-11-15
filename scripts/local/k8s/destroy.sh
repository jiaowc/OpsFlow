#!/usr/bin/env bash
# 销毁全部本地 K8s 调试资源（Docker 容器/网络/数据），不改本机 ~/.kube
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=env.sh
source "${SCRIPT_DIR}/env.sh"

echo "[k8s] 停止部署节点 ..."
export DEPLOY_NODE_IMAGE DEPLOY_NODE_PORT DEPLOY_NODE_SSH_PASSWORD DEPLOY_CONTAINER_NAME K8S_DATA_DIR
docker compose -f "${SCRIPT_DIR}/docker-compose.yml" down -v --remove-orphans || true

if command -v kind >/dev/null 2>&1; then
  if kind get clusters 2>/dev/null | grep -qx "${KIND_CLUSTER_NAME}"; then
    echo "[k8s] 删除 kind 集群 ${KIND_CLUSTER_NAME} ..."
    kind delete cluster --name "${KIND_CLUSTER_NAME}"
  else
    echo "[k8s] kind 集群 ${KIND_CLUSTER_NAME} 不存在，跳过"
  fi
else
  echo "[k8s] 未安装 kind，跳过集群删除"
fi

# 清理数据目录（kubeconfig 等）
if [[ -d "${K8S_DATA_DIR}" ]]; then
  echo "[k8s] 清理 ${K8S_DATA_DIR}"
  rm -rf "${K8S_DATA_DIR}"
fi

echo "[k8s] 已销毁。本机 ~/.kube 未被修改。"
