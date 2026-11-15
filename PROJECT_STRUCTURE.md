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
│           └── static/               # 前端静态资源
├── config/                           # 全部应用配置（只放这里）
│   ├── application.yml               # 公共配置
│   ├── application-<env>.yml         # dev/test/prod 差异配置
│   └── application-local.yml         # 本机私有配置（不提交 Git）
├── scripts/
│   ├── deploy/                       # start / stop / restart
│   └── database/
│       ├── schema.sql
│       ├── migrations/
│       ├── seeds/
│       └── run_migrations.sh
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
- 数据库脚本按 `schema` / `migrations` / `seeds` 分类存放
- 新环境一键初始化：`scripts/database/init.sql`（由 `export_init.sh` 从当前库导出）
- Docker Compose：`docker-compose.yml`（MySQL 自动导入 init.sql + 应用）
