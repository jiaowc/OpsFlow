# 部署脚本目录

## 脚本说明

| 脚本 | 说明 |
|------|------|
| `start.sh` | 启动应用 |
| `stop.sh` | 停止应用 |
| `restart.sh` | 重启（停止 → 编译/构建 → 启动） |
| `common.sh` | 公共变量与 Docker 辅助函数（被上面脚本引用） |

## 本地模式（默认）

```bash
./scripts/deploy/start.sh
./scripts/deploy/stop.sh
./scripts/deploy/restart.sh

# 指定环境（默认 local）
SPRING_PROFILES_ACTIVE=dev ./scripts/deploy/start.sh
SPRING_PROFILES_ACTIVE=dev ./scripts/deploy/restart.sh
```

- `restart.sh` 会按 `OPSFLOW_MAVEN_PROFILE`（未设置则回退 `SPRING_PROFILES_ACTIVE`，再回退 `local`）执行 `mvn clean package -P<profile>`
- `start.sh` / `restart.sh` 会读取整个 `config/` 目录，并按 `SPRING_PROFILES_ACTIVE` 选择 `application-<profile>.yml`
- 日志：`logs/app.log`

## Docker 模式

流程：**本地 Maven 编译出 jar → 再基于 jar 打 Docker 镜像**（镜像内不再编译）。

```bash
# 推荐：自动 mvn package + docker build + 启动容器
SPRING_PROFILES_ACTIVE=dev ./scripts/deploy/restart.sh --docker

# 手动分步
mvn clean package -DskipTests
docker build -t opsflow:1.0.0 .
./scripts/deploy/start.sh --docker

# 停止
./scripts/deploy/stop.sh --docker
```

等价环境变量：

```bash
OPSFLOW_DEPLOY_MODE=docker ./scripts/deploy/restart.sh
```

### Docker 行为说明

- 镜像名默认：`opsflow:1.0.0`（可用 `OPSFLOW_DOCKER_IMAGE` 覆盖）
- 容器名默认：`opsflow`（可用 `OPSFLOW_DOCKER_CONTAINER` 覆盖）
- 端口默认：`8080`（可用 `OPSFLOW_PORT` 覆盖）
- `Dockerfile` 仅 `COPY target/opsflow.jar`，需先完成本地编译
- 挂载：
  - `config/` → `/app/config/`
  - `logs/` → `/app/logs`
  - `data/` → `/app/data`
- 已添加 `host.docker.internal`，便于容器访问宿主机 MySQL

### 数据库配置注意

容器内不能使用 `127.0.0.1` 指宿主机。请在对应 profile 文件（如 `config/application-dev.yml`）中将数据源改为：

```yaml
url: jdbc:mysql://host.docker.internal:3306/opsflow?...
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
