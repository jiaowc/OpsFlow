# OpsFlow - DevOps构建发布平台

## 项目简介

OpsFlow 是一个基于 Spring Boot 的 DevOps 构建发布平台，支持非生产环境的一键构建发布功能。

📖 **使用文档**：[docs/USAGE.md](docs/USAGE.md)（初始化配置顺序、流水线 Job、上线审批、权限、Webhook、常见问题）

## 功能特性

- ✅ 非生产环境一键构建发布（dev/test/demo）
- ✅ 支持手动选择分支和环境
- ✅ 支持随机选择或手动选择构建节点
- ✅ 原生 Pipeline 流水线（拉代码、构建、部署）
- ✅ 自动构建 Docker 镜像并推送到 Harbor
- ✅ 自动部署到 K8s 集群
- ✅ Pipeline 任务管理（作业列表、构建历史、阶段日志）
- ✅ 组件管理（GitLab、K8s、Harbor 配置）
- 🔄 生产环境上线任务和审批流程（待实现）

## 技术栈

- **后端框架**: Spring Boot 2.7.14
- **数据库**: MySQL + MyBatis Plus
- **构建工具**: Maven
- **CI/CD**: 原生 Pipeline + SSH 远程节点
- **容器化**: Docker + Kubernetes
- **镜像仓库**: Harbor

## 项目结构

```
OpsFlow/
├── src/main/java/com/opsflow/   # 启动类 + 按包分层（api/dao/service/web/integration）
├── src/main/resources/          # mapper、static
├── config/                      # 全部应用配置（application*.yml）
├── scripts/                     # 部署、数据库、工具脚本
├── docs/                        # 文档
├── logs/                        # 日志
└── data/                        # 数据
```

详细结构说明请查看 [PROJECT_STRUCTURE.md](PROJECT_STRUCTURE.md)

## 快速开始

### 推荐：Docker Compose 一键安装

无需本机 JDK / Maven / MySQL：

```bash
cp .env.example .env
./scripts/deploy/compose-up.sh
# 打开 http://localhost:8080 ，默认账号 admin / 123456
```

说明见 [docs/deployment/DOCKER.md](docs/deployment/DOCKER.md)。

### 传统方式：环境要求

- JDK 1.8+
- Maven 3.6+
- MySQL 5.7+
- 构建节点（SSH，用于 Pipeline 执行）
- Kubernetes集群（已配置）

### 2. 数据库初始化

新环境推荐直接导入「结构 + 当前配置数据」（不含构建/上线历史）：

```bash
mysql -u root -p -e "CREATE DATABASE IF NOT EXISTS opsflow DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"
mysql -u root -p opsflow < scripts/database/init.sql
bash scripts/database/run_migrations.sh   # init.sql 是导出快照，需补跑之后新增的迁移
```

从本机库重新导出初始化脚本：

```bash
bash scripts/database/export_init.sh
```

只要空库（表结构 + 角色权限 + 内置 admin 账号，不含业务配置）时：

```bash
bash scripts/database/init_db.sh   # 建库、导入 schema.sql 与种子数据、执行全部迁移
```

### 3. 配置文件

```bash
cp config/application-local.yml.example config/application-local.yml
vim config/application-local.yml   # 填写 DB_PASSWORD（本机私有，不入库）
```

所有配置都在 `config/`（需在项目根目录启动），各环境的 `application-<env>.yml` 只写与公共配置的差异。详见 [config/README.md](config/README.md)。

### 4. 编译打包

```bash
mvn clean package -DskipTests   # 所有环境共用同一个 jar
```

### 5. 运行

#### 使用脚本（推荐）
```bash
# 启动应用
./scripts/deploy/start.sh

# 停止应用
./scripts/deploy/stop.sh

# 重启应用（会重新编译）
./scripts/deploy/restart.sh

# 指定环境（示例：dev）
SPRING_PROFILES_ACTIVE=dev ./scripts/deploy/restart.sh
```

#### 手动启动（在项目根目录执行）
```bash
java -jar target/opsflow.jar
SPRING_PROFILES_ACTIVE=dev java -jar target/opsflow.jar
```

#### Docker Compose（应用 + MySQL，推荐）
```bash
cp .env.example .env
./scripts/deploy/compose-up.sh
# 或：docker compose up -d --build
# 浏览器打开 http://localhost:8080 ，账号 admin / 123456
```

仅清空数据库并重新初始化：
```bash
./scripts/deploy/compose-down.sh --volumes
./scripts/deploy/compose-up.sh
```

详见 [docs/deployment/DOCKER.md](docs/deployment/DOCKER.md)。

## 配置文件管理

- 一个 jar 适用所有环境，启动时用 `SPRING_PROFILES_ACTIVE=dev|test|prod` 选择
- 数据库连接统一用 `DB_URL` / `DB_USERNAME` / `DB_PASSWORD` 注入（环境变量或 `config/application-local.yml`）
- ⚠️ 不要把真实密码提交到 Git；生产环境用环境变量或 K8s Secret

完整说明见 [config/README.md](config/README.md)。

## API接口

### 非生产环境构建发布

#### 1. 启动构建任务

```http
POST /api/build/start
Content-Type: application/json

{
  "serviceId": 1,
  "envId": 1,
  "pipelineTemplateId": 3,
  "branch": "develop",
  "gitType": "branch",
  "autoDeploy": true
}
```

#### 2. 查询构建状态

```http
GET /api/build/{jobId}
```

#### 3. 获取构建任务列表

```http
GET /api/build/jobs?envId=3
```

#### 4. 获取服务的分支列表

```http
GET /api/build/service/{serviceId}/branches
```

#### 5. 获取环境列表

```http
GET /api/build/envs
```

#### 6. 获取构建节点列表

```http
GET /api/build/nodes?random=true
```

### Pipeline 任务

通过管理后台「Pipeline」页面管理任务、触发构建、查看阶段日志。

### 组件管理

#### 1. 获取组件列表

```http
GET /api/component/list
```

#### 2. 测试组件连接

```http
POST /api/component/{id}/test
```

## 开发计划

- [ ] 生产环境上线任务功能
- [ ] 审批流程功能
- [ ] LDAP/SSO统一登录
- [ ] 构建任务历史查询
- [ ] 发布回滚功能

## 许可证

MIT License
