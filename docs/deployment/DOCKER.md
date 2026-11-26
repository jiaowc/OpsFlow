# OpsFlow 一键安装（Docker Compose）

推荐的默认部署方式：一条命令拉起 **MySQL + OpsFlow**。传统 jar 与 Kubernetes 见 [DEPLOYMENT.md](./DEPLOYMENT.md)。

## 环境要求

- Docker Engine 20+ / Docker Desktop（含 Compose V2：`docker compose`）
- 建议可用内存 ≥ 2GB（MySQL + JVM）
- 无需本机安装 JDK / Maven（镜像内多阶段编译）

## 快速开始（内置 MySQL）

```bash
cd OpsFlow
cp .env.example .env          # 可选；生产请修改密码
./scripts/deploy/compose-up.sh
```

等价命令：

```bash
docker compose up -d --build
```

浏览器打开：`http://localhost:8080`  
初始账号：**admin / admin**（首次登录会被要求修改密码）

| 操作 | 命令 |
|------|------|
| 查看状态 | `docker compose ps` |
| 应用日志 | `docker compose logs -f opsflow` |
| 停止（保留数据） | `./scripts/deploy/compose-down.sh` |
| 停止并清空数据库 | `./scripts/deploy/compose-down.sh --volumes` |

## 配置说明（`.env`）

| 变量 | 说明 | 默认 |
|------|------|------|
| `OPSFLOW_PORT` | 控制台端口 | `8080` |
| `MYSQL_PORT` | MySQL 宿主机端口 | `3306` |
| `MYSQL_ROOT_PASSWORD` | MySQL root 密码 | `opsflow` |
| `MYSQL_PASSWORD` | 应用库用户 `opsflow` 密码 | `opsflow` |
| `JAVA_OPTS` | JVM 参数 | `-Xms256m -Xmx512m` |
| `OPSFLOW_WEBHOOK_SECRET` | Git Webhook 密钥，空则关闭 | 空 |
| `OPSFLOW_SECRET_KEY` | 凭据加密主密钥（Base64 的 32 字节，`openssl rand -base64 32` 生成）；空则首次启动自动生成 `./data/secret.key` | 空 |
| `OPSFLOW_DOCKER_IMAGE` | 镜像名/标签 | `opsflow:1.1.0` |

> ⚠️ **凭据加密主密钥**：钥匙串、组件认证信息、节点 SSH 密码/私钥都以 AES-256-GCM 加密存库。
> 主密钥（`OPSFLOW_SECRET_KEY` 或 `./data/secret.key`）丢失或被更换后，已保存的凭据无法解密，只能重新录入。
> 请把它和数据库一起备份；不要提交到 Git。从旧版本升级时，首次启动会自动把历史明文凭据加密回写。

应用数据库连接统一走 **`DB_URL` / `DB_USERNAME` / `DB_PASSWORD`**（与传统部署一致）。全栈 Compose 会自动注入指向容器 `mysql` 的地址。

持久化目录：

- `./data` — 流水线日志、凭据加密主密钥 `secret.key`（未配置 `OPSFLOW_SECRET_KEY` 时）等
- `./logs` — 应用日志
- Docker volume `opsflow-mysql` — 数据库文件

## 使用发布的镜像（不在本机编译）

每个版本的社区版镜像由 GitHub Actions 构建并发布到 `ghcr.io/jiaowc/opsflow:<版本>`（linux/amd64、linux/arm64）。不想在本机编译时，在 `.env` 里设置 `OPSFLOW_DOCKER_IMAGE=ghcr.io/jiaowc/opsflow:1.1.0`，然后：

```bash
docker compose pull opsflow
docker compose up -d --no-build
```

本机自己出多架构镜像：

```bash
docker buildx build --platform linux/amd64,linux/arm64 --build-arg OPSFLOW_VERSION=1.1.0 -t <镜像名>:1.1.0 .
```

## 使用外部 MySQL

1. 自行建一个空库（表结构和默认数据由应用启动时自动建立）：

```bash
# 示例（按你们环境改连接）
mysql -h... -u... -p -e "CREATE DATABASE IF NOT EXISTS opsflow DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"
```

2. 在 `.env` 中设置：

```bash
DB_URL=jdbc:mysql://host.docker.internal:3306/opsflow?useUnicode=true&characterEncoding=utf8&connectionCollation=utf8mb4_unicode_ci&serverTimezone=Asia/Shanghai
DB_USERNAME=opsflow
DB_PASSWORD=你的密码
```

Linux 若无 `host.docker.internal`，可改成宿主机 IP，或在 compose 中加 `extra_hosts`。

3. 启动仅应用：

```bash
./scripts/deploy/compose-up.sh --external-db
# 或：docker compose -f docker-compose.app.yml up -d --build
```

## 体验镜像（all-in-one）

一个镜像、一个容器，内置 MySQL 8.0 和演示数据，`docker run` 之后直接可用。**只用于体验和演示**：数据库和应用在同一个容器里，不能分开升级和扩缩，正式使用请用上面的 Compose 方式或外部 MySQL。

```bash
docker run -d --name opsflow-allinone -p 8080:8080 \
  -v opsflow-allinone-data:/data ghcr.io/jiaowc/opsflow-allinone:1.1.0
```

镜像在打版本 tag 时由 GitHub Actions 自动构建并发布到 GitHub Container Registry，公开可拉取，支持 `linux/amd64` 和 `linux/arm64`。

拉取不了 ghcr.io，或想用当前源码构建时：

```bash
docker build -f Dockerfile.allinone -t ghcr.io/jiaowc/opsflow-allinone:1.1.0 .
```

也可以用 Compose 示例（本地没有镜像时自动拉取；加 `--build` 则从源码构建）：

```bash
docker compose -f docker-compose.allinone.yml up -d
```

首次启动约 1 分钟（初始化数据库、建表、导入演示数据），`docker logs -f opsflow-allinone` 可以看进度。浏览器打开 `http://localhost:8080`，初始账号 **admin / admin**。这是公开的演示口令，**首次登录后请修改**（系统会强制要求改密，新密码至少 8 位）。

### 带了哪些数据

| 来源 | 内容 |
|------|------|
| 默认数据（Flyway，和其他部署方式相同） | 管理员 `admin`、5 个角色及权限、步骤定义、Dockerfile / Deployment / Service 模版、通用流水线 |
| 演示数据（`scripts/docker/allinone/demo-data.sql`） | 项目 `demo-shop`、占位集群 `demo-k8s`、环境 `dev` / `test` / `prod`、组件 `github` 和 `harbor-demo`、3 个示例服务、3 个流水线视图 |

演示数据里**没有任何凭据**，也没有构建 / 部署节点：集群和 Harbor 是 `example.com` 占位地址。可以浏览和配置，但要真正跑通流水线，需要在界面上添加自己的构建节点、集群 kubeconfig 和镜像仓库。

不想要演示数据（只保留默认数据）：首次启动时加 `-e OPSFLOW_DEMO_DATA=false`。这个开关只在首次启动时生效。

### 数据与持久化

所有数据都在容器的 `/data` 下：

| 路径 | 内容 |
|------|------|
| `/data/mysql` | 数据库文件 |
| `/data/app` | 品牌 Logo、流水线日志、凭据加密主密钥 `secret.key` |
| `/data/logs` | 预留的日志目录；容器内应用和 MySQL 的日志都输出到 `docker logs` |
| `/data/.db-password` | 内置数据库的口令：首次启动随机生成，镜像里没有固定口令 |
| `/data/.seed-done` | 初始化标记：存在时不再导入演示数据 |

- 挂了卷（上面的 `-v opsflow-allinone-data:/data`）：删除容器、换新镜像重建，数据都还在，不会重复初始化。
- 没挂卷：`docker restart` 不丢数据；`docker rm` 后数据随匿名卷一起丢弃，下次是全新的演示环境。
- 建议用命名卷，不要把 `/data` 绑定到 macOS / Windows 的宿主机目录（MySQL 数据目录在这类文件系统上容易有权限和性能问题）。
- 清空重来：`docker rm -f opsflow-allinone && docker volume rm opsflow-allinone-data`。

内置 MySQL 只监听容器内的 `127.0.0.1`，不对外暴露。需要进库查看时：

```bash
docker exec -it opsflow-allinone mysql opsflow
```

停止时入口脚本会先停应用、再正常关闭 MySQL；`docker stop` 默认只等 10 秒，建议 `docker stop -t 60 opsflow-allinone`（Compose 示例里已设 `stop_grace_period: 1m`）。即使被强杀，下次启动 InnoDB 也会自动恢复。

内存建议 ≥ 1.5GB；`JAVA_OPTS` 默认 `-Xms256m -Xmx512m`，可用 `-e JAVA_OPTS=...` 调整。

### 发布镜像（维护者）

**打 tag 时由 CI 自动发布，不需要手动推送。** 公开仓库推送 `v<版本>` tag 后，`.github/workflows/ci.yml` 会同时构建社区版镜像 `ghcr.io/jiaowc/opsflow` 和体验镜像 `ghcr.io/jiaowc/opsflow-allinone`。两个镜像都出 amd64 和 arm64 双架构，并推送 `<版本>`（如 `1.1.0`）、`<主版本>.<次版本>`（如 `1.1`）和 `latest` 标签。tag 必须和 `pom.xml` 的 `<version>` 一致，不一致时 CI 会失败。

发布后核对两个架构都在：

```bash
docker buildx imagetools inspect ghcr.io/jiaowc/opsflow-allinone:1.1.0
```

CI 不可用时，可以按下面的步骤手动构建并推送（备用办法）：

```bash
# 1. 登录 ghcr.io：用带 write:packages 权限的 GitHub Token，从标准输入传入，不要写在命令行里
docker login ghcr.io -u jiaowc --password-stdin

# 2. 一次性：建一个支持多架构的构建器（Docker 默认的 docker 驱动不能同时出多个架构）
docker buildx create --name opsflow-multiarch --driver docker-container --use

# 3. 构建 amd64 + arm64 并推送
docker buildx build --builder opsflow-multiarch \
  --platform linux/amd64,linux/arm64 \
  -f Dockerfile.allinone \
  -t ghcr.io/jiaowc/opsflow-allinone:1.1.0 \
  -t ghcr.io/jiaowc/opsflow-allinone:latest \
  --push .

# 4. 核对两个架构都在
docker buildx imagetools inspect ghcr.io/jiaowc/opsflow-allinone:1.1.0
```

首次推送后包默认是私有的：在 GitHub 的 Packages → `opsflow-allinone` → Package settings 里把可见性改为 Public，匿名用户才能拉取。jar 只在构建机本机架构上编译一次（`FROM --platform=$BUILDPLATFORM`），另一个架构只模拟执行运行层的几条命令，不会很慢。

## 备份与恢复

```bash
# 备份数据库（Compose 内置 MySQL）
docker compose exec -T mysql \
  mysqldump -uroot -p"$MYSQL_ROOT_PASSWORD" opsflow > backup_$(date +%Y%m%d).sql

# 恢复（需先有空库或覆盖）
docker compose exec -T mysql \
  mysql -uroot -p"$MYSQL_ROOT_PASSWORD" opsflow < backup_YYYYMMDD.sql

# 备份应用数据目录
tar czf opsflow-data-$(date +%Y%m%d).tar.gz data/
```

## 升级

1. 备份数据库与 `./data`
2. `git pull`（或替换发布包）
3. 数据库迁移不需要手动处理：新版本带的迁移在应用启动时自动执行（关闭了自动迁移的环境先跑 `scripts/database/migrate.sh`）
4. 重新构建并滚动启动：

```bash
./scripts/deploy/compose-up.sh
# 或：docker compose up -d --build
```

各版本的行为和接口变化见 [UPGRADE.md](../UPGRADE.md)。

表结构和默认数据由应用启动时按版本自动建立和升级，与 MySQL 数据卷是否新建无关；升级不要依赖 `down -v`（那会清空全部数据）。

## 升级到企业版

企业版与社区版使用同一套库结构，升级只换镜像，数据库和 `./data` 原样沿用：

1. 备份数据库与 `./data`（见上文）；
2. 把应用镜像换成企业版镜像，数据库连接、`OPSFLOW_SECRET_KEY`（或 `./data/secret.key`）保持不变；
3. 启动后在「系统设置 → License」导入 License，或按企业版文档挂载 License 文件自动导入。

回到社区版只需换回社区版镜像，企业版功能的数据保留在库里，再次升级后仍在。企业版的获取方式见 <https://www.opslink.cn>。

## 常见问题

**8080 被占用** — 改 `.env` 中 `OPSFLOW_PORT=18080` 后重启。

**构建很慢** — 首次多阶段编译会拉 Maven 依赖；后续有 Docker 层缓存会快很多。

**容器内写 `./data` / `./logs` 权限错误** — 入口脚本会尝试修正属主；仍失败时可：`mkdir -p data logs && chmod -R a+rwX data logs`。

**健康检查失败** — `docker compose logs opsflow`，确认 MySQL 已 healthy、`DB_PASSWORD` 与库一致。

## 相关文件

- `Dockerfile` — 社区版镜像，多阶段构建（JDK 17 编译 + JRE 17 运行），产物 `opsflow-app/target/opsflow.jar`
- `docker-compose.yml` — 一键全栈
- `docker-compose.app.yml` — 仅应用 / 外部库
- `Dockerfile.allinone` / `docker-compose.allinone.yml` — 体验镜像（内置 MySQL 与演示数据）
- `scripts/docker/allinone/` — 体验镜像的入口脚本、MySQL 配置、演示数据
- `.env.example` — 环境变量模板
- `scripts/deploy/compose-up.sh` / `compose-down.sh` — 包装脚本
