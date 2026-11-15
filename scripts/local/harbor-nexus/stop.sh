#!/usr/bin/env bash
# 停止本地 Harbor + Nexus（保留数据目录）。
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=env.sh
source "${SCRIPT_DIR}/env.sh"

echo "[stop] 停止 Nexus ..."
if docker compose -f "${SCRIPT_DIR}/docker-compose.nexus.yml" ps -q 2>/dev/null | grep -q .; then
  docker compose -f "${SCRIPT_DIR}/docker-compose.nexus.yml" down
else
  docker rm -f opsflow-nexus 2>/dev/null || true
fi

echo "[stop] 停止 Harbor ..."
if [[ -d "$HARBOR_INSTALL_DIR" ]]; then
  if [[ -f "${HARBOR_INSTALL_DIR}/docker-compose.yml" ]] || [[ -f "${HARBOR_INSTALL_DIR}/compose.yml" ]]; then
    (cd "$HARBOR_INSTALL_DIR" && docker compose down) || true
  fi
fi

echo "[stop] 完成（数据仍在 ${HN_DATA_DIR}）"
