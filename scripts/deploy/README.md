# 部署脚本

部署方式、配置、上线检查和备份见 [docs/deployment/DEPLOYMENT.md](../../docs/deployment/DEPLOYMENT.md)。本文只列脚本和用法。所有脚本都在项目根目录执行。

## 脚本清单

| 脚本 | 说明 |
|------|------|
| `compose-up.sh` | Docker Compose 启动（MySQL + 应用）；加 `--external-db` 只启动应用、连外部数据库 |
| `compose-down.sh` | 停止 Compose；加 `--volumes` 同时删除数据库卷（数据不可恢复） |
| `start.sh` | 后台启动 jar；加 `--docker` 改为单容器方式 |
| `stop.sh` | 停止应用；加 `--docker` 停止单容器 |
| `restart.sh` | 停止 → 编译（`mvn clean package -DskipTests`）→ 启动；加 `--docker` 改为构建镜像后启动容器 |
| `common.sh` | 公共变量和函数，被上面的脚本引用，不单独执行 |

## 用法

```bash
# Compose
./scripts/deploy/compose-up.sh
./scripts/deploy/compose-up.sh --external-db
./scripts/deploy/compose-down.sh

# jar（指定环境；不设置则只用公共配置）
SPRING_PROFILES_ACTIVE=prod ./scripts/deploy/restart.sh
./scripts/deploy/start.sh
./scripts/deploy/stop.sh

# 单容器（兼容旧流程，新部署建议用 Compose）
SPRING_PROFILES_ACTIVE=dev ./scripts/deploy/restart.sh --docker
./scripts/deploy/stop.sh --docker
# 等价写法：OPSFLOW_DEPLOY_MODE=docker ./scripts/deploy/restart.sh
```

## jar 方式的行为

- 在项目根目录启动，配置从 `config/` 读取：按 `SPRING_PROFILES_ACTIVE` 选择 `config/application-<env>.yml`，并读取本机私有配置 `config/application-local.yml`。
- 日志写到 `logs/app.log`。每次启动会把上一次的日志改名为 `app.log.<时间戳>.bak`，只保留最近 5 个（`OPSFLOW_LOG_BACKUP_KEEP` 可调）。
- `OPSFLOW_MAVEN_PROFILE` 已废弃，设置时等同于 `SPRING_PROFILES_ACTIVE`。

## 单容器方式的行为

| 项 | 默认 | 覆盖 |
|---|---|---|
| 镜像名 | `opsflow:1.1.3` | `OPSFLOW_DOCKER_IMAGE` |
| 容器名 | `opsflow` | `OPSFLOW_DOCKER_CONTAINER` |
| 端口 | `8080` | `OPSFLOW_PORT` |

- 挂载 `config/` → `/app/config/`、`logs/` → `/app/logs`、`data/` → `/app/data`。
- 透传宿主机已设置的 `SPRING_PROFILES_ACTIVE`、`DB_URL`、`DB_USERNAME`、`DB_PASSWORD`。
- 已添加 `host.docker.internal`。容器里不能用 `127.0.0.1` 指宿主机，连宿主机的 MySQL 时在 `config/application-local.yml` 里写：

  ```yaml
  DB_URL: "jdbc:mysql://host.docker.internal:3306/opsflow?useUnicode=true&characterEncoding=utf8&connectionCollation=utf8mb4_unicode_ci&serverTimezone=Asia/Shanghai"
  ```

- 查看日志：`docker logs -f opsflow`。
