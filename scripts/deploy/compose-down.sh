#!/usr/bin/env bash
# 停止 Compose 部署（默认保留数据卷）
#   ./scripts/deploy/compose-down.sh
#   ./scripts/deploy/compose-down.sh --volumes   # 同时删除 MySQL 数据卷
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT_DIR"

REMOVE_VOLUMES=0
for arg in "$@"; do
  case "$arg" in
    -v|--volumes)
      REMOVE_VOLUMES=1
      ;;
  esac
done

if [[ "$REMOVE_VOLUMES" -eq 1 ]]; then
  echo "停止并删除容器与数据卷（数据库将清空）..."
  docker compose -f docker-compose.yml -f docker-compose.app.yml down -v 2>/dev/null \
    || docker compose down -v
else
  echo "停止容器（保留 MySQL 数据卷与 ./data ./logs）..."
  docker compose -f docker-compose.yml -f docker-compose.app.yml down 2>/dev/null \
    || docker compose down
fi

echo "已停止。"
