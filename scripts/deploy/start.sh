#!/bin/bash

# ==========================================
# OpsFlow 应用启动脚本
# 用法:
#   ./scripts/deploy/start.sh           # 本地 java -jar
#   ./scripts/deploy/start.sh --docker  # Docker 容器
# ==========================================

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=common.sh
source "${SCRIPT_DIR}/common.sh"

parse_deploy_args "$@"
ensure_active_profile_env

echo ""
echo "=========================================="
echo "  ${APP_NAME} 启动 (${DEPLOY_MODE})"
echo "=========================================="
echo ""

if [ "${DEPLOY_MODE}" = "docker" ]; then
    check_command docker

    # 无镜像或强制重建时：先确保有 jar 再 build
    if ! docker image inspect "${DOCKER_IMAGE}" >/dev/null 2>&1; then
        print_warning "镜像不存在，开始构建: ${DOCKER_IMAGE}"
        if [ ! -f "${JAR_PATH}" ]; then
            print_error "JAR 不存在，请先编译: mvn clean package -DskipTests"
            print_info "或使用: ./scripts/deploy/restart.sh --docker（会自动编译）"
            exit 1
        fi
        build_docker_image
    fi

    if start_docker_app; then
        print_info "等待服务就绪..."
        if wait_for_port 40; then
            print_success "应用启动成功！"
            print_docker_tips
        else
            print_warning "端口 ${PORT} 尚未监听，容器可能仍在启动"
            print_info "查看日志: docker logs -f ${DOCKER_CONTAINER}"
        fi
    else
        exit 1
    fi
    exit 0
fi

# ---------- 本地模式 ----------
check_command java
setup_java8

if [ ! -f "${JAR_PATH}" ]; then
    print_error "JAR 文件不存在: ${JAR_PATH}"
    print_info "请先执行: mvn clean package -DskipTests"
    print_info "或使用 Docker: ./scripts/deploy/start.sh --docker"
    exit 1
fi

if port_in_use; then
    print_warning "端口 ${PORT} 已被占用，应用可能已在运行"
    print_info "如需重启，请使用: ./scripts/deploy/restart.sh"
    exit 1
fi

mkdir -p "$(dirname "${LOG_FILE}")"

if [ "${OPSFLOW_RUN_MIGRATIONS:-0}" = "1" ] && [ -f "${PROJECT_DIR}/scripts/database/run_migrations.sh" ]; then
    print_info "执行数据库迁移 (OPSFLOW_RUN_MIGRATIONS=1)..."
    bash "${PROJECT_DIR}/scripts/database/run_migrations.sh" || print_warning "数据库迁移失败，请手动检查"
fi

if [ -f "${LOG_FILE}" ]; then
    mv "${LOG_FILE}" "${LOG_FILE}.$(date +%Y%m%d_%H%M%S).bak" 2>/dev/null || true
fi

cd "${PROJECT_DIR}"
print_info "正在启动 ${APP_NAME}（本地模式）..."

if [ -d "${PROJECT_DIR}/config" ]; then
    print_info "使用外部配置目录: ${PROJECT_DIR}/config"
    print_info "激活环境: ${SPRING_PROFILES_ACTIVE:-默认(application.yml)}"
    nohup java -jar "${JAR_PATH}" --spring.config.additional-location=file:"${PROJECT_DIR}/config/" > "${LOG_FILE}" 2>&1 &
else
    print_warning "外部配置目录不存在: ${PROJECT_DIR}/config"
    print_info "使用默认配置文件（项目内）"
    nohup java -jar "${JAR_PATH}" > "${LOG_FILE}" 2>&1 &
fi

if wait_for_port 30; then
    print_success "应用启动成功！"
    print_info "  - 访问地址: http://localhost:${PORT}"
    print_info "  - 日志文件: ${LOG_FILE}"
    print_info "  - 查看日志: tail -f ${LOG_FILE}"
else
    print_error "应用启动失败，请检查日志: ${LOG_FILE}"
    exit 1
fi
