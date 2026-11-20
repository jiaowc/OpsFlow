# 本地 Harbor + Nexus（对接 OpsFlow 测试）

在本机 Docker 中启动：

- **Harbor**（官方 online installer，HTTP）：镜像仓库 + `/api/v2.0` API  
- **Nexus 3**：仅用于「凭据与组件」连通性检测（当前流水线推镜像**不走** Nexus）

默认账号：

| 服务 | URL | 用户 | 密码 |
|------|-----|------|------|
| Harbor | http://localhost | admin | `Harbor12345` |
| Nexus | http://127.0.0.1:8081 | admin | `Nexus12345` |

数据与安装包在 `.data/`（已 gitignore），可反复 `stop` / `start`。

## 前置

- Docker Desktop 已启动，建议可用内存 ≥ 4GB（Harbor 官方镜像目前多为 `linux/amd64`，Apple Silicon 会走模拟，首次较慢）  
- 本机 **80**、**8081** 端口空闲（OpsFlow 仍用 8080）  
- Harbor **不允许** `127.0.0.1` 作为 hostname，脚本默认用 `localhost`  
- 推 HTTP 镜像：Docker Desktop 需允许 `127.0.0.0/8` 为 insecure（近年 Desktop 默认已有；`docker info` 里看 Insecure Registries）。本机已验证 `docker login localhost` 可用。

若没有，在 Docker Desktop → Settings → Docker Engine 增加后 Apply & Restart：

```json
"insecure-registries": ["127.0.0.0/8", "localhost", "127.0.0.1"]
```

构建若跑在**其它机器**上的 Docker，也要在那台机器同样配置。

若 OpsFlow 进程在改组件配置**之前**已经调过 Harbor API，建议重启一次应用（HarborClient 会缓存首次初始化结果）。

## 启动

```bash
cd scripts/local/harbor-nexus
chmod +x *.sh
./start.sh                 # Nexus + Harbor
./configure-opsflow.sh     # 改本机 OpsFlow 库里的组件/钥匙串
```

单独启动：

```bash
./start-nexus.sh
./start-harbor.sh
```

停止（保留数据）：

```bash
./stop.sh
```

## 在 OpsFlow 里确认

1. 打开「凭据与组件」  
2. 应看到启用中的 `harbor-local`、`nexus-local`  
3. 分别点「检测」，应成功  
4. 流水线 `docker_build` / `push_image` 使用的 registry 主机为 `localhost`（从组件 URL 解析）  
5. Harbor 已建 public project：`library`、`opsflow`；环境的 K8s namespace 若用作镜像项目名，需在 Harbor 里也有同名 project

## 本机推送冒烟

```bash
docker pull busybox:1.36
docker tag busybox:1.36 localhost/opsflow/smoke:1
echo 'Harbor12345' | docker login localhost -u admin --password-stdin
docker push localhost/opsflow/smoke:1
```

## 流水线联调最短路径

1. 构建节点本机 Docker 可用，且 insecure-registries 已覆盖 `127.0.0.0/8`（含 localhost）  
2. 服务的「对外端口」等照常；环境的 `k8sNamespace` 建议先用 `opsflow`（或在 Harbor 建同名 project）  
3. 流水线步骤含：`docker_build` → `push_image`（及后续部署按需）  
4. 构建日志里应出现 `docker login ... localhost` 与 `docker push localhost/...`

## 环境变量（可选）

见 `env.sh`：`HARBOR_VERSION`、`HARBOR_HTTP_PORT`、`HARBOR_ADMIN_PASSWORD`、`NEXUS_HTTP_PORT`、`NEXUS_ADMIN_PASSWORD`、`HARBOR_TEST_PROJECT` 等。
