#!/usr/bin/env bash
# OpsFlow 体验镜像（all-in-one）入口：在一个容器里依次启动 MySQL 和 OpsFlow。
#
# 首次启动：初始化数据库 -> 生成随机数据库口令 -> 建表并写入默认数据（Flyway）-> 导入演示数据
# 再次启动：直接拉起 MySQL 和应用，不重复初始化（标记文件在 /data 下，随数据卷保留）
#
# 环境变量：
#   OPSFLOW_DEMO_DATA  首次启动是否导入演示数据，默认 true；只在首次启动时生效
#   JAVA_OPTS          JVM 参数，默认 -Xms256m -Xmx512m
#   APP_JAR            要启动的 jar，默认 /app/opsflow.jar；基于本镜像做的其他镜像可以覆盖
set -euo pipefail

# 传了命令（如 docker run ... bash）时直接执行，方便排查
if [ "$#" -gt 0 ]; then
  exec "$@"
fi

DATA_DIR=/data
MYSQL_DATADIR="${DATA_DIR}/mysql"
DB_PASSWORD_FILE="${DATA_DIR}/.db-password"
SEED_MARKER="${DATA_DIR}/.seed-done"
MYSQL_SOCKET=/var/run/mysqld/mysqld.sock
DB_NAME=opsflow
DB_USER=opsflow
JDBC_URL="jdbc:mysql://127.0.0.1:3306/${DB_NAME}?useUnicode=true&characterEncoding=utf8&connectionCollation=utf8mb4_unicode_ci&serverTimezone=Asia/Shanghai"
JAVA_OPTS="${JAVA_OPTS:--Xms256m -Xmx512m}"
APP_JAR="${APP_JAR:-/app/opsflow.jar}"

MYSQLD_PID=""
APP_PID=""

log() {
  echo "[opsflow-allinone] $*"
}

mysql_root() {
  mysql --protocol=socket --socket="$MYSQL_SOCKET" -uroot "$@"
}

stop_all() {
  if [ -n "$APP_PID" ] && kill -0 "$APP_PID" 2>/dev/null; then
    log "停止 OpsFlow ..."
    kill -TERM "$APP_PID" 2>/dev/null || true
    wait "$APP_PID" 2>/dev/null || true
  fi
  if [ -n "$MYSQLD_PID" ] && kill -0 "$MYSQLD_PID" 2>/dev/null; then
    log "停止 MySQL ..."
    mysqladmin --protocol=socket --socket="$MYSQL_SOCKET" -uroot shutdown 2>/dev/null \
      || kill -TERM "$MYSQLD_PID" 2>/dev/null || true
    wait "$MYSQLD_PID" 2>/dev/null || true
  fi
}

# 作为 1 号进程必须自己处理信号，否则 docker stop 只能等超时后强杀
TERMINATING=0
trap 'TERMINATING=1' TERM INT

exit_if_terminating() {
  if [ "$TERMINATING" -eq 1 ]; then
    stop_all
    exit 0
  fi
}

# ---------- 目录与权限 ----------
mkdir -p "$MYSQL_DATADIR" "${DATA_DIR}/app/branding" "${DATA_DIR}/app/pipeline-logs" "${DATA_DIR}/logs" /var/run/mysqld
chown mysql:mysql "$MYSQL_DATADIR" /var/run/mysqld
chown -R opsflow:opsflow "${DATA_DIR}/app" "${DATA_DIR}/logs"

# ---------- MySQL ----------
if [ -z "$(ls -A "$MYSQL_DATADIR" 2>/dev/null)" ]; then
  log "首次启动：初始化 MySQL 数据目录 ${MYSQL_DATADIR}"
  mysqld --initialize-insecure --user=mysql --datadir="$MYSQL_DATADIR"
fi

log "启动 MySQL ..."
mysqld --user=mysql &
MYSQLD_PID=$!

ready=0
for _ in $(seq 1 90); do
  exit_if_terminating
  if ! kill -0 "$MYSQLD_PID" 2>/dev/null; then
    log "MySQL 启动失败，见上方 mysqld 日志"
    exit 1
  fi
  if mysqladmin --protocol=socket --socket="$MYSQL_SOCKET" -uroot ping --silent 2>/dev/null; then
    ready=1
    break
  fi
  sleep 1
done
if [ "$ready" -ne 1 ]; then
  log "等待 MySQL 就绪超时（90s）"
  stop_all
  exit 1
fi

# 数据库口令：首次启动随机生成并保存在数据卷内（镜像里没有固定口令）；之后每次启动按该文件对齐
if [ ! -s "$DB_PASSWORD_FILE" ]; then
  log "生成数据库口令（保存在 ${DB_PASSWORD_FILE}，仅容器内 root 可读）"
  (umask 077 && openssl rand -hex 24 > "$DB_PASSWORD_FILE")
fi
DB_PASSWORD="$(cat "$DB_PASSWORD_FILE")"

# 以下语句可重复执行。口令走标准输入，不出现在进程参数里
mysql_root <<SQL
ALTER USER 'root'@'localhost' IDENTIFIED WITH auth_socket;
CREATE DATABASE IF NOT EXISTS \`${DB_NAME}\` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE USER IF NOT EXISTS '${DB_USER}'@'127.0.0.1' IDENTIFIED WITH mysql_native_password BY '${DB_PASSWORD}';
ALTER USER '${DB_USER}'@'127.0.0.1' IDENTIFIED WITH mysql_native_password BY '${DB_PASSWORD}';
GRANT ALL PRIVILEGES ON \`${DB_NAME}\`.* TO '${DB_USER}'@'127.0.0.1';
FLUSH PRIVILEGES;
SQL

# ---------- 首次启动：建表 + 演示数据 ----------
if [ ! -f "$SEED_MARKER" ]; then
  case "${OPSFLOW_DEMO_DATA:-true}" in
    false|FALSE|0|no|off)
      log "OPSFLOW_DEMO_DATA=false：不导入演示数据（表结构和默认数据由应用启动时建立）"
      echo "skipped $(date '+%F %T')" > "$SEED_MARKER"
      ;;
    *)
      log "首次启动：建表并写入默认数据（Flyway）"
      # 口令通过环境变量传给迁移程序，仅在这个子进程内可见
      (
        export OPSFLOW_MIGRATE_URL="$JDBC_URL"
        export OPSFLOW_MIGRATE_USER="$DB_USER"
        export OPSFLOW_MIGRATE_PASSWORD="$DB_PASSWORD"
        exec gosu opsflow java -cp "$APP_JAR" \
          -Dloader.main=com.opsflow.config.DbMigrateCli \
          org.springframework.boot.loader.PropertiesLauncher migrate
      )
      exit_if_terminating
      log "导入演示数据（示例项目、环境、服务、流水线视图）"
      mysql_root --default-character-set=utf8mb4 "$DB_NAME" < /app/demo-data.sql
      echo "imported $(date '+%F %T')" > "$SEED_MARKER"
      ;;
  esac
else
  log "已初始化过（$(cat "$SEED_MARKER")），跳过演示数据导入"
fi
exit_if_terminating

# ---------- OpsFlow ----------
log "启动 OpsFlow ..."
(
  export DB_URL="$JDBC_URL"
  export DB_USERNAME="$DB_USER"
  export DB_PASSWORD
  cd /app
  # JAVA_OPTS 需按空格拆成多个参数
  # shellcheck disable=SC2086
  exec gosu opsflow java $JAVA_OPTS -jar "$APP_JAR"
) &
APP_PID=$!

log "就绪后访问 http://localhost:<映射端口>  初始账号 admin / admin（首次登录会被要求修改密码）"

# 任一进程退出，或收到停止信号，就把另一个也停掉，让容器整体退出
rc=0
wait -n "$APP_PID" "$MYSQLD_PID" || rc=$?
if [ "$TERMINATING" -eq 1 ]; then
  log "收到停止信号"
  rc=0
elif ! kill -0 "$MYSQLD_PID" 2>/dev/null; then
  log "MySQL 意外退出（退出码 ${rc}）"
  [ "$rc" -ne 0 ] || rc=1
else
  log "OpsFlow 已退出（退出码 ${rc}）"
fi
stop_all
exit "$rc"
