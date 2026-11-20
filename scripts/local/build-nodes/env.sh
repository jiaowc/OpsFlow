#!/usr/bin/env bash
# 本地 Docker 构建节点共用变量

_BN_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
export BN_ROOT="${BN_ROOT:-$_BN_DIR}"
export BN_DATA_DIR="${BN_DATA_DIR:-$BN_ROOT/.data}"

# SSH（映射到宿主机端口，OpsFlow 连 127.0.0.1:PORT）
export BUILD_NODE1_PORT="${BUILD_NODE1_PORT:-2222}"
export BUILD_NODE2_PORT="${BUILD_NODE2_PORT:-2223}"
export BUILD_NODE_SSH_USER="${BUILD_NODE_SSH_USER:-root}"
export BUILD_NODE_SSH_PASSWORD="${BUILD_NODE_SSH_PASSWORD:-Build12345}"
export BUILD_NODE_WORK_DIR="${BUILD_NODE_WORK_DIR:-~/opsflow}"

export BUILD_NODE1_NAME="${BUILD_NODE1_NAME:-local-build-1}"
export BUILD_NODE2_NAME="${BUILD_NODE2_NAME:-local-build-2}"
export BUILD_NODE_IMAGE="${BUILD_NODE_IMAGE:-opsflow-local-build-node:1}"
