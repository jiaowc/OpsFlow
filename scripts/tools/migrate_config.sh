#!/bin/bash

# ==========================================
# 配置文件迁移脚本
# 将项目内配置文件迁移到外部配置目录
# ==========================================

set -e

# 颜色定义
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
BLUE='\033[0;34m'
NC='\033[0m'

# 配置变量
PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
INTERNAL_CONFIG="${PROJECT_DIR}/admin/src/main/resources/application.yml"
EXTERNAL_CONFIG="${PROJECT_DIR}/config/application.yml"
EXTERNAL_CONFIG_EXAMPLE="${PROJECT_DIR}/config/application.yml.example"

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

# 主函数
main() {
    echo ""
    echo "=========================================="
    echo "  配置文件迁移脚本"
    echo "=========================================="
    echo ""
    
    # 检查项目内配置文件是否存在
    if [ ! -f "$INTERNAL_CONFIG" ]; then
        print_error "项目内配置文件不存在: $INTERNAL_CONFIG"
        exit 1
    fi
    
    # 检查外部配置目录是否存在
    if [ ! -d "$(dirname "$EXTERNAL_CONFIG")" ]; then
        print_info "创建外部配置目录..."
        mkdir -p "$(dirname "$EXTERNAL_CONFIG")"
    fi
    
    # 如果外部配置文件已存在
    if [ -f "$EXTERNAL_CONFIG" ]; then
        print_warning "外部配置文件已存在: $EXTERNAL_CONFIG"
        echo ""
        echo "请选择操作："
        echo "  1) 备份现有配置并覆盖"
        echo "  2) 对比差异（不覆盖）"
        echo "  3) 取消"
        echo ""
        read -p "请输入选项 (1/2/3): " choice
        
        case $choice in
            1)
                BACKUP_FILE="${EXTERNAL_CONFIG}.backup.$(date +%Y%m%d_%H%M%S)"
                print_info "备份现有配置到: $BACKUP_FILE"
                cp "$EXTERNAL_CONFIG" "$BACKUP_FILE"
                print_success "备份完成"
                ;;
            2)
                print_info "对比配置文件差异..."
                echo ""
                echo "=== 项目内配置 ==="
                cat "$INTERNAL_CONFIG"
                echo ""
                echo "=== 外部配置 ==="
                cat "$EXTERNAL_CONFIG"
                echo ""
                print_info "请手动对比并决定是否需要更新"
                exit 0
                ;;
            3)
                print_info "操作已取消"
                exit 0
                ;;
            *)
                print_error "无效选项"
                exit 1
                ;;
        esac
    fi
    
    # 复制配置文件
    print_info "正在复制配置文件..."
    print_info "  源文件: $INTERNAL_CONFIG"
    print_info "  目标文件: $EXTERNAL_CONFIG"
    
    cp "$INTERNAL_CONFIG" "$EXTERNAL_CONFIG"
    
    if [ $? -eq 0 ]; then
        print_success "配置文件已迁移到: $EXTERNAL_CONFIG"
        echo ""
        print_warning "⚠️  重要提示："
        echo "  1. 请检查并修改外部配置文件中的敏感信息（如数据库密码）"
        echo "  2. 外部配置文件不会被提交到 Git（已在 .gitignore 中）"
        echo "  3. 项目内配置文件将保留作为开发环境默认配置"
        echo ""
        print_info "编辑配置文件: vim $EXTERNAL_CONFIG"
    else
        print_error "配置文件迁移失败"
        exit 1
    fi
}

# 执行主函数
main "$@"
