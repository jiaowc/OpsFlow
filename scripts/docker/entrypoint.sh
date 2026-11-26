#!/bin/sh
# OpsFlow 容器入口：准备可写目录，并以非 root 用户启动。
set -eu

mkdir -p /app/logs /app/data/branding /app/data/pipeline-logs
JAVA_OPTS="${JAVA_OPTS:--Xms256m -Xmx512m}"
# 要启动的 jar：社区版镜像是 /app/opsflow.jar，其他镜像可用环境变量覆盖
APP_JAR="${APP_JAR:-/app/opsflow.jar}"

# 内存耗尽时的保护，固定生效；JAVA_OPTS 写在后面，里面的同名参数优先
#   ExitOnOutOfMemoryError：OOM 后进程直接退出，由容器重启策略拉起，避免带着损坏的状态继续跑
#   HeapDumpOnOutOfMemoryError：退出前把堆转储写到日志目录，便于排查（文件大小约等于堆大小）
OOM_OPTS="-XX:+ExitOnOutOfMemoryError -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=/app/logs"

run_app() {
  # OOM_OPTS / JAVA_OPTS 需按空格拆成多个参数
  # shellcheck disable=SC2086
  exec java $OOM_OPTS $JAVA_OPTS -jar "$APP_JAR"
}

if [ "$(id -u)" = "0" ]; then
  chown -R opsflow:opsflow /app/logs /app/data 2>/dev/null || true
  if [ "$#" -eq 0 ]; then
    exec runuser -u opsflow -- env JAVA_OPTS="$JAVA_OPTS" APP_JAR="$APP_JAR" /app/docker-entrypoint.sh
  fi
  exec runuser -u opsflow -- "$@"
fi

if [ "$#" -eq 0 ]; then
  run_app
fi
exec "$@"
