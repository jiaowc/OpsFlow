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
JAR_NAME="admin-1.0.0.jar"
# Maven 多模块项目的 JAR 文件在 admin/target/ 目录下
JAR_PATH="${PROJECT_DIR}/admin/target/${JAR_NAME}"
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
    print_error "JAR 文件不存在: $JAR_PATH"
    print_info "请先执行编译: mvn clean package -DskipTests"
    exit 1
fi

# 检查是否已在运行
if lsof -i :${PORT} > /dev/null 2>&1; then
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

# 等待启动
sleep 3

# 检查是否启动成功
if lsof -i :${PORT} > /dev/null 2>&1; then
    print_info "应用启动成功！"
    print_info "  - 访问地址: http://localhost:${PORT}"
    print_info "  - 日志文件: $LOG_FILE"
    print_info "  - 查看日志: tail -f $LOG_FILE"
else
    print_error "应用启动失败，请检查日志: $LOG_FILE"
    exit 1
fi