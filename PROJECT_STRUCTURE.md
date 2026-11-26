# OpsFlow 项目目录结构

## 目录结构说明

```
OpsFlow/
├── pom.xml                           # 父 POM（模块列表、JDK 版本约束）
├── opsflow-core/                     # 普通 jar：全部业务代码、资源和测试
│   ├── pom.xml                       # 全部依赖
│   └── src/
│       ├── main/
│       │   ├── java/com/opsflow/
│       │   │   ├── api/              # DTO
│       │   │   ├── common/           # 工具、常量、异常
│       │   │   ├── dao/              # Model、Mapper
│       │   │   ├── integration/      # K8s、Harbor、SSH、Pipeline
│       │   │   ├── service/          # 业务逻辑
│       │   │   ├── spi/              # 扩展点（企业版通过这里接入，社区版里是默认实现）
│       │   │   └── web/              # Controller、拦截器、配置
│       │   └── resources/
│       │       ├── mapper/           # MyBatis XML
│       │       ├── db/migration/     # Flyway 版本文件（表结构 + 默认数据）
│       │       └── static/           # 前端静态资源
│       └── test/                     # 单元测试
├── opsflow-app/                      # Spring Boot 可执行包
│   ├── pom.xml                       # spring-boot-maven-plugin，产物 opsflow.jar
│   └── src/main/java/com/opsflow/
│       └── Application.java          # 启动类
├── config/                           # 全部应用配置（只放这里）
│   ├── application.yml               # 公共配置
│   ├── application-<env>.yml         # dev/test/prod 差异配置
│   └── application-local.yml         # 本机私有配置（不提交 Git）
├── Dockerfile                        # 社区版镜像
├── docker-compose.yml                # MySQL + 应用；docker-compose.app.yml 为外部库
├── .github/workflows/ci.yml          # CI：lint、测试、镜像（tag 时发布）
├── LICENSE                           # MIT
├── scripts/
│   ├── deploy/                       # start / stop / restart
│   ├── dev/                          # 前端检查（只读）：check-i18n / check-html-escape / check-routes / check-required-mark（必填星号与 required 一致）
│   └── database/
│       ├── migrate.sh                # 手动执行/查看数据库迁移
│       ├── export_sample_data.sh     # 导出本地演示数据
│       ├── sample/                   # 可选的演示数据
│       └── legacy/                   # 引入 Flyway 之前的迁移脚本（仅旧库追平用）
├── docs/
├── logs/
├── data/
└── README.md
```

## 打包产物

```bash
mvn clean package -DskipTests
# 可部署 JAR：
#   opsflow-app/target/opsflow.jar
```

## 启动方式

```bash
# 开发
java -jar opsflow-app/target/opsflow.jar

# 指定环境（配置说明见 config/README.md）
SPRING_PROFILES_ACTIVE=dev java -jar opsflow-app/target/opsflow.jar

# 或使用脚本
./scripts/deploy/restart.sh
```

## Git 管理

**提交到 Git**：源码、`config/` 下的 `application*.yml` 与 `*.example`、`scripts/`、`docs/`

**不提交到 Git**：`target/`、`logs/`、`data/`、`config/application-local.yml`

## 说明

- 采用 **Maven 多模块**：`opsflow-core`（业务代码、资源、测试）+ `opsflow-app`（只有启动类和打包配置）；在根目录执行 `mvn clean package` 一次构建全部模块。应用配置不放 resources，统一在 `config/`，启动时工作目录仍是项目根目录（或部署目录）
- 包内仍按职责分层：`api` / `dao` / `service` / `web` / `integration`
- 数据库表结构与默认数据由 Flyway 管理（`opsflow-core/src/main/resources/db/migration`，V1 结构、V2 默认数据，之后每个变更一个新版本），应用启动时自动执行
- Docker Compose：`docker-compose.yml`（MySQL 提供空库 + 应用启动时自动建表）
