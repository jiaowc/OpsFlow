#!/bin/sh
# OpsFlow 容器入口：准备可写目录，并以非 root 用户启动。
set -eu

mkdir -p /app/logs /app/data/branding /app/data/pipeline-logs
JAVA_OPTS="${JAVA_OPTS:--Xms256m -Xmx512m}"

run_app() {
  # JAVA_OPTS 需按空格拆成多个参数
  # shellcheck disable=SC2086
  exec java $JAVA_OPTS -jar /app/opsflow.jar
}

if [ "$(id -u)" = "0" ]; then
  chown -R opsflow:opsflow /app/logs /app/data 2>/dev/null || true
  if [ "$#" -eq 0 ]; then
    exec runuser -u opsflow -- env JAVA_OPTS="$JAVA_OPTS" /app/docker-entrypoint.sh
  fi
  exec runuser -u opsflow -- "$@"
fi

if [ "$#" -eq 0 ]; then
  run_app
fi
exec "$@"
