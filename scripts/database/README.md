# 数据库脚本

表结构和产品默认数据由 **Flyway** 管理，版本文件在 `src/main/resources/db/migration/`，随 jar 发布，应用启动时自动执行。这个目录只放辅助脚本。

```
src/main/resources/db/migration/
├── V1__baseline_schema.sql   # 基线：全部表结构
├── V2__default_data.sql      # 基线：角色权限、初始管理员、步骤定义、默认模版、通用流水线
├── V3__unify_table_collation.sql  # 统一 4 张表的排序规则
└── V4__xxx.sql ...           # 之后的每个变更一个文件

scripts/database/
├── migrate.sh                # 手动执行 / 查看迁移（不启动应用）
├── export_sample_data.sh     # 从当前库导出本地演示数据
├── sample/sample-data.sql    # 可选的演示数据，默认不导入
├── db_env.sh                 # 共用：按与应用一致的规则解析数据库连接
└── legacy/                   # 引入 Flyway 之前的迁移脚本，仅用于把很早的旧库追平
```

## 新环境

建一个空库即可，启动应用时会自动建表并写入默认数据：

```bash
mysql -u root -p -e "CREATE DATABASE IF NOT EXISTS opsflow DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"
```

初始账号 `admin` / `admin`，首次登录会被要求修改密码。

## 升级

不需要手动操作。新版本带的迁移在启动时自动执行，每个版本只执行一次，记录在 `flyway_schema_history` 表里。

### 不希望启动时改库（如生产）

```bash
export OPSFLOW_DB_MIGRATE=false            # 启动时只检查；有未执行的迁移就拒绝启动
bash scripts/database/migrate.sh --info    # 列出已记录和待执行的版本，不改库
bash scripts/database/migrate.sh           # 执行待执行的版本
bash scripts/database/migrate.sh --profile=prod
```

`migrate.sh` 用的是 jar 里的同一批版本文件，需要先有 `target/opsflow.jar`（或用 `OPSFLOW_JAR` 指定）。

### 三种库的处理

| 库的状态 | 启动 / 迁移时发生什么 |
| --- | --- |
| 空库 | 依次执行 V1、V2 及之后的版本 |
| 已有业务表、没有迁移历史 | 打基线：记为「已到版本 2」，不执行 V1、V2，只新建 `flyway_schema_history`；然后执行 V3 及之后的版本 |
| 很早的旧库（缺少近期的表或列） | 拒绝启动并提示。先执行一次 `bash scripts/database/legacy/run_migrations.sh` 追平，再启动 |

### 某个版本执行失败了

MySQL 的表结构变更不能回滚，所以一个版本执行到一半失败时，Flyway 会在历史表里留下一条失败记录，并拒绝继续，直到它被清掉：

```bash
bash scripts/database/migrate.sh --info      # 看是哪个版本失败
# 处理导致失败的原因（报错信息 + 该版本文件开头的说明）；如果它已经做了一部分改动，先手工撤销或补完
bash scripts/database/migrate.sh --repair    # 清除失败记录
bash scripts/database/migrate.sh             # 重新执行，或直接重启应用
```

## 新增一个数据库变更

1. 在 `src/main/resources/db/migration/` 新建 `V<下一个序号>__<英文描述>.sql`，例如 `V4__add_service_owner_index.sql`。
2. 只写这一次变更需要的语句，不需要写成可重复执行的形式（Flyway 保证只执行一次）。
3. **已经发布的版本文件不要再改**：Flyway 会校验内容，改过的文件会让已执行过它的环境启动失败。要修正就再加一个新版本。
4. 本地启动应用验证；生产按上面的方式执行。

注意：版本文件里可以直接出现 `${serviceName}` 这类平台变量，已关闭 Flyway 自己的占位符替换。

## 演示数据（可选）

```bash
mysql -u root -p opsflow < scripts/database/sample/sample-data.sql   # 应用至少启动过一次之后
bash scripts/database/export_sample_data.sh                          # 从当前库重新导出
```

包含项目、集群、环境、节点、组件、钥匙串、服务、流水线视图和本地调试流水线。钥匙串内容、节点密码和 SSH 主机指纹在导出时已置空，导入后需要在界面上重新录入。不含账号、审批流和运行历史。

## 表设计约定

- **启用/禁用开关**用 `tinyint`，`1` 启用、`0` 禁用，列名 `status`（如 `cluster.status`、`user.status`）。
- **状态机**用 `varchar` 存可读的状态码（如 `build_job.status` 的 `PENDING / BUILDING / SUCCESS / FAILED`、`deploy_task.task_status`、`approval_status`）。新表里这类列建议起更具体的名字（`task_status`、`approval_status`），避免和开关型的 `status` 混淆。
- 关联用 `<表>_id` 的 `bigint` 列，不建数据库外键，一致性由应用保证；查询会用到的关联列要建索引。
- 表和字符列统一 `utf8mb4` + `utf8mb4_unicode_ci`，建表时显式写出 `DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci`，不要依赖 MySQL 的默认值（MySQL 8 默认是 `utf8mb4_0900_ai_ci`，混用后字符列关联会报排序规则冲突）。历史上有 4 张表用了默认值，已由 `V3__unify_table_collation.sql` 统一。
