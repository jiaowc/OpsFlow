#!/usr/bin/env bash
# 本地 kind K8s + 部署节点共用变量

_K8S_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
export K8S_ROOT="${K8S_ROOT:-$_K8S_DIR}"
export K8S_DATA_DIR="${K8S_DATA_DIR:-$K8S_ROOT/.data}"

# kind 集群名（销毁用 kind delete cluster --name ...）
export KIND_CLUSTER_NAME="${KIND_CLUSTER_NAME:-opsflow}"

# 部署节点 SSH（OpsFlow 连 127.0.0.1:PORT）
export DEPLOY_NODE_PORT="${DEPLOY_NODE_PORT:-2224}"
export DEPLOY_NODE_SSH_USER="${DEPLOY_NODE_SSH_USER:-root}"
export DEPLOY_NODE_SSH_PASSWORD="${DEPLOY_NODE_SSH_PASSWORD:-Build12345}"
export DEPLOY_NODE_WORK_DIR="${DEPLOY_NODE_WORK_DIR:-~/opsflow}"
export DEPLOY_NODE_NAME="${DEPLOY_NODE_NAME:-local-deploy}"
export DEPLOY_NODE_IMAGE="${DEPLOY_NODE_IMAGE:-opsflow-local-deploy-node:1}"
export DEPLOY_CONTAINER_NAME="${DEPLOY_CONTAINER_NAME:-opsflow-deploy}"

# 集群在 OpsFlow 中的显示名 / 钥匙串名
export OPSFLOW_CLUSTER_NAME="${OPSFLOW_CLUSTER_NAME:-local-kind}"
export OPSFLOW_KUBE_CREDENTIAL_NAME="${OPSFLOW_KUBE_CREDENTIAL_NAME:-local-kind-kubeconfig}"

# 需要预创建的命名空间（与环境 k8s_namespace 对齐）
export K8S_NAMESPACES="${K8S_NAMESPACES:-dev opsflow}"
