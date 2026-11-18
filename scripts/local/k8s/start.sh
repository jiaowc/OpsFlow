#!/usr/bin/env bash
# 用 Docker 启动可销毁的 kind 集群 + 部署节点（不污染本机全局 K8s）
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=env.sh
source "${SCRIPT_DIR}/env.sh"

mkdir -p "${K8S_DATA_DIR}"

need_cmd() {
  if ! command -v "$1" >/dev/null 2>&1; then
    echo "[k8s] 缺少命令: $1" >&2
    echo "[k8s] macOS 可执行: brew install kind kubectl" >&2
    exit 1
  fi
}

need_cmd docker
need_cmd kind
need_cmd kubectl

# 所有 kubectl/kind 操作只写项目内文件，避免污染 ~/.kube
RAW_KUBECONFIG="${K8S_DATA_DIR}/kubeconfig.host.yaml"
DEPLOY_KUBECONFIG="${K8S_DATA_DIR}/kubeconfig"
export KUBECONFIG="${RAW_KUBECONFIG}"

echo "[k8s] 创建/复用 kind 集群: ${KIND_CLUSTER_NAME}"
if kind get clusters 2>/dev/null | grep -qx "${KIND_CLUSTER_NAME}"; then
  echo "[k8s] 集群已存在，跳过 create"
  kind get kubeconfig --name "${KIND_CLUSTER_NAME}" > "${RAW_KUBECONFIG}"
else
  # --kubeconfig + KUBECONFIG 均指向项目目录，不写 ~/.kube
  kind create cluster \
    --name "${KIND_CLUSTER_NAME}" \
    --config "${SCRIPT_DIR}/kind-config.yaml" \
    --kubeconfig "${RAW_KUBECONFIG}"
fi

CONTROL_PLANE="${KIND_CLUSTER_NAME}-control-plane"
RAW_KUBECONFIG="$RAW_KUBECONFIG" DEPLOY_KUBECONFIG="$DEPLOY_KUBECONFIG" CONTROL_PLANE="$CONTROL_PLANE" python3 - <<'PY'
from pathlib import Path
import os, re
raw = Path(os.environ["RAW_KUBECONFIG"])
out = Path(os.environ["DEPLOY_KUBECONFIG"])
cp = os.environ["CONTROL_PLANE"]
src = raw.read_text()
# 127.0.0.1:xxxxx / localhost → kind 容器内 DNS，供部署节点访问
src2 = re.sub(
    r"(server:\s*)https://(127\.0\.0\.1|localhost)(:\d+)?",
    rf"\1https://{cp}:6443",
    src,
)
out.write_text(src2)
print(f"[k8s] deploy kubeconfig -> {out}")
print(f"[k8s] server = https://{cp}:6443")
PY

echo "[k8s] 预创建命名空间: ${K8S_NAMESPACES}"
# 用 host kubeconfig 操作（本机临时，不写入 ~/.kube）
export KUBECONFIG="${RAW_KUBECONFIG}"
for ns in ${K8S_NAMESPACES}; do
  kubectl get ns "$ns" >/dev/null 2>&1 || kubectl create ns "$ns"
done

# 让 kind 节点能拉宿主机 Harbor 的 localhost 镜像
# 桥接失败（如宿主机 Harbor 未启动）只告警不中断，Harbor 起来后可单独重跑 harbor-bridge.sh
if ! bash "${SCRIPT_DIR}/harbor-bridge.sh"; then
  echo "[k8s] 警告：Harbor 桥接未成功，继续启动；Harbor 就绪后请执行 ./harbor-bridge.sh，否则 kind 节点拉不到 localhost 镜像" >&2
fi

echo "[k8s] 构建并启动部署节点容器 ..."
export DEPLOY_NODE_IMAGE DEPLOY_NODE_PORT DEPLOY_NODE_SSH_PASSWORD DEPLOY_CONTAINER_NAME K8S_DATA_DIR
docker compose -f "${SCRIPT_DIR}/docker-compose.yml" build
docker compose -f "${SCRIPT_DIR}/docker-compose.yml" up -d

# 把部署节点挂到 kind 网络，才能解析 control-plane 主机名
KIND_NET="kind"
if docker network inspect "${KIND_NET}" >/dev/null 2>&1; then
  if ! docker inspect -f '{{json .NetworkSettings.Networks}}' "${DEPLOY_CONTAINER_NAME}" \
      | grep -q "\"${KIND_NET}\""; then
    echo "[k8s] 连接部署节点到 Docker 网络 ${KIND_NET}"
    docker network connect "${KIND_NET}" "${DEPLOY_CONTAINER_NAME}" || true
  fi
else
  echo "[k8s] 警告: 未找到 kind 网络，部署节点可能无法访问 API" >&2
fi

echo "[k8s] 等待部署节点 SSH ..."
ok=0
for i in $(seq 1 40); do
  if nc -z 127.0.0.1 "${DEPLOY_NODE_PORT}" 2>/dev/null; then
    ok=1
    break
  fi
  sleep 1
done
if [[ "$ok" != "1" ]]; then
  echo "[k8s] 端口 ${DEPLOY_NODE_PORT} 未就绪" >&2
  docker compose -f "${SCRIPT_DIR}/docker-compose.yml" ps >&2 || true
  exit 1
fi

echo "[k8s] 冒烟: 集群节点"
kubectl --kubeconfig "${RAW_KUBECONFIG}" get nodes

if command -v sshpass >/dev/null 2>&1; then
  echo "[k8s] 冒烟: 部署节点 kubectl"
  sshpass -p "${DEPLOY_NODE_SSH_PASSWORD}" ssh -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null \
    -p "${DEPLOY_NODE_PORT}" "${DEPLOY_NODE_SSH_USER}@127.0.0.1" \
    'kubectl version --client 2>&1 | head -n1; kubectl get nodes 2>&1 | head -n5' || true
fi

echo
echo "[k8s] 已启动（全部在 Docker 内，可随时 ./destroy.sh 清空）"
echo "  kind 集群: ${KIND_CLUSTER_NAME}"
echo "  部署节点: ssh -p ${DEPLOY_NODE_PORT} ${DEPLOY_NODE_SSH_USER}@127.0.0.1  密码 ${DEPLOY_NODE_SSH_PASSWORD}"
echo "  本机临时 kubeconfig: ${RAW_KUBECONFIG}  （未写入 ~/.kube）"
echo "接下来: ${SCRIPT_DIR}/configure-opsflow.sh"
