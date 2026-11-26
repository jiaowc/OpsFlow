#!/usr/bin/env bash
# 手动执行数据库迁移（Flyway），不启动应用。
#
# 什么时候用：
#   - 关闭了启动时自动迁移（OPSFLOW_DB_MIGRATE=false）的环境，升级前先跑一次；
#   - 想在启动前先看看这次升级会改什么。
#
#   bash scripts/database/migrate.sh --info            # 只列出已记录和待执行的版本，不改库
#   bash scripts/database/migrate.sh                   # 执行待执行的版本
#   bash scripts/database/migrate.sh --profile=prod    # 指定环境，连接解析规则见 db_env.sh
#   bash scripts/database/migrate.sh --repair          # 某个版本执行失败后：处理完原因，清除失败记录以便重试
#
# 版本文件打在 jar 里（core/src/main/resources/db/migration），所以要先有 app/target/opsflow.jar，
# 或用 OPSFLOW_JAR 指定 jar 路径。连接信息与应用一致；密码通过环境变量传给 Java，不出现在命令行。
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "${SCRIPT_DIR}/../.." && pwd)"
PROFILE="${OPSFLOW_PROFILE:-}"
COMMAND="migrate"

for arg in "$@"; do
  case "$arg" in
    --profile=*) PROFILE="${arg#--profile=}" ;;
    --info) COMMAND="info" ;;
    --repair) COMMAND="repair" ;;
    -h|--help)
      echo "用法: $0 [--info|--repair] [--profile=dev|test|prod]"
      exit 0
      ;;
    *)
      echo "未知参数: $arg" >&2
      exit 2
      ;;
  esac
done

JAR="${OPSFLOW_JAR:-${PROJECT_DIR}/app/target/opsflow.jar}"
if [ ! -f "$JAR" ]; then
  echo "找不到 $JAR，请先打包（mvn clean package -DskipTests）或用 OPSFLOW_JAR 指定 jar 路径" >&2
  exit 1
fi

# shellcheck source=db_env.sh
source "${SCRIPT_DIR}/db_env.sh"
resolve_db_env "$PROFILE" || exit 1

# 应用要求 JDK 17；OPSFLOW_JAVA_HOME 与 scripts/deploy 的约定一致
JAVA_BIN="java"
if [ -n "${OPSFLOW_JAVA_HOME:-}" ] && [ -x "${OPSFLOW_JAVA_HOME}/bin/java" ]; then
  JAVA_BIN="${OPSFLOW_JAVA_HOME}/bin/java"
elif [ -x /opt/homebrew/opt/openjdk@17/bin/java ]; then
  JAVA_BIN="/opt/homebrew/opt/openjdk@17/bin/java"
fi

echo "迁移目标: ${DB_USER}@${DB_HOST}:${DB_PORT}/${DB_NAME}  (${DB_CONFIG_SOURCE})"
echo "jar:      ${JAR}"
echo

export OPSFLOW_MIGRATE_URL="jdbc:mysql://${DB_HOST}:${DB_PORT}/${DB_NAME}?useUnicode=true&characterEncoding=utf8&connectionCollation=utf8mb4_unicode_ci&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true&useSSL=false"
export OPSFLOW_MIGRATE_USER="$DB_USER"
export OPSFLOW_MIGRATE_PASSWORD="$DB_PASS"

exec "$JAVA_BIN" -cp "$JAR" -Dloader.main=com.opsflow.config.DbMigrateCli \
  org.springframework.boot.loader.PropertiesLauncher "$COMMAND"
