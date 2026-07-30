#!/bin/bash

# ==========================================
# OpsFlow 应用停止脚本
# 用法:
#   ./scripts/deploy/stop.sh           # 停止本地 java 进程
#   ./scripts/deploy/stop.sh --docker  # 停止 Docker 容器
# ==========================================

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=common.sh
source "${SCRIPT_DIR}/common.sh"

parse_deploy_args "$@"

echo ""
echo "=========================================="
echo "  ${APP_NAME} 停止 (${DEPLOY_MODE})"
echo "=========================================="
echo ""

if [ "${DEPLOY_MODE}" = "docker" ]; then
    check_command docker
    stop_docker_app
    if port_in_use; then
        print_warning "端口 ${PORT} 仍被占用（可能是本地 java 进程）"
        print_info "可再执行: ./scripts/deploy/stop.sh --local"
    else
        print_success "应用已成功停止"
    fi
    exit 0
fi

# ---------- 本地模式 ----------
print_info "正在停止 ${APP_NAME} 应用（本地模式）..."

# 方法1: 通过端口查找进程
if port_in_use; then
    pid="$(lsof -tiTCP:"${PORT}" -sTCP:LISTEN 2>/dev/null | head -1 || true)"
    if [ -n "${pid}" ]; then
        print_info "找到运行中的进程（通过端口），PID: ${pid}"
        kill "${pid}" 2>/dev/null || true
        sleep 2
        if ps -p "${pid}" >/dev/null 2>&1; then
            print_warning "进程仍在运行，强制停止..."
            kill -9 "${pid}" 2>/dev/null || true
            sleep 1
        fi
    fi
fi

# 方法2: 通过进程名查找
pid="$(find_app_pid || true)"
if [ -n "${pid}" ]; then
    print_info "找到运行中的进程（通过进程名），PID: ${pid}"
    kill "${pid}" 2>/dev/null || true
    sleep 2
    if ps -p "${pid}" >/dev/null 2>&1; then
        print_warning "进程仍在运行，强制停止..."
        kill -9 "${pid}" 2>/dev/null || true
        sleep 1
    fi
fi

if port_in_use || [ -n "$(find_app_pid || true)" ]; then
    print_error "无法停止应用，请手动检查进程"
    print_info "若使用 Docker 启动，请执行: ./scripts/deploy/stop.sh --docker"
    exit 1
fi

print_success "应用已成功停止"
