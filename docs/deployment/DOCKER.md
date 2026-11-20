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
| `OPSFLOW_DOCKER_IMAGE` | 镜像名/标签 | `opsflow:1.0.0` |

> ⚠️ **凭据加密主密钥**：钥匙串、组件认证信息、节点 SSH 密码/私钥都以 AES-256-GCM 加密存库。
> 主密钥（`OPSFLOW_SECRET_KEY` 或 `./data/secret.key`）丢失或被更换后，已保存的凭据无法解密，只能重新录入。
> 请把它和数据库一起备份；不要提交到 Git。从旧版本升级时，首次启动会自动把历史明文凭据加密回写。

应用数据库连接统一走 **`DB_URL` / `DB_USERNAME` / `DB_PASSWORD`**（与传统部署一致）。全栈 Compose 会自动注入指向容器 `mysql` 的地址。

持久化目录：

- `./data` — 品牌 Logo、流水线日志、凭据加密主密钥 `secret.key`（未配置 `OPSFLOW_SECRET_KEY` 时）等
- `./logs` — 应用日志
- Docker volume `opsflow-mysql` — 数据库文件

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

表结构和默认数据由应用启动时按版本自动建立和升级，与 MySQL 数据卷是否新建无关；升级不要依赖 `down -v`（那会清空全部数据）。

## 常见问题

**8080 被占用** — 改 `.env` 中 `OPSFLOW_PORT=18080` 后重启。

**构建很慢** — 首次多阶段编译会拉 Maven 依赖；后续有 Docker 层缓存会快很多。

**容器内写 `./data` / `./logs` 权限错误** — 入口脚本会尝试修正属主；仍失败时可：`mkdir -p data logs && chmod -R a+rwX data logs`。

**健康检查失败** — `docker compose logs opsflow`，确认 MySQL 已 healthy、`DB_PASSWORD` 与库一致。

## 相关文件

- `Dockerfile` — 多阶段构建（JDK 17 编译 + JRE 17 运行）
- `docker-compose.yml` — 一键全栈
- `docker-compose.app.yml` — 仅应用 / 外部库
- `.env.example` — 环境变量模板
- `scripts/deploy/compose-up.sh` / `compose-down.sh` — 包装脚本
