#!/usr/bin/env bash
# OpsFlow Docker Compose 一键启动
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT_DIR"

EXTERNAL_DB=0
COMPOSE_ARGS=()
for arg in "$@"; do
  case "$arg" in
    --external-db|--app-only)
      EXTERNAL_DB=1
      ;;
    *)
      COMPOSE_ARGS+=("$arg")
      ;;
  esac
done

if ! command -v docker >/dev/null 2>&1; then
  echo "未找到 docker，请先安装 Docker Desktop 或 Docker Engine。"
  exit 1
fi
if ! docker compose version >/dev/null 2>&1; then
  echo "未找到 docker compose（Compose V2 插件），请升级 Docker。"
  exit 1
fi

if [[ ! -f .env ]]; then
  cp .env.example .env
  echo "已生成 .env（来自 .env.example），试用默认密码即可；生产请先改 MYSQL_PASSWORD / MYSQL_ROOT_PASSWORD。"
fi

mkdir -p logs data/branding data/pipeline-logs

if [[ "$EXTERNAL_DB" -eq 1 ]]; then
  if ! grep -qE '^DB_URL=.+' .env || ! grep -qE '^DB_PASSWORD=.+' .env; then
    echo "外部库模式需要在 .env 中设置 DB_URL 与 DB_PASSWORD。"
    exit 1
  fi
  echo "启动 OpsFlow（外部 MySQL）..."
  docker compose -f docker-compose.app.yml up -d --build "${COMPOSE_ARGS[@]+"${COMPOSE_ARGS[@]}"}"
else
  echo "启动 OpsFlow（MySQL + 应用）..."
  docker compose up -d --build "${COMPOSE_ARGS[@]+"${COMPOSE_ARGS[@]}"}"
fi

# 读取端口展示
OPSFLOW_PORT="$(grep -E '^OPSFLOW_PORT=' .env 2>/dev/null | tail -1 | cut -d= -f2- || true)"
OPSFLOW_PORT="${OPSFLOW_PORT:-8080}"

echo ""
echo "已提交启动。等待健康检查通过后访问："
echo "  http://localhost:${OPSFLOW_PORT}"
echo "初始账号：admin / admin（首次登录会被要求修改密码）"
echo ""
echo "查看状态：docker compose ps"
echo "查看日志：docker compose logs -f opsflow"
