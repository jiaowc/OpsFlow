# 代码目录结构

## Spring Boot 单模块标准结构

```
OpsFlow/
├── src/main/java/com/opsflow/
│   ├── Application.java   # 启动类
│   ├── api/               # DTO
│   ├── common/            # 工具、常量、异常
│   ├── dao/               # Model、Mapper
│   ├── integration/       # K8s、Harbor、SSH、Pipeline
│   ├── service/           # 业务逻辑
│   └── web/               # Controller、拦截器、配置
├── src/main/resources/
│   ├── application.yml
│   ├── mapper/
│   └── static/
├── config/                # 外部配置
├── scripts/               # 部署、数据库脚本
└── docs/                  # 文档
```

包内按职责分层，Maven 仅一个模块，产物为 `target/opsflow.jar`。

## 非代码目录

- **config/**：生产环境外置配置
- **scripts/deploy/**：启停脚本
- **scripts/database/**：`schema.sql`、`migrations/`、`seeds/`
- **logs/**、**data/**：运行时数据（不入库）
