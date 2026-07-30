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
│           ├── application.yml       # 开发默认配置
│           ├── mapper/               # MyBatis XML
│           └── static/               # 前端静态资源
├── config/                           # 外部配置（生产/本地，不提交 Git）
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

# 生产（外置配置）
java -jar target/opsflow.jar --spring.config.location=file:./config/application.yml

# 或使用脚本
./scripts/deploy/restart.sh
```

## Git 管理

**提交到 Git**：源码、`config/*.example`、`scripts/`、`docs/`

**不提交到 Git**：`config/application.yml`、`target/`、`logs/`、`data/`

## 说明

- 采用 **Spring Boot 单模块标准目录**（`src/main/java` + `src/main/resources`）
- 包内仍按职责分层：`api` / `dao` / `service` / `web` / `integration`
- 数据库脚本按 `schema` / `migrations` / `seeds` 分类存放
