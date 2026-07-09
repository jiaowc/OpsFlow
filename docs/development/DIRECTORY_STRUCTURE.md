# 代码目录结构

## Maven 多模块（平级结构）

```
OpsFlow/
├── api/              # DTO
├── common/           # 工具、常量
├── dao/              # Model、Mapper
├── integration/      # K8s、Harbor、SSH、Pipeline
├── service/          # 业务逻辑
├── web/              # Controller + 静态前端 + 默认配置
├── app/              # 启动入口（打出 opsflow.jar）
├── config/           # 外部配置
├── scripts/          # 部署、数据库脚本
└── docs/             # 文档
```

### 模块职责

| 模块 | 说明 |
|------|------|
| `web` | REST API、管理后台静态资源、`application.yml` |
| `app` | `Application.java`，Spring Boot 打包模块 |
| 其余 | 标准分层，见 PROJECT_STRUCTURE.md |

### 依赖链

```
app → web → service → (api, dao, integration)
dao → common
```

## 非代码目录

- **config/**：生产环境外置配置
- **scripts/deploy/**：启停脚本
- **scripts/database/**：`schema.sql`、`migrations/`、`seeds/`
- **logs/**、**data/**：运行时数据（不入库）

## 结论

保持 Maven 平级多模块，**不要将模块套入 `modules/` 子目录**。`admin` 已重命名为 `app`，静态资源归 `web` 管理。
