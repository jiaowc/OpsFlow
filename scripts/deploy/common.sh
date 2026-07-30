#!/bin/bash
# ==========================================
# OpsFlow 部署脚本公共配置与函数
# 由 start.sh / stop.sh / restart.sh source
# ==========================================

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
JAR_NAME="opsflow.jar"
JAR_PATH="${PROJECT_DIR}/target/${JAR_NAME}"
LOG_FILE="${PROJECT_DIR}/logs/app.log"
CONFIG_FILE="${PROJECT_DIR}/config/application.yml"
PORT="${OPSFLOW_PORT:-8080}"
APP_NAME="OpsFlow"

# Docker 相关
DOCKER_IMAGE="${OPSFLOW_DOCKER_IMAGE:-opsflow:1.0.0}"
DOCKER_CONTAINER="${OPSFLOW_DOCKER_CONTAINER:-opsflow}"
DOCKER_NETWORK="${OPSFLOW_DOCKER_NETWORK:-}"

# Java（本地编译/运行必须用 JDK 8；不要直接沿用环境里的 JAVA_HOME，可能是 17/21）
OPSFLOW_JAVA8_CANDIDATES=(
    "${OPSFLOW_JAVA_HOME:-}"
    "/Library/Java/JavaVirtualMachines/jdk8u472/Contents/Home"
    "/Library/Java/JavaVirtualMachines/temurin-8.jdk/Contents/Home"
    "/Library/Java/JavaVirtualMachines/adoptopenjdk-8.jdk/Contents/Home"
)
JAVA_8_HOME=""

resolve_java8_home() {
    local candidate
    for candidate in "${OPSFLOW_JAVA8_CANDIDATES[@]}"; do
        if [ -n "${candidate}" ] && [ -x "${candidate}/bin/java" ]; then
            if "${candidate}/bin/java" -version 2>&1 | grep -Eq 'version "1\.8|version "8'; then
                JAVA_8_HOME="${candidate}"
                return 0
            fi
        fi
    done
    if command -v /usr/libexec/java_home >/dev/null 2>&1; then
        candidate="$(/usr/libexec/java_home -v 1.8 2>/dev/null || true)"
        if [ -n "${candidate}" ] && [ -x "${candidate}/bin/java" ]; then
            JAVA_8_HOME="${candidate}"
            return 0
        fi
    fi
    return 1
}

setup_java8() {
    if ! resolve_java8_home; then
        print_error "未找到 JDK 8，无法编译（当前 Lombok 与 JDK 17/21 不兼容）"
        print_info "请安装 JDK 8，或设置: export OPSFLOW_JAVA_HOME=/path/to/jdk8"
        print_info "常见路径: /Library/Java/JavaVirtualMachines/jdk8u472/Contents/Home"
        exit 1
    fi
    export JAVA_HOME="${JAVA_8_HOME}"
    export PATH="${JAVA_HOME}/bin:${PATH}"
    print_info "使用 JDK 8: ${JAVA_HOME}"
    print_info "Java 版本: $(java -version 2>&1 | head -1)"
}

# 部署模式: local | docker（可用参数 --docker / --local 或环境变量覆盖）
DEPLOY_MODE="${OPSFLOW_DEPLOY_MODE:-local}"

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

print_info()    { echo -e "${BLUE}[INFO]${NC} $1"; }
print_success() { echo -e "${GREEN}[SUCCESS]${NC} $1"; }
print_warning() { echo -e "${YELLOW}[WARNING]${NC} $1"; }
print_error()   { echo -e "${RED}[ERROR]${NC} $1"; }

resolve_active_profile() {
    if [ -n "${SPRING_PROFILES_ACTIVE:-}" ]; then
        echo "${SPRING_PROFILES_ACTIVE}"
        return
    fi
    if [ -n "${OPSFLOW_MAVEN_PROFILE:-}" ]; then
        echo "${OPSFLOW_MAVEN_PROFILE}"
        return
    fi
    # 空字符串 = 不强制 profile，仅使用 application.yml
    echo ""
}

ensure_active_profile_env() {
    local profile
    profile="$(resolve_active_profile)"
    if [ -n "${profile}" ]; then
        export SPRING_PROFILES_ACTIVE="${profile}"
    else
        unset SPRING_PROFILES_ACTIVE
    fi
}

parse_deploy_args() {
    while [ $# -gt 0 ]; do
        case "$1" in
            --docker|-d)
                DEPLOY_MODE="docker"
                ;;
            --local|-l)
                DEPLOY_MODE="local"
                ;;
            --help|-h)
                echo "用法: $0 [--docker|--local]"
                echo "  --docker, -d   使用 Docker 镜像运行"
                echo "  --local,  -l   使用本地 java -jar 运行（默认）"
                echo "环境变量:"
                echo "  OPSFLOW_DEPLOY_MODE=docker|local"
                echo "  OPSFLOW_DOCKER_IMAGE=opsflow:1.0.0"
                echo "  OPSFLOW_DOCKER_CONTAINER=opsflow"
                echo "  OPSFLOW_PORT=8080"
                exit 0
                ;;
            *)
                print_warning "忽略未知参数: $1"
                ;;
        esac
        shift
    done
}

check_command() {
    if ! command -v "$1" >/dev/null 2>&1; then
        print_error "$1 命令未找到，请先安装"
        exit 1
    fi
}

find_app_pid() {
    ps aux | grep "[j]ava.*${JAR_NAME}" | awk '{print $2}' | head -1
}

port_in_use() {
    lsof -iTCP:"${PORT}" -sTCP:LISTEN >/dev/null 2>&1
}

# ---------- Docker ----------

docker_container_running() {
    docker ps --format '{{.Names}}' 2>/dev/null | grep -qx "${DOCKER_CONTAINER}"
}

docker_container_exists() {
    docker ps -a --format '{{.Names}}' 2>/dev/null | grep -qx "${DOCKER_CONTAINER}"
}

stop_docker_app() {
    print_info "正在停止 Docker 容器 ${DOCKER_CONTAINER}..."
    if docker_container_running; then
        docker stop "${DOCKER_CONTAINER}" >/dev/null
        print_success "容器已停止"
    else
        print_info "没有运行中的容器"
    fi
    if docker_container_exists; then
        docker rm "${DOCKER_CONTAINER}" >/dev/null 2>&1 || true
        print_info "已移除容器 ${DOCKER_CONTAINER}"
    fi
}

build_docker_image() {
    print_info "正在基于本地 JAR 构建 Docker 镜像 ${DOCKER_IMAGE}..."
    check_command docker
    cd "${PROJECT_DIR}"

    if [ ! -f "${JAR_PATH}" ]; then
        print_error "JAR 不存在: ${JAR_PATH}"
        print_info "请先执行: mvn clean package -DskipTests"
        exit 1
    fi

    print_info "使用 JAR: ${JAR_PATH} ($(du -h "${JAR_PATH}" | cut -f1))"

    if docker build -t "${DOCKER_IMAGE}" .; then
        print_success "镜像构建成功: ${DOCKER_IMAGE}"
    else
        print_error "镜像构建失败"
        exit 1
    fi
}

start_docker_app() {
    print_info "正在启动 Docker 容器 ${DOCKER_CONTAINER}..."
    check_command docker
    cd "${PROJECT_DIR}"

    if docker_container_running; then
        print_warning "容器已在运行: ${DOCKER_CONTAINER}"
        print_info "如需重启，请使用: ./scripts/deploy/restart.sh --docker"
        return 1
    fi

    if docker_container_exists; then
        docker rm "${DOCKER_CONTAINER}" >/dev/null 2>&1 || true
    fi

    mkdir -p "${PROJECT_DIR}/logs" "${PROJECT_DIR}/data"

    local run_args=(
        run -d
        --name "${DOCKER_CONTAINER}"
        --restart unless-stopped
        -p "${PORT}:8080"
        -e TZ=Asia/Shanghai
        -e JAVA_OPTS="${JAVA_OPTS:--Xms256m -Xmx512m}"
        -v "${PROJECT_DIR}/logs:/app/logs"
        -v "${PROJECT_DIR}/data:/app/data"
        --add-host=host.docker.internal:host-gateway
    )

    if [ -d "${PROJECT_DIR}/config" ]; then
        print_info "挂载外部配置目录: ${PROJECT_DIR}/config"
        print_info "Docker 激活环境: ${SPRING_PROFILES_ACTIVE:-默认(application.yml)}"
        run_args+=(
            -v "${PROJECT_DIR}/config:/app/config:ro"
        )
        if [ -n "${SPRING_PROFILES_ACTIVE:-}" ]; then
            run_args+=(-e "SPRING_PROFILES_ACTIVE=${SPRING_PROFILES_ACTIVE}")
        fi
    else
        print_warning "外部配置目录不存在: ${PROJECT_DIR}/config，将使用镜像内默认配置"
        print_info "建议准备 config/application.yml 与 application-<env>.yml"
    fi

    if [ -n "${DOCKER_NETWORK}" ]; then
        run_args+=(--network "${DOCKER_NETWORK}")
    fi

    run_args+=("${DOCKER_IMAGE}")

    if docker "${run_args[@]}"; then
        print_success "容器已启动: ${DOCKER_CONTAINER}"
        return 0
    fi
    print_error "容器启动失败"
    return 1
}

wait_for_port() {
    local max_wait="${1:-40}"
    local waited=0
    while [ "${waited}" -lt "${max_wait}" ]; do
        if port_in_use; then
            return 0
        fi
        sleep 2
        waited=$((waited + 2))
    done
    return 1
}

print_docker_tips() {
    echo "应用信息："
    echo "  - 模式: Docker"
    echo "  - 镜像: ${DOCKER_IMAGE}"
    echo "  - 容器: ${DOCKER_CONTAINER}"
    echo "  - 访问: http://localhost:${PORT}"
    echo "  - 日志: docker logs -f ${DOCKER_CONTAINER}"
    echo "  - 或宿主机: tail -f ${LOG_FILE}"
    echo ""
    echo "注意：容器内访问宿主机 MySQL 请在非 local profile 中将地址改为 host.docker.internal 或实际内网地址"
}
