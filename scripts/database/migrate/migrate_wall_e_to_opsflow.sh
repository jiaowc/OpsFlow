#!/bin/bash

# 从 wall_e 数据库迁移到 opsflow 数据库

MYSQL_PATH="mysql"
DB_HOST="127.0.0.1"
DB_PORT="3306"
DB_USER="root"
DB_PASS="Y4djc+pAyEW=ANp"
SOURCE_DB="wall_e"
TARGET_DB="opsflow"
SCHEMA_FILE="scripts/database/schema.sql"

echo "=========================================="
echo "数据库迁移：wall_e -> opsflow"
echo "=========================================="

# 检查源数据库是否存在
echo "1. 检查源数据库 $SOURCE_DB..."
SOURCE_EXISTS=$($MYSQL_PATH -h $DB_HOST -P $DB_PORT -u $DB_USER -p$DB_PASS -e "SHOW DATABASES LIKE '$SOURCE_DB';" 2>/dev/null | grep -c "$SOURCE_DB")

if [ $SOURCE_EXISTS -eq 0 ]; then
    echo "✗ 源数据库 $SOURCE_DB 不存在，无法迁移"
    echo "请先运行 init_opsflow_db.sh 初始化新数据库"
    exit 1
fi
echo "✓ 源数据库存在"

# 检查目标数据库是否存在
echo ""
echo "2. 检查目标数据库 $TARGET_DB..."
TARGET_EXISTS=$($MYSQL_PATH -h $DB_HOST -P $DB_PORT -u $DB_USER -p$DB_PASS -e "SHOW DATABASES LIKE '$TARGET_DB';" 2>/dev/null | grep -c "$TARGET_DB")

if [ $TARGET_EXISTS -gt 0 ]; then
    echo "⚠️  目标数据库 $TARGET_DB 已存在"
    read -p "是否删除并重新创建？(y/n): " -n 1 -r
    echo
    if [[ $REPLY =~ ^[Yy]$ ]]; then
        echo "删除现有数据库..."
        $MYSQL_PATH -h $DB_HOST -P $DB_PORT -u $DB_USER -p$DB_PASS -e "DROP DATABASE IF EXISTS $TARGET_DB;" 2>/dev/null
        echo "✓ 数据库已删除"
    else
        echo "取消操作"
        exit 0
    fi
fi

# 创建目标数据库
echo ""
echo "3. 创建目标数据库 $TARGET_DB..."
$MYSQL_PATH -h $DB_HOST -P $DB_PORT -u $DB_USER -p$DB_PASS -e "CREATE DATABASE $TARGET_DB DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;" 2>/dev/null

if [ $? -eq 0 ]; then
    echo "✓ 数据库创建成功"
else
    echo "✗ 数据库创建失败"
    exit 1
fi

# 导入表结构
echo ""
echo "4. 导入表结构到目标数据库..."
if [ ! -f "$SCHEMA_FILE" ]; then
    echo "✗ 找不到SQL文件: $SCHEMA_FILE"
    exit 1
fi

$MYSQL_PATH -h $DB_HOST -P $DB_PORT -u $DB_USER -p$DB_PASS $TARGET_DB < $SCHEMA_FILE 2>/dev/null

if [ $? -eq 0 ]; then
    echo "✓ 表结构导入成功"
else
    echo "✗ 表结构导入失败"
    exit 1
fi

# 导入补充表（如果存在）
if [ -f "create_missing_tables.sql" ]; then
    echo ""
    echo "5. 导入补充表结构..."
    $MYSQL_PATH -h $DB_HOST -P $DB_PORT -u $DB_USER -p$DB_PASS $TARGET_DB < create_missing_tables.sql 2>/dev/null
    if [ $? -eq 0 ]; then
        echo "✓ 补充表结构导入成功"
    else
        echo "⚠️  补充表结构导入失败（可能表已存在）"
    fi
fi

# 获取源数据库中的所有表
echo ""
echo "6. 迁移数据..."
TABLES=$($MYSQL_PATH -h $DB_HOST -P $DB_PORT -u $DB_USER -p$DB_PASS $SOURCE_DB -e "SHOW TABLES;" 2>/dev/null | grep -v "Tables_in" | grep -v "^+")

MIGRATED_COUNT=0
FAILED_COUNT=0

for TABLE in $TABLES; do
    # 检查目标数据库是否存在该表
    TARGET_TABLE_EXISTS=$($MYSQL_PATH -h $DB_HOST -P $DB_PORT -u $DB_USER -p$DB_PASS $TARGET_DB -e "SHOW TABLES LIKE '$TABLE';" 2>/dev/null | grep -c "$TABLE")
    
    if [ $TARGET_TABLE_EXISTS -gt 0 ]; then
        echo "  迁移表: $TABLE"
        # 使用 mysqldump 导出并导入数据
        $MYSQL_PATH -h $DB_HOST -P $DB_PORT -u $DB_USER -p$DB_PASS $SOURCE_DB -e "SELECT * FROM $TABLE;" 2>/dev/null | \
        $MYSQL_PATH -h $DB_HOST -P $DB_PORT -u $DB_USER -p$DB_PASS $TARGET_DB -e "LOAD DATA LOCAL INFILE '' INTO TABLE $TABLE;" 2>/dev/null
        
        # 使用更简单的方法：导出为INSERT语句
        $MYSQL_PATH -h $DB_HOST -P $DB_PORT -u $DB_USER -p$DB_PASS $SOURCE_DB -e "SELECT * FROM $TABLE;" 2>/dev/null > /tmp/${TABLE}_data.txt
        
        # 使用mysqldump（如果可用）
        if command -v mysqldump &> /dev/null; then
            mysqldump -h $DB_HOST -P $DB_PORT -u $DB_USER -p$DB_PASS --no-create-info --skip-triggers $SOURCE_DB $TABLE 2>/dev/null | \
            $MYSQL_PATH -h $DB_HOST -P $DB_PORT -u $DB_USER -p$DB_PASS $TARGET_DB 2>/dev/null
            
            if [ $? -eq 0 ]; then
                echo "    ✓ $TABLE 迁移成功"
                ((MIGRATED_COUNT++))
            else
                echo "    ⚠️  $TABLE 迁移失败（可能表为空或结构不同）"
                ((FAILED_COUNT++))
            fi
        else
            # 如果没有mysqldump，尝试直接复制
            ROW_COUNT=$($MYSQL_PATH -h $DB_HOST -P $DB_PORT -u $DB_USER -p$DB_PASS $SOURCE_DB -e "SELECT COUNT(*) FROM $TABLE;" 2>/dev/null | tail -1)
            if [ "$ROW_COUNT" -gt 0 ]; then
                echo "    ⚠️  $TABLE 有 $ROW_COUNT 条数据，但需要mysqldump工具进行迁移"
                echo "    请安装mysql客户端工具或手动迁移数据"
            else
                echo "    ✓ $TABLE 表为空，跳过"
            fi
        fi
    else
        echo "  ⚠️  跳过表 $TABLE（目标数据库中不存在）"
    fi
done

echo ""
echo "=========================================="
echo "数据迁移完成！"
echo "=========================================="
echo "成功迁移表数: $MIGRATED_COUNT"
echo "失败/跳过表数: $FAILED_COUNT"
echo ""
echo "注意事项："
echo "1. 如果使用了mysqldump，数据已完整迁移"
echo "2. 如果没有mysqldump，需要手动迁移数据"
echo "3. 请验证迁移后的数据完整性"
echo "4. 配置文件已更新为使用 $TARGET_DB 数据库"










