#!/usr/bin/env bash
# 本地 Harbor + Nexus 共用变量。其它脚本 source 本文件即可。

_HN_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
export HN_ROOT="${HN_ROOT:-$_HN_DIR}"
export HN_DATA_DIR="${HN_DATA_DIR:-$HN_ROOT/.data}"

# Harbor（官方 online installer）
export HARBOR_VERSION="${HARBOR_VERSION:-v2.12.2}"
# Harbor 校验不允许 127.0.0.1 作为 hostname，本地用 localhost
export HARBOR_HOSTNAME="${HARBOR_HOSTNAME:-localhost}"
export HARBOR_HTTP_PORT="${HARBOR_HTTP_PORT:-80}"
export HARBOR_ADMIN_PASSWORD="${HARBOR_ADMIN_PASSWORD:-Harbor12345}"
export HARBOR_INSTALL_DIR="${HARBOR_INSTALL_DIR:-$HN_DATA_DIR/harbor}"
export HARBOR_EXTERNAL_URL="http://${HARBOR_HOSTNAME}"
if [[ "$HARBOR_HTTP_PORT" != "80" ]]; then
  export HARBOR_EXTERNAL_URL="http://${HARBOR_HOSTNAME}:${HARBOR_HTTP_PORT}"
fi
# OpsFlow 组件 URL（含协议，API 用）
export HARBOR_COMPONENT_URL="${HARBOR_COMPONENT_URL:-$HARBOR_EXTERNAL_URL}"
# docker login / 镜像前缀用的 host（无协议）
export HARBOR_REGISTRY_HOST="${HARBOR_REGISTRY_HOST:-$HARBOR_HOSTNAME}"
if [[ "$HARBOR_HTTP_PORT" != "80" ]]; then
  export HARBOR_REGISTRY_HOST="${HARBOR_HOSTNAME}:${HARBOR_HTTP_PORT}"
fi

# Nexus 3
export NEXUS_HTTP_PORT="${NEXUS_HTTP_PORT:-8081}"
export NEXUS_ADMIN_PASSWORD="${NEXUS_ADMIN_PASSWORD:-Nexus12345}"
export NEXUS_COMPONENT_URL="${NEXUS_COMPONENT_URL:-http://127.0.0.1:${NEXUS_HTTP_PORT}}"
export NEXUS_DATA_DIR="${NEXUS_DATA_DIR:-$HN_DATA_DIR/nexus}"

# Harbor 测试 project（推镜像用；library 是 Harbor 默认已有）
export HARBOR_TEST_PROJECT="${HARBOR_TEST_PROJECT:-opsflow}"
