#!/bin/bash

# ==========================================
# OpsFlow 应用停止脚本
# ==========================================

set -e

# 颜色定义
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m'

# 配置变量
JAR_NAME="opsflow.jar"
PORT=8080
APP_NAME="OpsFlow"

print_info() {
    echo -e "${GREEN}[INFO]${NC} $1"
}

print_warning() {
    echo -e "${YELLOW}[WARNING]${NC} $1"
}

print_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

# 查找应用进程
find_app_pid() {
    ps aux | grep "[j]ava.*${JAR_NAME}" | awk '{print $2}' | head -1
}

# 停止应用
print_info "正在停止 ${APP_NAME} 应用..."

# 方法1: 通过端口查找进程
if lsof -i :${PORT} > /dev/null 2>&1; then
    local pid=$(lsof -ti :${PORT})
    if [ -n "$pid" ]; then
        print_info "找到运行中的进程（通过端口），PID: $pid"
        kill $pid 2>/dev/null || true
        sleep 2
        
        if ps -p $pid > /dev/null 2>&1; then
            print_warning "进程仍在运行，强制停止..."
            kill -9 $pid 2>/dev/null || true
            sleep 1
        fi
    fi
fi

# 方法2: 通过进程名查找
local pid=$(find_app_pid)
if [ -n "$pid" ]; then
    print_info "找到运行中的进程（通过进程名），PID: $pid"
    kill $pid 2>/dev/null || true
    sleep 2
    
    if ps -p $pid > /dev/null 2>&1; then
        print_warning "进程仍在运行，强制停止..."
        kill -9 $pid 2>/dev/null || true
        sleep 1
    fi
fi

# 最终检查
if lsof -i :${PORT} > /dev/null 2>&1 || [ -n "$(find_app_pid)" ]; then
    print_error "无法停止应用，请手动检查进程"
    exit 1
else
    print_info "应用已成功停止"
fi
