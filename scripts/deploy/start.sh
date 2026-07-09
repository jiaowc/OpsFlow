#!/bin/bash

# ==========================================
# OpsFlow 应用启动脚本
# ==========================================

set -e

# 颜色定义
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m'

# 配置变量
PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
JAR_NAME="opsflow.jar"
# 统一产物：项目根 target/opsflow.jar（mvn package 后由 app 模块复制）
JAR_PATH="${PROJECT_DIR}/target/${JAR_NAME}"
LOG_FILE="${PROJECT_DIR}/logs/app.log"
CONFIG_FILE="${PROJECT_DIR}/config/application.yml"
PORT=8080
APP_NAME="OpsFlow"

# Java 配置
JAVA_8_HOME="${JAVA_HOME:-/Library/Java/JavaVirtualMachines/jdk8u472/Contents/Home}"

print_info() {
    echo -e "${GREEN}[INFO]${NC} $1"
}

print_warning() {
    echo -e "${YELLOW}[WARNING]${NC} $1"
}

print_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

# 检查 JAR 文件是否存在
if [ ! -f "$JAR_PATH" ]; then
    alt_jar_path="${PROJECT_DIR}/app/target/${JAR_NAME}"
    if [ -f "$alt_jar_path" ]; then
        JAR_PATH="$alt_jar_path"
    else
        print_error "JAR 文件不存在: $JAR_PATH"
        print_info "请先执行: mvn clean package -DskipTests"
        exit 1
    fi
fi

# 检查是否已在运行（仅检测 LISTEN 状态，避免 CLOSED 连接误报）
if lsof -iTCP:${PORT} -sTCP:LISTEN > /dev/null 2>&1; then
    print_warning "端口 ${PORT} 已被占用，应用可能已在运行"
    print_info "如需重启，请使用: ./scripts/deploy/restart.sh"
    exit 1
fi

# 设置 JAVA_HOME
if [ -d "$JAVA_8_HOME" ]; then
    export JAVA_HOME="${JAVA_8_HOME}"
    export PATH="${JAVA_HOME}/bin:${PATH}"
fi

# 确保日志目录存在
mkdir -p "$(dirname "$LOG_FILE")"

# 增量数据库迁移（可选，拉取新代码且有表结构变更时执行）
if [ "${OPSFLOW_RUN_MIGRATIONS:-0}" = "1" ] && [ -f "${PROJECT_DIR}/scripts/database/run_migrations.sh" ]; then
    print_info "执行数据库迁移 (OPSFLOW_RUN_MIGRATIONS=1)..."
    bash "${PROJECT_DIR}/scripts/database/run_migrations.sh" || print_warning "数据库迁移失败，请手动检查"
fi

# 备份旧日志
if [ -f "$LOG_FILE" ]; then
    mv "$LOG_FILE" "${LOG_FILE}.$(date +%Y%m%d_%H%M%S).bak" 2>/dev/null || true
fi

# 启动应用
cd "$PROJECT_DIR"
print_info "正在启动 ${APP_NAME}..."

if [ -f "$CONFIG_FILE" ]; then
    print_info "使用外部配置文件: $CONFIG_FILE"
    nohup java -jar "$JAR_PATH" --spring.config.location=file:"$CONFIG_FILE" > "$LOG_FILE" 2>&1 &
else
    print_warning "外部配置文件不存在: $CONFIG_FILE"
    print_info "使用默认配置文件（项目内）"
    nohup java -jar "$JAR_PATH" > "$LOG_FILE" 2>&1 &
fi

# 等待启动（Spring Boot 冷启动通常需要 10 秒以上）
MAX_WAIT=30
WAITED=0
while [ $WAITED -lt $MAX_WAIT ]; do
    if lsof -iTCP:${PORT} -sTCP:LISTEN > /dev/null 2>&1; then
        break
    fi
    sleep 2
    WAITED=$((WAITED + 2))
done

# 检查是否启动成功
if lsof -iTCP:${PORT} -sTCP:LISTEN > /dev/null 2>&1; then
    print_info "应用启动成功！"
    print_info "  - 访问地址: http://localhost:${PORT}"
    print_info "  - 日志文件: $LOG_FILE"
    print_info "  - 查看日志: tail -f $LOG_FILE"
else
    print_error "应用启动失败，请检查日志: $LOG_FILE"
    exit 1
fi