# 部署脚本目录

## 脚本说明

| 脚本 | 说明 |
|------|------|
| `compose-up.sh` | **推荐**：Docker Compose 一键启动（镜像内编译） |
| `compose-down.sh` | 停止 Compose（可选 `--volumes` 清空库） |
| `start.sh` | 传统方式启动 jar（或 `--docker` 旧流程） |
| `stop.sh` | 停止应用 |
| `restart.sh` | 重启（停止 → 编译/构建 → 启动） |
| `common.sh` | 公共变量与 Docker 辅助函数（被上面脚本引用） |

## Compose 一键安装（推荐）

```bash
cp .env.example .env
./scripts/deploy/compose-up.sh
./scripts/deploy/compose-down.sh
# 外部 MySQL：
./scripts/deploy/compose-up.sh --external-db
```

详见 [docs/deployment/DOCKER.md](../../docs/deployment/DOCKER.md)。

## 本地 jar 模式

```bash
./scripts/deploy/start.sh
./scripts/deploy/stop.sh
./scripts/deploy/restart.sh

# 指定环境（不设置则只用公共配置）
SPRING_PROFILES_ACTIVE=dev ./scripts/deploy/start.sh
SPRING_PROFILES_ACTIVE=dev ./scripts/deploy/restart.sh
```

- `restart.sh` 执行 `mvn clean package -DskipTests`，所有环境共用同一个 jar
- 在项目根目录启动，配置全部从 `config/` 读取：按 `SPRING_PROFILES_ACTIVE` 选择 `config/application-<env>.yml`，并读取 `config/application-local.yml`（本机私有配置，如 `DB_PASSWORD`）
- `OPSFLOW_MAVEN_PROFILE` 已废弃，设置时等同于 `SPRING_PROFILES_ACTIVE`
- 日志：`logs/app.log`；每次启动会把上一次的日志改名为 `app.log.<时间戳>.bak`，只保留最近 5 个（`OPSFLOW_LOG_BACKUP_KEEP` 可调）

## Docker 单容器模式（兼容旧脚本）

新安装请优先用上方 **Compose 一键安装**。根目录 `Dockerfile` 已改为多阶段构建，可直接 `docker build` 而无需本机先 `mvn package`。

```bash
SPRING_PROFILES_ACTIVE=dev ./scripts/deploy/restart.sh --docker

# 或
docker build -t opsflow:1.1.0 .
./scripts/deploy/start.sh --docker

# 停止
./scripts/deploy/stop.sh --docker
```

等价环境变量：

```bash
OPSFLOW_DEPLOY_MODE=docker ./scripts/deploy/restart.sh
```

### Docker 行为说明

- 镜像名默认：`opsflow:1.1.0`（可用 `OPSFLOW_DOCKER_IMAGE` 覆盖）
- 容器名默认：`opsflow`（可用 `OPSFLOW_DOCKER_CONTAINER` 覆盖）
- 端口默认：`8080`（可用 `OPSFLOW_PORT` 覆盖）
- `Dockerfile` 多阶段编译并打入 `config/`（不含 `application-local.yml`）
- 透传宿主机已设置的 `SPRING_PROFILES_ACTIVE`、`DB_URL`、`DB_USERNAME`、`DB_PASSWORD`
- 挂载：
  - `config/` → `/app/config/`
  - `logs/` → `/app/logs`
  - `data/` → `/app/data`
- 已添加 `host.docker.internal`，便于容器访问宿主机 MySQL

### 数据库配置注意

容器内不能使用 `127.0.0.1` 指宿主机。请在 `config/application-local.yml` 中设置：

```yaml
DB_URL: "jdbc:mysql://host.docker.internal:3306/opsflow?useUnicode=true&characterEncoding=utf8&connectionCollation=utf8mb4_unicode_ci&serverTimezone=Asia/Shanghai"
```

### 常用 Docker 命令

```bash
docker logs -f opsflow
docker exec -it opsflow sh
docker images | grep opsflow
```

## 日志

```bash
# 本地
tail -f logs/app.log

# Docker
docker logs -f opsflow
```
