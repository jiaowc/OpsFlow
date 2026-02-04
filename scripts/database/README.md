# 数据库脚本目录

## 文件说明

- **init.sql**: 数据库初始化脚本（创建表结构）
- **migrate/**: 数据库迁移脚本目录
  - **migrate_build_job.sql**: 为 build_job 表添加缺失字段

## 使用方法

### 初始化数据库

```bash
mysql -u root -p opsflow < scripts/database/init.sql
```

### 执行迁移脚本

```bash
# 执行特定迁移
mysql -u root -p opsflow < scripts/database/migrate/migrate_build_job.sql
```

## 注意事项

- 执行迁移前请先备份数据库
- 检查迁移脚本是否已执行过，避免重复执行
- 生产环境建议在维护窗口期执行
