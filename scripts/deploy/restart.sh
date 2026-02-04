#!/bin/bash

# ==========================================
# OpsFlow 应用重启脚本
# ==========================================

set -e  # 遇到错误立即退出

# 颜色定义
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

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
JAVA_8_HOME="/Library/Java/JavaVirtualMachines/jdk8u472/Contents/Home"
JAVA_HOME="${JAVA_8_HOME}"

# 函数：打印带颜色的消息
print_info() {
    echo -e "${BLUE}[INFO]${NC} $1"
}

print_success() {
    echo -e "${GREEN}[SUCCESS]${NC} $1"
}

print_warning() {
    echo -e "${YELLOW}[WARNING]${NC} $1"
}

print_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

# 函数：检查命令是否存在
check_command() {
    if ! command -v $1 &> /dev/null; then
        print_error "$1 命令未找到，请先安装"
        exit 1
    fi
}

# 函数：查找应用进程
find_app_pid() {
    ps aux | grep "[j]ava.*${JAR_NAME}" | awk '{print $2}' | head -1
}

# 函数：停止应用
stop_app() {
    print_info "正在停止 ${APP_NAME} 应用..."
    
    local pid=$(find_app_pid)
    if [ -n "$pid" ]; then
        print_info "找到运行中的进程，PID: $pid"
        
        # 先尝试优雅停止
        kill $pid 2>/dev/null || true
        sleep 2
        
        # 检查进程是否还在运行
        if ps -p $pid > /dev/null 2>&1; then
            print_warning "进程仍在运行，强制停止..."
            kill -9 $pid 2>/dev/null || true
            sleep 1
        fi
        
        # 再次检查
        if ps -p $pid > /dev/null 2>&1; then
            print_error "无法停止进程 $pid"
            return 1
        else
            print_success "应用已停止"
        fi
    else
        print_info "没有运行中的应用"
    fi
    
    return 0
}

# 函数：编译项目
build_project() {
    print_info "正在编译打包项目..."
    
    cd "$PROJECT_DIR"
    
    # 检查 Maven 是否可用
    if ! command -v mvn &> /dev/null; then
        print_error "Maven 未安装或不在 PATH 中"
        exit 1
    fi
    
    # 设置 JAVA_HOME
    export JAVA_HOME="${JAVA_8_HOME}"
    export PATH="${JAVA_HOME}/bin:${PATH}"
    
    print_info "使用 Java: $(java -version 2>&1 | head -1)"
    
    # 执行编译
    if mvn clean package -DskipTests -q; then
        print_success "编译成功"
    else
        print_error "编译失败，请检查错误信息"
        exit 1
    fi
    
    # 检查 JAR 文件是否存在
    if [ ! -f "$JAR_PATH" ]; then
        # 尝试在根目录 target/ 查找（兼容其他可能的构建方式）
        local alt_jar_path="${PROJECT_DIR}/target/${JAR_NAME}"
        if [ -f "$alt_jar_path" ]; then
            JAR_PATH="$alt_jar_path"
            print_info "在根目录 target/ 找到 JAR 文件"
        else
        print_error "JAR 文件不存在: $JAR_PATH"
            print_info "请先执行编译: mvn clean package -DskipTests"
            print_info "JAR 文件应该在: ${PROJECT_DIR}/admin/target/${JAR_NAME}"
        exit 1
        fi
    fi
    
    print_info "JAR 文件位置: $JAR_PATH"
    print_info "JAR 文件大小: $(du -h "$JAR_PATH" | cut -f1)"
}

# 函数：启动应用
start_app() {
    print_info "正在启动 ${APP_NAME} 应用..."
    
    cd "$PROJECT_DIR"
    
    # 设置 JAVA_HOME
    export JAVA_HOME="${JAVA_8_HOME}"
    export PATH="${JAVA_HOME}/bin:${PATH}"
    
    # 确保日志目录存在
    mkdir -p "$(dirname "$LOG_FILE")"
    
    # 备份旧日志
    if [ -f "$LOG_FILE" ]; then
        mv "$LOG_FILE" "${LOG_FILE}.$(date +%Y%m%d_%H%M%S).bak" 2>/dev/null || true
    fi
    
    # 启动应用（优先使用外部配置文件）
    if [ -f "$CONFIG_FILE" ]; then
        print_info "使用外部配置文件: $CONFIG_FILE"
        nohup java -jar "$JAR_PATH" --spring.config.location=file:"$CONFIG_FILE" > "$LOG_FILE" 2>&1 &
    else
        print_warning "外部配置文件不存在: $CONFIG_FILE"
        print_info "使用默认配置文件（项目内）"
        nohup java -jar "$JAR_PATH" > "$LOG_FILE" 2>&1 &
    fi
    
    local new_pid=$!
    print_info "启动命令已执行，新进程 PID: $new_pid"
    
    # 等待应用启动
    sleep 3
    
    # 检查进程是否还在运行
    local current_pid=$(find_app_pid)
    if [ -n "$current_pid" ]; then
        print_success "应用已启动，进程 ID: $current_pid"
        return 0
    else
        print_error "应用启动失败，进程已退出"
        print_info "查看日志: tail -50 $LOG_FILE"
        return 1
    fi
}

# 函数：检查应用状态
check_app_status() {
    print_info "正在检查应用状态..."
    
    local pid=$(find_app_pid)
    if [ -z "$pid" ]; then
        print_error "应用进程未找到"
        return 1
    fi
    
    print_success "进程运行中，PID: $pid"
    
    # 检查端口
    sleep 2
    if lsof -i :${PORT} > /dev/null 2>&1; then
        print_success "端口 ${PORT} 正在监听"
    else
        print_warning "端口 ${PORT} 未监听，应用可能还在启动中"
    fi
    
    # 检查日志中的错误
    if [ -f "$LOG_FILE" ]; then
        local error_count=$(grep -i "error\|exception" "$LOG_FILE" | wc -l | tr -d ' ')
        if [ "$error_count" -gt 0 ]; then
            print_warning "日志中发现 $error_count 个错误/异常，请检查日志"
        fi
    fi
    
    # 测试 API
    print_info "正在测试 API 连接..."
    sleep 3
    
    local response=$(curl -s -w "\n%{http_code}" http://localhost:${PORT}/api/build/envs 2>&1)
    local http_code=$(echo "$response" | tail -1)
    local body=$(echo "$response" | sed '$d')
    
    if [ "$http_code" = "200" ]; then
        print_success "API 响应正常 (HTTP $http_code)"
        if [ -n "$body" ] && [ "$body" != "null" ]; then
            print_info "响应预览: $(echo "$body" | head -c 100)..."
        fi
    else
        print_warning "API 响应异常 (HTTP $http_code)"
        if [ -n "$body" ]; then
            print_info "响应内容: $(echo "$body" | head -c 200)"
        fi
    fi
}

# 主函数
main() {
    echo ""
    echo "=========================================="
    echo "  ${APP_NAME} 应用重启脚本"
    echo "=========================================="
    echo ""
    
    # 检查必要的命令
    check_command "java"
    check_command "mvn"
    check_command "curl"
    
    # 检查项目目录
    if [ ! -d "$PROJECT_DIR" ]; then
        print_error "项目目录不存在: $PROJECT_DIR"
        exit 1
    fi
    
    # 检查 Java 8 是否存在
    if [ ! -d "$JAVA_8_HOME" ]; then
        print_error "Java 8 未找到: $JAVA_8_HOME"
        print_info "请检查 Java 8 安装路径"
        exit 1
    fi
    
    # 执行重启流程
    stop_app
    echo ""
    
    build_project
    echo ""
    
    if start_app; then
        echo ""
        check_app_status
        echo ""
        echo "=========================================="
        echo "  重启完成！"
        echo "=========================================="
        echo ""
        echo "应用信息："
        echo "  - 进程 ID: $(find_app_pid)"
        echo "  - 访问地址: http://localhost:${PORT}"
        echo "  - 日志文件: $LOG_FILE"
        echo ""
        echo "常用命令："
        echo "  - 查看日志: tail -100f $LOG_FILE"
        echo "  - 停止应用: kill \$(ps aux | grep '[j]ava.*${JAR_NAME}' | awk '{print \$2}')"
        echo "  - 查看进程: ps aux | grep '[j]ava.*${JAR_NAME}'"
        echo ""
    else
        echo ""
        print_error "重启失败！"
        echo ""
        echo "故障排查："
        echo "  1. 查看日志: tail -100 $LOG_FILE"
        echo "  2. 检查端口占用: lsof -i :${PORT}"
        echo "  3. 检查 Java 版本: java -version"
        echo "  4. 检查数据库连接"
        echo ""
        exit 1
    fi
}

# 执行主函数
main "$@"
