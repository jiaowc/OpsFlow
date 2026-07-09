# OpsFlow 项目目录结构

## 目录结构说明

```
OpsFlow/
├── api/                          # API 模块（DTO）
├── common/                       # 公共模块（工具类、常量）
├── dao/                          # 数据访问层（Model、Mapper）
├── integration/                  # 集成模块（K8s、Harbor、SSH、Pipeline 步骤）
├── service/                      # 业务逻辑层
├── web/                          # Web 层（Controller + 静态前端 + 默认配置）
│   └── src/main/
│       ├── java/com/opsflow/web/
│       └── resources/
│           ├── application.yml     # 开发环境默认配置
│           └── static/           # HTML、CSS、JS
├── app/                          # 启动模块（仅 Application + 打包）
│   └── src/main/java/com/opsflow/Application.java
├── config/                       # 外部配置（生产/本地，不提交 Git）
├── scripts/
│   ├── deploy/                   # start / stop / restart
│   └── database/
│       ├── schema.sql            # 全量建表
│       ├── migrations/           # 增量迁移 migrate_*.sql
│       ├── seeds/                # 种子数据 seed_*.sql
│       └── run_migrations.sh
├── docs/
├── logs/
├── data/
├── pom.xml
└── README.md
```

## 模块依赖关系

```
app → web → service → api
                    ├── dao → common
                    └── integration → api, dao
```

| 模块 | 职责 |
|------|------|
| **api** | 请求/响应 DTO |
| **common** | 常量、异常、通用工具 |
| **dao** | MyBatis 实体与 Mapper |
| **integration** | 第三方集成与 Pipeline 步骤执行 |
| **service** | 业务逻辑 |
| **web** | REST API、静态管理页面、默认 `application.yml` |
| **app** | Spring Boot 启动入口，打出可执行 `opsflow.jar` |

## 打包产物

```bash
mvn clean package -DskipTests
# 可部署 JAR（统一输出）：
#   target/opsflow.jar
```

各子模块编译时仍会在 `模块名/target/` 生成中间产物（Maven 惯例），**部署只需关注根目录这一个 jar**。

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

**提交到 Git**：各模块源码、`config/*.example`、`scripts/`、`docs/`

**不提交到 Git**：`config/application.yml`、`target/`、`logs/`、`data/`

## 说明

- 采用 **Maven 多模块平级结构**，符合 Java 社区惯例
- `web` 负责对外 HTTP（API + 页面），`app` 仅负责启动与打包，职责清晰
- 数据库脚本按 `schema` / `migrations` / `seeds` 分类存放
