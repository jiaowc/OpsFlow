#!/usr/bin/env bash
# 一键启动本地 Harbor + Nexus。
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

"${SCRIPT_DIR}/start-nexus.sh"
"${SCRIPT_DIR}/start-harbor.sh"

echo
echo "全部启动完成。接下来可执行:"
echo "  ${SCRIPT_DIR}/configure-opsflow.sh"
echo "说明见: ${SCRIPT_DIR}/README.md"
