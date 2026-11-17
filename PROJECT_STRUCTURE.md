# OpsFlow 项目目录结构

## 目录结构说明

```
OpsFlow/
├── src/
│   └── main/
│       ├── java/com/opsflow/
│       │   ├── Application.java      # 启动类
│       │   ├── api/                  # DTO
│       │   ├── common/               # 工具、常量、异常
│       │   ├── dao/                  # Model、Mapper
│       │   ├── integration/          # K8s、Harbor、SSH、Pipeline
│       │   ├── service/              # 业务逻辑
│       │   └── web/                  # Controller、拦截器、配置
│       └── resources/
│           ├── mapper/               # MyBatis XML
│           ├── db/migration/         # Flyway 版本文件（表结构 + 默认数据）
│           └── static/               # 前端静态资源
├── config/                           # 全部应用配置（只放这里）
│   ├── application.yml               # 公共配置
│   ├── application-<env>.yml         # dev/test/prod 差异配置
│   └── application-local.yml         # 本机私有配置（不提交 Git）
├── scripts/
│   ├── deploy/                       # start / stop / restart
│   └── database/
│       ├── migrate.sh                # 手动执行/查看数据库迁移
│       ├── export_sample_data.sh     # 导出本地演示数据
│       ├── sample/                   # 可选的演示数据
│       └── legacy/                   # 引入 Flyway 之前的迁移脚本（仅旧库追平用）
├── docs/
├── logs/
├── data/
├── pom.xml
└── README.md
```

## 打包产物

```bash
mvn clean package -DskipTests
# 可部署 JAR：
#   target/opsflow.jar
```

## 启动方式

```bash
# 开发
java -jar target/opsflow.jar

# 指定环境（配置说明见 config/README.md）
SPRING_PROFILES_ACTIVE=dev java -jar target/opsflow.jar

# 或使用脚本
./scripts/deploy/restart.sh
```

## Git 管理

**提交到 Git**：源码、`config/` 下的 `application*.yml` 与 `*.example`、`scripts/`、`docs/`

**不提交到 Git**：`target/`、`logs/`、`data/`、`config/application-local.yml`

## 说明

- 采用 **Spring Boot 单模块标准目录**（`src/main/java` + `src/main/resources`），应用配置不放 resources，统一在 `config/`
- 包内仍按职责分层：`api` / `dao` / `service` / `web` / `integration`
- 数据库表结构与默认数据由 Flyway 管理（`src/main/resources/db/migration`，V1 结构、V2 默认数据，之后每个变更一个新版本），应用启动时自动执行
- Docker Compose：`docker-compose.yml`（MySQL 提供空库 + 应用启动时自动建表）
