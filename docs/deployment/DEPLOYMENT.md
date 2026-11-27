# OpsFlow 部署文档

本文介绍社区版的三种部署方式，以及上线前检查、备份与恢复、常见问题。版本间的行为和接口变化见 [UPGRADE.md](../UPGRADE.md)。

## 怎么选

| 方式 | 适合 | 数据库 | 是否适合生产 |
|---|---|---|---|
| [① 体验镜像（all-in-one）](#体验镜像all-in-one) | 先看看界面和功能、演示 | 容器内置 MySQL，带演示数据 | **不适合**：库和应用在同一个容器里，不能分开升级、备份和扩容 |
| [② Docker Compose](#docker-compose单机生产推荐) | 单机部署，推荐的生产方式 | Compose 自带一个 MySQL 容器 | 适合 |
| [③ 外部数据库](#外部数据库) | 已有自建 MySQL 或云数据库 | 自己的 MySQL | 适合 |

三种方式用的是同一个程序，库结构也相同。体验完想正式使用时，按 ② 或 ③ 新部署一套即可，体验镜像里的演示数据不需要迁移。

镜像由公开仓库的 CI 在打 `v*` tag 时自动构建和发布，都支持 `linux/amd64` 和 `linux/arm64`。标签有 `1.1.3`、`1.1` 和 `latest`，正式环境建议固定到具体版本（如 `1.1.3`）：

| 镜像 | 用途 |
|---|---|
| `ghcr.io/jiaowc/opsflow` | 程序镜像，用于 ② 和 ③ |
| `ghcr.io/jiaowc/opsflow-allinone` | 体验镜像，用于 ① |

---

## 体验镜像（all-in-one）

一个镜像、一个容器，内置 MySQL 8.0 和演示数据，`docker run` 之后直接可用。**只用于体验和演示**，正式使用请用 [Docker Compose](#docker-compose单机生产推荐) 或 [外部数据库](#外部数据库)。

```bash
docker run -d --name opsflow-allinone -p 8080:8080 \
  -v opsflow-allinone-data:/data ghcr.io/jiaowc/opsflow-allinone:1.1.3
```

首次启动约 1 分钟（初始化数据库、建表、导入演示数据），`docker logs -f opsflow-allinone` 可以看进度。浏览器打开 `http://localhost:8080`，初始账号 **admin / admin**。这是公开的演示口令，首次登录时系统会强制要求修改（新密码至少 8 位）。

内存建议 ≥ 1.5GB；`JAVA_OPTS` 默认 `-Xms256m -Xmx512m`，可用 `-e JAVA_OPTS=...` 调整。

也可以用 Compose 示例启动（本地没有镜像时自动拉取，加 `--build` 则从源码构建）：

```bash
docker compose -f docker-compose.allinone.yml up -d
```

拉取不了 ghcr.io，或想用当前源码构建时：

```bash
docker build -f Dockerfile.allinone -t ghcr.io/jiaowc/opsflow-allinone:1.1.3 .
```

### 带了哪些数据

| 来源 | 内容 |
|------|------|
| 默认数据（Flyway，和其他部署方式相同） | 管理员 `admin`、5 个角色及权限、步骤定义、Dockerfile / Deployment / Service 模版、通用流水线 |
| 演示数据（`scripts/docker/allinone/demo-data.sql`） | 项目 `demo-shop`、占位集群 `demo-k8s`、环境 `dev` / `test` / `prod`、组件 `github` 和 `harbor-demo`、3 个示例服务、3 个流水线视图 |

演示数据里**没有任何凭据**，也没有构建 / 部署节点，集群和 Harbor 都是 `example.com` 占位地址。可以浏览和配置，但要真正跑通流水线，需要在界面上添加自己的构建节点、集群 kubeconfig 和镜像仓库。

不想要演示数据（只保留默认数据）：首次启动时加 `-e OPSFLOW_DEMO_DATA=false`。这个开关只在首次启动时生效。

### 数据与持久化

所有数据都在容器的 `/data` 下：

| 路径 | 内容 |
|------|------|
| `/data/mysql` | 数据库文件 |
| `/data/app` | 流水线日志、凭据加密主密钥 `secret.key` 等 |
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

停止时入口脚本会先停应用、再正常关闭 MySQL。`docker stop` 默认只等 10 秒，建议 `docker stop -t 60 opsflow-allinone`（Compose 示例里已设 `stop_grace_period: 1m`）。即使被强杀，下次启动 InnoDB 也会自动恢复。

---

## Docker Compose（单机生产推荐）

一条命令拉起 **MySQL + OpsFlow** 两个容器。

### 环境要求

- Docker Engine 20+ 或 Docker Desktop，带 Compose V2（`docker compose`）
- 建议可用内存 ≥ 2GB（MySQL + JVM）
- 不需要在本机安装 JDK 和 Maven：从源码构建时在镜像里编译；也可以直接用发布的镜像

### 启动

```bash
cd OpsFlow
cp .env.example .env          # 生产环境务必修改里面的密码
./scripts/deploy/compose-up.sh
# 等价于：docker compose up -d --build
```

浏览器打开 `http://localhost:8080`，初始账号 **admin / admin**，首次登录会被要求修改密码。

不想在本机编译时，在 `.env` 里设置 `OPSFLOW_DOCKER_IMAGE=ghcr.io/jiaowc/opsflow:1.1.3`，然后用发布的镜像启动：

```bash
docker compose pull opsflow
docker compose up -d --no-build
```

| 操作 | 命令 |
|------|------|
| 查看状态 | `docker compose ps` |
| 应用日志 | `docker compose logs -f opsflow` |
| 停止（保留数据） | `./scripts/deploy/compose-down.sh` |
| 停止并清空数据库 | `./scripts/deploy/compose-down.sh --volumes`（**数据全部删除，不可恢复**） |

### 配置（`.env`）

| 变量 | 说明 | 默认 |
|------|------|------|
| `OPSFLOW_PORT` | 控制台端口 | `8080` |
| `MYSQL_PORT` | MySQL 映射到宿主机的端口 | `3306` |
| `MYSQL_ROOT_PASSWORD` | MySQL root 密码 | `opsflow` |
| `MYSQL_PASSWORD` | 应用库用户 `opsflow` 的密码 | `opsflow` |
| `JAVA_OPTS` | JVM 参数 | `-Xms256m -Xmx512m` |
| `OPSFLOW_WEBHOOK_SECRET` | Git Webhook 密钥，空则关闭 Webhook | 空 |
| `OPSFLOW_SECRET_KEY` | 凭据加密主密钥（Base64 编码的 32 字节，`openssl rand -base64 32` 生成）；空则首次启动自动生成 `./data/secret.key` | 空 |
| `OPSFLOW_DOCKER_IMAGE` | 应用镜像名和标签 | `opsflow:1.1.3`（本机构建） |

应用的数据库连接统一用 `DB_URL` / `DB_USERNAME` / `DB_PASSWORD`。全栈 Compose 会自动注入指向 `mysql` 容器的地址，不需要自己填。

持久化目录：

| 位置 | 内容 |
|---|---|
| `./data` | 流水线日志、凭据加密主密钥 `secret.key`（未配置 `OPSFLOW_SECRET_KEY` 时）等 |
| `./logs` | 应用日志目录；应用本身输出到 `docker compose logs` |
| Docker volume `opsflow-mysql` | 数据库文件 |

表结构和默认数据由应用启动时按版本自动建立和升级（Flyway），MySQL 容器只提供一个空库。

---

## 外部数据库

程序连接已有的 MySQL（自建或云数据库），可以用程序容器，也可以直接跑 jar。

### 1. 准备数据库

- MySQL 8.0（Compose 和体验镜像使用的版本），字符集 `utf8mb4`。
- 建一个空库和应用账号。表结构和默认数据（角色权限、初始管理员、步骤定义、默认模版、通用流水线）由应用启动时自动建立：

```bash
# 示例，按你们的环境修改连接参数和密码
mysql -h <主机> -u root -p -e "CREATE DATABASE IF NOT EXISTS opsflow DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"
```

- 应用账号需要这个库的建表和读写权限，因为启动时要执行迁移。如果生产库不允许应用账号改表结构，见 [上线检查清单](#生产上线检查清单) 里的 `OPSFLOW_DB_MIGRATE`。

**示例：单独起一个 MySQL 容器。** 手上没有现成的 MySQL、又想把数据库和程序分开管理时，可以这样起一个：

```bash
docker run -d --name opsflow-db --restart unless-stopped \
  -e MYSQL_ROOT_PASSWORD=<root 密码> \
  -e MYSQL_DATABASE=opsflow -e MYSQL_USER=opsflow -e MYSQL_PASSWORD=<应用密码> \
  -p 3306:3306 -v opsflow-db:/var/lib/mysql \
  mysql:8.0 --character-set-server=utf8mb4 --collation-server=utf8mb4_unicode_ci
```

### 2a. 用程序容器连接

在 `.env` 中设置：

```bash
DB_URL=jdbc:mysql://host.docker.internal:3306/opsflow?useUnicode=true&characterEncoding=utf8&connectionCollation=utf8mb4_unicode_ci&serverTimezone=Asia/Shanghai
DB_USERNAME=opsflow
DB_PASSWORD=你的密码
```

容器里的 `127.0.0.1` 指容器自己，不是宿主机。数据库在宿主机上时用 `host.docker.internal`；Linux 上没有这个名字时，改成宿主机 IP，或在 compose 里加 `extra_hosts`。数据库在别的机器或云上时，直接写它的地址。

只启动应用：

```bash
./scripts/deploy/compose-up.sh --external-db
# 等价于：docker compose -f docker-compose.app.yml up -d --build
```

同样可以在 `.env` 里设置 `OPSFLOW_DOCKER_IMAGE=ghcr.io/jiaowc/opsflow:1.1.3`，用发布的镜像代替本机构建。

### 2b. 直接运行 jar

需要 JDK 17（构建和运行都要）和 Maven 3.6+。

```bash
# 1. 编译打包，所有环境共用同一个 jar
mvn clean package -DskipTests

# 2. 本机私有配置（不入库）：填 DB_PASSWORD；prod 环境还要填 DB_URL
cp config/application-local.yml.example config/application-local.yml

# 3. 在项目根目录（部署目录）下启动
SPRING_PROFILES_ACTIVE=prod java -jar app/target/opsflow.jar
```

- **必须在部署目录下启动**：jar 里不带配置文件，配置从当前目录的 `config/` 读取。部署到服务器时，把 `opsflow.jar` 和整个 `config/` 目录一起拷过去。
- `SPRING_PROFILES_ACTIVE` 选择 `config/application-<env>.yml`；`prod` 必须显式提供 `DB_URL`，避免误连默认库。
- 配置的完整规则和优先级见 [config/README.md](../../config/README.md)。

也可以用部署脚本编译并在后台启动，日志写到 `logs/app.log`：

```bash
SPRING_PROFILES_ACTIVE=prod ./scripts/deploy/restart.sh
```

脚本清单和参数见 [scripts/deploy/README.md](../../scripts/deploy/README.md)。生产服务器建议用下面的 systemd 管理进程。

---

## 生产上线检查清单

- [ ] **修改 admin 密码**：首次登录时系统会强制修改。之后给每个人建自己的账号，按角色分配权限，不要共用 admin。
- [ ] **修改默认数据库密码**：Compose 方式改 `.env` 里的 `MYSQL_ROOT_PASSWORD`、`MYSQL_PASSWORD`。改密码要在**第一次启动前**完成：MySQL 容器只在数据卷为空时按这两个值建账号，之后再改 `.env` 不会改库里的密码。
- [ ] **不对外暴露 MySQL 端口**：Compose 默认把 MySQL 映射到宿主机 `3306`。不需要从宿主机外连库时，删掉 `docker-compose.yml` 里 `mysql` 的 `ports`，或用防火墙挡住。
- [ ] **凭据主密钥和数据库一起备份**：钥匙串、组件认证信息、节点 SSH 密码和私钥、两步验证密钥都用主密钥加密存库。主密钥（`OPSFLOW_SECRET_KEY` 或 `./data/secret.key`）丢失或被换掉后，这些数据都无法解密，只能重新录入。多实例部署时要显式设置 `OPSFLOW_SECRET_KEY`，所有实例必须一致。主密钥不要提交到 Git。
- [ ] **决定是否自动迁移**：默认 `OPSFLOW_DB_MIGRATE=true`，启动时自动执行数据库迁移。设为 `false` 时启动不改库，有未执行的迁移就拒绝启动，每次升级前要先手动执行：

  ```bash
  bash scripts/database/migrate.sh --info --profile=prod   # 查看待执行的版本
  bash scripts/database/migrate.sh --profile=prod          # 执行
  ```

  脚本从 `config/` 读取数据库连接，规则和应用相同，见 [scripts/database/README.md](../../scripts/database/README.md)。
- [ ] **反向代理和 HTTPS**：对外用 Nginx 等反向代理终止 HTTPS，后端转发到 `8080`，见下面的示例。
- [ ] **进程管理**：jar 方式用 systemd 管理，见下面的示例；Compose 方式已配置 `restart: unless-stopped`。
- [ ] **日志**：容器方式用 `docker logs` 查看，注意给 Docker 配置日志轮转（如 `json-file` 的 `max-size`）。jar 方式用脚本启动时日志在 `logs/app.log`，用 systemd 时在 journald。
- [ ] **健康检查**：`GET /api/health`（也可以用 `/health`），免登录。应用和数据库都正常时返回 200，数据库连不上时返回 503，响应里带版本号：

  ```bash
  curl -fsS http://127.0.0.1:8080/api/health
  # {"status":"UP","version":"1.1.3","checks":{"app":"UP","database":"UP"},...}
  ```

  镜像和 Compose 里已经用它做健康检查，负载均衡和监控也可以直接用。
- [ ] **Webhook**：要用 Git 推送自动构建时设置 `OPSFLOW_WEBHOOK_SECRET`，为空时 Webhook 关闭。
- [ ] **数据保留**：每天 03:30 自动清理过期的阶段日志（默认 30 天）和构建记录（默认 180 天，每个任务至少保留最近 50 次），按需在 `config/application-local.yml` 的 `opsflow.retention` 下调整。

### 反向代理示例（Nginx）

```nginx
server {
    listen 443 ssl;
    server_name ops.example.com;

    ssl_certificate     /etc/nginx/ssl/ops.example.com.pem;
    ssl_certificate_key /etc/nginx/ssl/ops.example.com.key;

    location / {
        proxy_pass http://127.0.0.1:8080;
        proxy_set_header Host              $host;
        proxy_set_header X-Forwarded-Host  $host;
        proxy_set_header X-Forwarded-For   $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }
}
```

写接口会校验请求的 `Origin` / `Referer` 是否和当前站点一致（防 CSRF）。反向代理改写了 `Host`、又没有传 `X-Forwarded-Host` 时，写操作会被拒绝。这时把对外地址填到 `OPSFLOW_ALLOWED_ORIGINS`（逗号分隔，如 `https://ops.example.com`）。

上传文件的大小上限是单个 1MB、整个请求 2MB，反向代理的 `client_max_body_size` 不要小于这个值。

### systemd 示例（jar 方式）

`/etc/systemd/system/opsflow.service`：

```ini
[Unit]
Description=OpsFlow
After=network.target

[Service]
Type=simple
User=opsflow
# 部署目录：放 opsflow.jar 和 config/，data/ 和 logs/ 也会建在这里
WorkingDirectory=/opt/opsflow
Environment=SPRING_PROFILES_ACTIVE=prod
# DB_URL / DB_PASSWORD 等写在 config/application-local.yml，或放到下面这个文件里（权限 600）
EnvironmentFile=-/opt/opsflow/opsflow.env
ExecStart=/usr/bin/java -Xms256m -Xmx512m -jar /opt/opsflow/opsflow.jar
Restart=always
RestartSec=10

[Install]
WantedBy=multi-user.target
```

```bash
sudo systemctl daemon-reload
sudo systemctl enable --now opsflow
sudo systemctl status opsflow
journalctl -u opsflow -f          # 查看日志
```

---

## 备份与恢复

要备份两样东西：**数据库**和**数据目录**（`./data`，里面有凭据主密钥和流水线日志）。只备份数据库、丢了主密钥，凭据就无法解密。用 `OPSFLOW_SECRET_KEY` 环境变量提供主密钥时，把它另外妥善保存。

Compose 内置 MySQL：

```bash
# 备份数据库
docker compose exec -T mysql \
  sh -c 'exec mysqldump -uroot -p"$MYSQL_ROOT_PASSWORD" --single-transaction opsflow' > opsflow-db-$(date +%Y%m%d).sql

# 备份数据目录
tar czf opsflow-data-$(date +%Y%m%d).tar.gz data/

# 恢复数据库（会覆盖库里的同名表）
docker compose exec -T mysql \
  sh -c 'exec mysql -uroot -p"$MYSQL_ROOT_PASSWORD" opsflow' < opsflow-db-YYYYMMDD.sql
```

外部数据库：

```bash
mysqldump -h <主机> -u <账号> -p --single-transaction opsflow > opsflow-db-$(date +%Y%m%d).sql
mysql -h <主机> -u <账号> -p opsflow < opsflow-db-YYYYMMDD.sql
```

恢复时数据库和数据目录要用同一时间的备份，并保证主密钥和备份时一致。

---

## 升级

升级步骤：**备份数据库和 `./data` → 换新版本（新镜像或新 jar）→ 启动**。新版本的数据库迁移在启动时自动执行；关闭了自动迁移的环境先执行 `scripts/database/migrate.sh`。

```bash
# Compose，从源码构建
git pull && ./scripts/deploy/compose-up.sh

# Compose，用发布的镜像：先把 .env 里的 OPSFLOW_DOCKER_IMAGE 改成新版本
docker compose pull opsflow && docker compose up -d --no-build
```

不要用 `docker compose down -v` 升级，它会把数据库卷一起删掉。

每个版本的行为和接口变化、需要提前处理的事项，见 [UPGRADE.md](../UPGRADE.md)，升级前务必看一遍。

企业版和社区版库结构相同，换镜像即可升级或回退，数据保留。企业版的功能和获取方式见 <https://www.opslink.cn>。

---

## 常见问题

**8080 被占用** — Compose 方式改 `.env` 里的 `OPSFLOW_PORT`（如 `18080`）后重新 `up -d`；jar 方式在 `config/application-local.yml` 里设 `server.port`。

**首次构建很慢** — 从源码构建时要下载 Maven 依赖，之后有 Docker 层缓存会快很多。不想在本机编译，就用发布的镜像。

**数据库连接失败** — 看应用日志里的报错：
- 容器里用了 `127.0.0.1` 连宿主机的库：改成 `host.docker.internal` 或宿主机 IP；
- `DB_PASSWORD` 和库里的账号对不上：注意 Compose 的 MySQL 账号只在数据卷第一次创建时生成；
- 防火墙或云数据库白名单没有放行应用所在的机器。

**健康检查失败** — `docker compose logs opsflow` 看启动日志，确认 MySQL 已 healthy、数据库密码正确；`curl http://127.0.0.1:8080/api/health` 看是哪一项 `DOWN`。

**启动时报有未执行的迁移、拒绝启动** — 设置了 `OPSFLOW_DB_MIGRATE=false`，先执行 `bash scripts/database/migrate.sh --profile=<环境>` 再启动。

**容器内写 `./data` / `./logs` 报权限错误** — 入口脚本会尝试修正属主；仍失败时执行 `mkdir -p data logs && chmod -R a+rwX data logs`。

**凭据在页面上显示为未配置** — 通常是主密钥变了（换了 `OPSFLOW_SECRET_KEY`，或 `./data/secret.key` 丢失、没挂载）。换回原来的主密钥即可；找不回时只能重新录入凭据。

**页面上的写操作都失败，提示来源校验不通过** — 反向代理没有传 `X-Forwarded-Host`，见 [反向代理示例](#反向代理示例nginx)。

## 相关文件

| 文件 | 说明 |
|---|---|
| `Dockerfile` | 程序镜像，多阶段构建（JDK 17 编译 + JRE 17 运行） |
| `docker-compose.yml` | MySQL + 应用 |
| `docker-compose.app.yml` | 只有应用，连外部数据库 |
| `Dockerfile.allinone`、`docker-compose.allinone.yml` | 体验镜像 |
| `scripts/docker/allinone/` | 体验镜像的入口脚本、MySQL 配置、演示数据 |
| `.env.example` | 环境变量模板 |
| `scripts/deploy/` | 部署脚本，见 [scripts/deploy/README.md](../../scripts/deploy/README.md) |
| `config/` | 应用配置，见 [config/README.md](../../config/README.md) |
