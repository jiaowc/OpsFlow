# 本地 Docker 构建节点（调试）

用两个容器模拟**独立部署的构建机**，经 SSH 接入 OpsFlow：

| 节点名 | SSH | 账号 |
|--------|-----|------|
| `local-build-1` | `127.0.0.1:2222` | root / `Build12345` |
| `local-build-2` | `127.0.0.1:2223` | root / `Build12345` |

容器内含：**JDK8、Maven、Node 22（npm）、Git、Docker CLI**；挂载宿主机 `/var/run/docker.sock`，`docker build/push` 实际打在本机 Docker（可推本地 Harbor `localhost/...`）。

工作目录：`~/opsflow`（容器内 `/root/opsflow`，已卷持久化到 `.data/`）。

## 启动

```bash
cd scripts/local/build-nodes
chmod +x *.sh
./start.sh
./configure-opsflow.sh
```

停止：

```bash
./stop.sh
```

## 接到流水线

1. OpsFlow「节点管理」应看到 `local-build-1`、`local-build-2`，可点「环境检测」
2. 编辑流水线模版，构建节点勾选这两台（或其中一台）
3. 跑含 `checkout` / `build` / `docker_build` / `push_image` 的流水线

## 说明

- 正式环境仍是真实机器 SSH 接入；本目录仅本地调试
- 不修改、不删除你已有的远程节点（如 `dev-build-1`）
- Apple Silicon 上基础镜像为多架构时走 arm64；`docker.io` 包提供 CLI 即可
- 可选安装 `sshpass` 以便 `start.sh` 自动跑 SSH 冒烟

## 环境变量

见 `env.sh`：端口、密码、节点名、镜像名等。
