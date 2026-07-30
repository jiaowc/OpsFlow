#!/bin/bash

# ==========================================
# OpsFlow 应用重启脚本
# 用法:
#   ./scripts/deploy/restart.sh           # 本地编译 + java -jar
#   ./scripts/deploy/restart.sh --docker  # 构建镜像 + 重启容器
# ==========================================

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=common.sh
source "${SCRIPT_DIR}/common.sh"

parse_deploy_args "$@"
ensure_active_profile_env

# 函数：本地编译
build_project() {
    print_info "正在编译打包项目..."
    cd "${PROJECT_DIR}"

    check_command mvn
    setup_java8

    local maven_profile="${OPSFLOW_MAVEN_PROFILE:-${SPRING_PROFILES_ACTIVE:-}}"
    local mvn_args=(clean package -DskipTests)
    if [ -n "${maven_profile}" ]; then
        mvn_args+=(-P"${maven_profile}")
        print_info "Maven 打包环境: ${maven_profile}"
    else
        print_info "Maven 打包环境: 默认 (application.yml)"
    fi
    if mvn "${mvn_args[@]}"; then
        print_success "编译成功"
    else
        print_error "编译失败，请检查错误信息"
        print_info "若仍报 JCTree/Lombok 相关错误，确认 java -version 为 1.8"
        exit 1
    fi

    if [ ! -f "${JAR_PATH}" ]; then
        print_error "JAR 文件不存在: ${JAR_PATH}"
        exit 1
    fi

    print_info "JAR 文件位置: ${JAR_PATH}"
    print_info "JAR 文件大小: $(du -h "${JAR_PATH}" | cut -f1)"
}

# 函数：本地停止
stop_local_app() {
    print_info "正在停止 ${APP_NAME} 应用（本地）..."
    local pid
    pid="$(find_app_pid || true)"
    if [ -n "${pid}" ]; then
        print_info "找到运行中的进程，PID: ${pid}"
        kill "${pid}" 2>/dev/null || true
        sleep 2
        if ps -p "${pid}" >/dev/null 2>&1; then
            print_warning "进程仍在运行，强制停止..."
            kill -9 "${pid}" 2>/dev/null || true
            sleep 1
        fi
        if ps -p "${pid}" >/dev/null 2>&1; then
            print_error "无法停止进程 ${pid}"
            return 1
        fi
        print_success "应用已停止"
    else
        print_info "没有运行中的本地应用"
    fi
    return 0
}

# 函数：本地启动
start_local_app() {
    print_info "正在启动 ${APP_NAME} 应用（本地）..."
    cd "${PROJECT_DIR}"

    setup_java8

    mkdir -p "$(dirname "${LOG_FILE}")"
    if [ -f "${LOG_FILE}" ]; then
        mv "${LOG_FILE}" "${LOG_FILE}.$(date +%Y%m%d_%H%M%S).bak" 2>/dev/null || true
    fi

    if [ -d "${PROJECT_DIR}/config" ]; then
        print_info "使用外部配置目录: ${PROJECT_DIR}/config"
        print_info "激活环境: ${SPRING_PROFILES_ACTIVE:-默认(application.yml)}"
        nohup java -jar "${JAR_PATH}" --spring.config.additional-location=file:"${PROJECT_DIR}/config/" > "${LOG_FILE}" 2>&1 &
    else
        print_warning "外部配置目录不存在: ${PROJECT_DIR}/config"
        print_info "使用默认配置文件（项目内）"
        nohup java -jar "${JAR_PATH}" > "${LOG_FILE}" 2>&1 &
    fi

    sleep 3
    local current_pid
    current_pid="$(find_app_pid || true)"
    if [ -n "${current_pid}" ]; then
        print_success "应用已启动，进程 ID: ${current_pid}"
        return 0
    fi
    print_error "应用启动失败，进程已退出"
    print_info "查看日志: tail -50 ${LOG_FILE}"
    return 1
}

check_app_status() {
    print_info "正在检查应用状态..."
    local status_ok=0
    if [ "${DEPLOY_MODE}" = "docker" ]; then
        if docker_container_running; then
            print_success "容器运行中: ${DOCKER_CONTAINER}"
        else
            print_error "容器未运行"
            return 1
        fi
    else
        local pid
        pid="$(find_app_pid || true)"
        if [ -z "${pid}" ]; then
            print_error "应用进程未找到"
            return 1
        fi
        print_success "进程运行中，PID: ${pid}"
    fi

    print_info "等待端口 ${PORT} 就绪..."
    if wait_for_port 30; then
        print_success "端口 ${PORT} 正在监听"
        status_ok=1
    else
        print_warning "端口 ${PORT} 在等待窗口内仍未监听"
    fi

    print_info "正在测试健康检查接口..."
    local response http_code body attempts
    attempts=0
    while [ "${attempts}" -lt 10 ]; do
        response="$(curl -s -m 3 -w "\n%{http_code}" "http://localhost:${PORT}/api/health" 2>&1 || true)"
        http_code="$(echo "${response}" | tail -1)"
        body="$(echo "${response}" | sed '$d')"
        if [ "${http_code}" = "200" ]; then
            break
        fi
        attempts=$((attempts + 1))
        sleep 2
    done

    if [ "${http_code}" = "200" ]; then
        print_success "API 响应正常 (HTTP ${http_code})"
        status_ok=1
    else
        print_warning "健康检查响应异常 (HTTP ${http_code})"
        if [ -n "${body}" ]; then
            print_info "响应内容: $(echo "${body}" | head -c 200)"
        fi
    fi

    if [ "${DEPLOY_MODE}" != "docker" ] && [ -z "$(find_app_pid || true)" ]; then
        print_error "应用进程已退出，请检查日志: ${LOG_FILE}"
        return 1
    fi

    if [ "${status_ok}" -eq 0 ]; then
        return 1
    fi

    return 0
}

main() {
    echo ""
    echo "=========================================="
    echo "  ${APP_NAME} 应用重启脚本 (${DEPLOY_MODE})"
    echo "=========================================="
    echo ""

    if [ ! -d "${PROJECT_DIR}" ]; then
        print_error "项目目录不存在: ${PROJECT_DIR}"
        exit 1
    fi

    if [ "${DEPLOY_MODE}" = "docker" ]; then
        check_command docker
        check_command curl
        check_command mvn

        stop_docker_app
        # 同时停掉可能占用端口的本地进程
        stop_local_app || true
        echo ""

        # 先本地编译出 jar，再打镜像
        build_project
        echo ""
        build_docker_image
        echo ""

        if start_docker_app; then
            echo ""
            wait_for_port 40 || true
            check_app_status || true
            echo ""
            echo "=========================================="
            echo "  Docker 重启完成！"
            echo "=========================================="
            echo ""
            print_docker_tips
            echo "常用命令："
            echo "  - 查看日志: docker logs -f ${DOCKER_CONTAINER}"
            echo "  - 停止容器: ./scripts/deploy/stop.sh --docker"
            echo "  - 进入容器: docker exec -it ${DOCKER_CONTAINER} sh"
            echo ""
        else
            print_error "Docker 重启失败！"
            echo "  查看日志: docker logs ${DOCKER_CONTAINER}"
            exit 1
        fi
        exit 0
    fi

    # ---------- 本地模式 ----------
    check_command java
    check_command mvn
    check_command curl

    setup_java8

    # Docker 容器若占用端口也一并停掉
    if command -v docker >/dev/null 2>&1 && docker_container_running; then
        print_warning "检测到 Docker 容器在运行，先停止容器以免端口冲突"
        stop_docker_app
    fi

    stop_local_app
    echo ""

    build_project
    echo ""

    if start_local_app; then
        echo ""
        if ! check_app_status; then
            echo ""
            print_error "重启失败！"
            echo ""
            echo "故障排查："
            echo "  1. 查看日志: tail -100 ${LOG_FILE}"
            echo "  2. 检查 profile: echo \${SPRING_PROFILES_ACTIVE:-local}"
            echo "  3. 检查数据库连接配置是否在 application-\${SPRING_PROFILES_ACTIVE:-local}.yml"
            echo ""
            exit 1
        fi
        echo ""
        echo "=========================================="
        echo "  重启完成！"
        echo "=========================================="
        echo ""
        echo "应用信息："
        echo "  - 模式: 本地"
        echo "  - 进程 ID: $(find_app_pid)"
        echo "  - 访问地址: http://localhost:${PORT}"
        echo "  - 日志文件: ${LOG_FILE}"
        echo ""
        echo "常用命令："
        echo "  - 查看日志: tail -100f ${LOG_FILE}"
        echo "  - 停止应用: ./scripts/deploy/stop.sh"
        echo "  - Docker 部署: ./scripts/deploy/restart.sh --docker"
        echo ""
    else
        echo ""
        print_error "重启失败！"
        echo ""
        echo "故障排查："
        echo "  1. 查看日志: tail -100 ${LOG_FILE}"
        echo "  2. 检查端口占用: lsof -i :${PORT}"
        echo "  3. 检查 Java 版本: java -version"
        echo "  4. 检查数据库连接"
        echo ""
        exit 1
    fi
}

main "$@"
