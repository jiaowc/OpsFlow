# OpsFlow - DevOps 构建发布平台

OpsFlow 是一个开源的 CI/CD 平台：接入 Git 仓库、镜像仓库和 Kubernetes 集群后，通过流水线完成拉代码、编译、构建镜像、推送和部署，并提供阶段日志、回滚、权限管理和 Webhook 自动构建。

本仓库是 **社区版（Community）**，以 GNU AGPLv3 许可证开源（1.1.1 起；1.1.0 及更早的版本是 MIT）。

📖 **使用文档**：[docs/USAGE.md](docs/USAGE.md)（初始化配置顺序、流水线 Job、权限、Webhook、常见问题）

⬆️ **升级说明**：[docs/UPGRADE.md](docs/UPGRADE.md)（版本间的行为和接口变化，升级前必看）

## 功能特性

- **流水线**：原生 Pipeline，内置拉代码、编译、构建镜像、推送镜像、渲染模版、部署、检查部署状态、回滚等步骤；支持流水线模版和自定义步骤
- **构建与部署**：通过 SSH 接入构建节点和部署节点，构建 Docker 镜像推送到 Harbor，部署到 Kubernetes 集群
- **任务管理**：工作视图、作业列表、构建历史、阶段实时日志、重新构建
- **部署与回滚**：生产和非生产环境都可部署，按历史构建的镜像回滚（按权限码控制）
- **资源管理**：项目、服务、环境、集群、节点
- **凭据与集成**：钥匙串统一保存账号密码、Token、Kubeconfig、SSH 密钥（加密存库），供 Git、Harbor、K8s 等组件引用
- **自动构建**：Git Webhook 推送触发
- **账号与权限**：本地账号、LDAP 登录、两步验证（TOTP 动态码 + 恢复码）、角色与权限（RBAC）、项目成员
- **其他**：站内信、工作台统计、中英文界面

## 版本说明

OpsFlow 采用 Open Core 模式：社区版提供完整可用的 CI/CD 核心，企业版在此基础上增加面向企业的管控能力。两个版本库结构相同，可以直接换镜像升级或回退，数据不动。

| 能力 | 社区版 | 企业版 |
| --- | :---: | :---: |
| 项目 / 服务 / 环境 / 集群 / 节点管理 | ✅ | ✅ |
| 凭据与集成：钥匙串、组件接入（Git / Harbor / K8s） | ✅ | ✅ |
| 流水线：构建、部署、日志、模版、自定义步骤 | ✅ | ✅ |
| 生产与非生产环境的部署和回滚（按权限码控制） | ✅ | ✅ |
| Git Webhook 自动构建 | ✅ | ✅ |
| 本地账号、LDAP 登录、角色权限（RBAC） | ✅ | ✅ |
| 两步验证（动态码 + 恢复码，可设为全员强制） | ✅ | ✅ |
| 站内信、工作台统计 | ✅ | ✅ |
| 上线任务与审批流程 | — | ✅ |
| 操作审计查询 | — | ✅ |
| 外部通知（飞书等） | — | ✅ |
| 单点登录（飞书、企业微信、钉钉） | — | ✅ |
| 企业品牌（Logo、名称） | — | ✅ |

说明：

- 社区版永久免费，不含 License 校验代码；企业版按期订阅，到期后回到社区版能力，数据和流水线保留。
- 企业版功能不在本仓库。完整边界见 [docs/licensing/OPEN_CORE_BOUNDARY.md](docs/licensing/OPEN_CORE_BOUNDARY.md)。
- 企业版咨询：<https://www.opslink.cn>

## 技术栈

- **后端**：Spring Boot 2.7.18，JDK 17，MyBatis Plus
- **数据库**：MySQL 8.0，表结构由 Flyway 在启动时自动迁移
- **前端**：原生 HTML / JavaScript，随应用一起打包，无需单独构建
- **执行方式**：原生 Pipeline + SSH 远程节点
- **部署目标**：Docker + Kubernetes
- **镜像仓库**：Harbor

## 项目结构

```
OpsFlow/
├── pom.xml          # 父 POM（多模块）
├── core/            # 全部业务代码（api/dao/service/web/integration）、mapper、静态页面、数据库迁移、测试；对外提供扩展点（com.opsflow.spi）
├── app/             # 启动类 + 打包配置，产物 app/target/opsflow.jar
├── config/          # 全部应用配置（application*.yml）
├── scripts/         # 部署、数据库、本地环境脚本
└── docs/            # 文档
```

模块划分、扩展点、流水线执行模型等见 [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)，本地开发见 [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md)。

## 快速开始

### 体验：单镜像一条命令启动（内置 MySQL 与演示数据）

只想先看看界面和功能时用这个。一个容器里同时跑 MySQL 和 OpsFlow，首次启动自动建表、写入默认数据并导入一份演示数据（示例项目、dev/test/prod 环境、示例服务、流水线视图）：

```bash
docker run -d --name opsflow-allinone -p 8080:8080 -v opsflow-allinone-data:/data ghcr.io/jiaowc/opsflow-allinone:1.1.2
# 首次启动约 1 分钟，打开 http://localhost:8080
```

镜像支持 `linux/amd64` 和 `linux/arm64`。拉取不了 ghcr.io 时可以从源码自己构建：`docker build -f Dockerfile.allinone -t ghcr.io/jiaowc/opsflow-allinone:1.1.2 .`

初始账号 **admin / admin**，这是公开的演示口令，**首次登录后请修改**（系统会强制要求改密）。

仅用于体验 / 演示，正式使用请用下面的方式一。持久化、关闭演示数据等说明见 [部署文档「体验镜像」](docs/deployment/DEPLOYMENT.md#体验镜像all-in-one)。

### 方式一：Docker Compose（推荐）

无需本机安装 JDK、Maven、MySQL：

```bash
cp .env.example .env
./scripts/deploy/compose-up.sh
# 或：docker compose up -d --build
```

浏览器打开 <http://localhost:8080>，初始账号 `admin` / `admin`，首次登录会被要求修改密码。

清空数据库并重新初始化：

```bash
./scripts/deploy/compose-down.sh --volumes
./scripts/deploy/compose-up.sh
```

配置、使用发布的镜像、外部数据库、上线检查清单、备份与升级见 [docs/deployment/DEPLOYMENT.md](docs/deployment/DEPLOYMENT.md)。

### 方式二：源码构建运行

需要 JDK 17、Maven 3.6+ 和 MySQL 8.0。建一个空库，表结构和默认数据由应用启动时自动建立：

```bash
mysql -u root -p -e "CREATE DATABASE IF NOT EXISTS opsflow DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"
cp config/application-local.yml.example config/application-local.yml   # 填写 DB_PASSWORD
mvn clean package -DskipTests
java -jar app/target/opsflow.jar                                # 在项目根目录执行
```

JDK 版本、本地辅助环境（构建节点、Harbor、kind 集群）、测试和前端检查见 [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md)；生产服务器用 jar 部署（systemd、反向代理）见 [部署文档「外部数据库」](docs/deployment/DEPLOYMENT.md#外部数据库)。

要真正跑流水线，还需要通过 SSH 接入的构建节点和作为部署目标的 Kubernetes 集群，配置顺序见 [使用文档](docs/USAGE.md)。

## 配置文件管理

- 一个 jar 适用所有环境，启动时用 `SPRING_PROFILES_ACTIVE=dev|test|prod` 选择
- 数据库连接统一用 `DB_URL` / `DB_USERNAME` / `DB_PASSWORD` 注入（环境变量或 `config/application-local.yml`）
- ⚠️ 不要把真实密码提交到 Git；生产环境用环境变量或 K8s Secret
- 钥匙串等凭据加密存库，主密钥请与数据库一起备份

完整说明见 [config/README.md](config/README.md)。

## API 示例

日常操作都可以在管理后台完成，下面是构建相关的几个接口：

| 接口 | 说明 |
| --- | --- |
| `POST /api/build/start` | 启动构建任务 |
| `GET /api/build/{jobId}` | 查询构建状态 |
| `GET /api/build/jobs?envId=3` | 构建任务列表 |
| `GET /api/build/service/{serviceId}/branches` | 服务的分支列表 |
| `GET /api/build/envs` | 环境列表 |
| `GET /api/build/nodes?random=true` | 构建节点列表 |
| `GET /api/component/list` | 组件列表 |
| `POST /api/component/{id}/test` | 测试组件连接 |

启动构建的请求体：

```json
{
  "serviceId": 1,
  "envId": 1,
  "pipelineTemplateId": 3,
  "branch": "develop",
  "gitType": "branch"
}
```

## 文档

- [使用文档](docs/USAGE.md)
- [升级说明](docs/UPGRADE.md)
- [社区版与企业版的边界](docs/licensing/OPEN_CORE_BOUNDARY.md)
- [部署文档](docs/deployment/DEPLOYMENT.md)（体验镜像、Docker Compose、外部数据库、上线检查清单）
- [技术架构](docs/ARCHITECTURE.md)
- [开发指南](docs/DEVELOPMENT.md)
- [配置说明](config/README.md)
- [数据库脚本说明](scripts/database/README.md)

## 许可证

Copyright (C) 2026 jiaowc

社区版自 1.1.1 起以 [GNU Affero General Public License v3.0](LICENSE)（AGPL-3.0-only）开源；1.1.0 及更早的版本按当时的 MIT 许可发布，不受影响。

- 自己或公司内部部署使用，不修改源码：不需要做任何额外的事。
- 修改了源码并分发，或者把修改后的版本通过网络提供给别人使用（例如托管服务）：必须按 AGPLv3 向这些用户提供修改后的完整源码。
- 不想受 AGPLv3 约束（例如做成闭源产品或闭源托管服务）：可以联系获取商业授权。

企业版功能不在本仓库，需单独订阅，见 <https://www.opslink.cn>。
