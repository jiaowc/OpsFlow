# 数据库脚本目录

## 目录结构

```
scripts/database/
├── schema.sql           # 全量建表（新环境首选）
├── init_db.sh           # 一键初始化（读 config/application.yml）
├── run_migrations.sh    # 增量迁移（可重复执行）
├── migrations/          # migrate_*.sql 增量脚本
├── seeds/               # seed_*.sql 种子数据
└── migrate/             # 历史/特殊迁移工具脚本
```

## 使用方法

### 新环境初始化

```bash
bash scripts/database/init_db.sh
# 或
mysql -u root -p opsflow < scripts/database/schema.sql
```

### 增量迁移（拉代码后有表结构变更）

```bash
bash scripts/database/run_migrations.sh
```

### 种子数据

```bash
mysql -u root -p opsflow < scripts/database/seeds/seed_default_service.sql
```

## 注意事项

- 执行迁移前请先备份数据库
- 迁移脚本设计为可重复执行（已存在的变更会跳过）
- 生产环境建议在维护窗口期执行
