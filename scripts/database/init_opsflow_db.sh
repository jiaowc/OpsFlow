#!/bin/bash

# OpsFlow 数据库初始化脚本

MYSQL_PATH="mysql"
DB_HOST="127.0.0.1"
DB_PORT="3306"
DB_USER="root"
DB_PASS="Y4djc+pAyEW=ANp"
DB_NAME="opsflow"
SCHEMA_FILE="scripts/database/schema.sql"

echo "=========================================="
echo "开始初始化 OpsFlow 数据库..."
echo "=========================================="

# 检查数据库是否已存在
echo "1. 检查数据库 $DB_NAME..."
DB_EXISTS=$($MYSQL_PATH -h $DB_HOST -P $DB_PORT -u $DB_USER -p$DB_PASS -e "SHOW DATABASES LIKE '$DB_NAME';" 2>/dev/null | grep -c "$DB_NAME")

if [ $DB_EXISTS -gt 0 ]; then
    echo "⚠️  数据库 $DB_NAME 已存在"
    read -p "是否删除并重新创建？(y/n): " -n 1 -r
    echo
    if [[ $REPLY =~ ^[Yy]$ ]]; then
        echo "删除现有数据库..."
        $MYSQL_PATH -h $DB_HOST -P $DB_PORT -u $DB_USER -p$DB_PASS -e "DROP DATABASE IF EXISTS $DB_NAME;" 2>/dev/null
        echo "✓ 数据库已删除"
    else
        echo "取消操作"
        exit 0
    fi
fi

# 创建数据库
echo ""
echo "2. 创建数据库 $DB_NAME..."
$MYSQL_PATH -h $DB_HOST -P $DB_PORT -u $DB_USER -p$DB_PASS -e "CREATE DATABASE $DB_NAME DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;" 2>/dev/null

if [ $? -eq 0 ]; then
    echo "✓ 数据库创建成功"
else
    echo "✗ 数据库创建失败"
    exit 1
fi

# 导入表结构
echo ""
echo "3. 导入表结构..."
if [ ! -f "$SCHEMA_FILE" ]; then
    echo "✗ 找不到SQL文件: $SCHEMA_FILE"
    exit 1
fi

$MYSQL_PATH -h $DB_HOST -P $DB_PORT -u $DB_USER -p$DB_PASS $DB_NAME < $SCHEMA_FILE 2>/dev/null

if [ $? -eq 0 ]; then
    echo "✓ 表结构导入成功"
else
    echo "✗ 表结构导入失败"
    exit 1
fi

# 导入补充表（如果存在）
if [ -f "create_missing_tables.sql" ]; then
    echo ""
    echo "4. 导入补充表结构..."
    $MYSQL_PATH -h $DB_HOST -P $DB_PORT -u $DB_USER -p$DB_PASS $DB_NAME < create_missing_tables.sql 2>/dev/null
    if [ $? -eq 0 ]; then
        echo "✓ 补充表结构导入成功"
    else
        echo "⚠️  补充表结构导入失败（可能表已存在）"
    fi
fi

# 验证数据
echo ""
echo "5. 验证数据库..."
echo "表列表:"
$MYSQL_PATH -h $DB_HOST -P $DB_PORT -u $DB_USER -p$DB_PASS $DB_NAME -e "SHOW TABLES;" 2>/dev/null | grep -v "Tables_in" | grep -v "^+"

TABLE_COUNT=$($MYSQL_PATH -h $DB_HOST -P $DB_PORT -u $DB_USER -p$DB_PASS $DB_NAME -e "SHOW TABLES;" 2>/dev/null | grep -v "Tables_in" | grep -v "^+" | wc -l | tr -d ' ')
echo ""
echo "共创建 $TABLE_COUNT 个表"

echo ""
echo "=========================================="
echo "数据库初始化完成！"
echo "=========================================="
echo ""
echo "数据库名称: $DB_NAME"
echo "数据库地址: $DB_HOST:$DB_PORT"
echo ""
echo "配置文件位置: src/main/resources/application.yml"
echo "请确认配置文件中的数据库名称已更新为: $DB_NAME"










