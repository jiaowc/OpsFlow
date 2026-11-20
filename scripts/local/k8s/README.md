# 本地 kind K8s（可销毁）

用 **Docker 容器** 跑一套临时 Kubernetes + 部署节点，**不写入本机 `~/.kube`**，测完可一键清空。

| 资源 | 说明 |
|------|------|
| kind 集群 `opsflow` | 单 control-plane 容器 |
| `local-deploy` | SSH `127.0.0.1:2224`，内置 kubectl |
| 账号 | root / `Build12345` |

## 依赖

```bash
brew install kind kubectl
# 可选：sshpass（冒烟 SSH）
```

## 启动

```bash
cd scripts/local/k8s
chmod +x *.sh
./start.sh
./configure-opsflow.sh
```

## 停止 / 销毁

```bash
./stop.sh       # 只停部署节点，保留 kind 集群
./destroy.sh    # 删除 kind 集群 + 部署容器 + .data（推荐测完用这个）
```

## 接到 OpsFlow

```bash
./configure-opsflow.sh
```

接入后应看到：

1. 「节点管理」→ `local-deploy`（环境检测应通过）
2. 「集群管理」→ `local-kind`（检测时代理节点选 `local-deploy`）
3. 流水线模版 **local-backend-cicd**：构建节点 local-build-*，部署节点 local-deploy
4. 环境 `dev` → 集群 `local-kind`，命名空间 `dev`

## Harbor 镜像拉取

`start.sh` 会在 kind 节点内做 `localhost:80 → 宿主机 Harbor` 桥接。若节点重启后拉镜像失败，可再执行：

```bash
./harbor-bridge.sh
```

## 说明

- 正式环境仍是真实集群；本目录仅本地调试
- kubeconfig 只写在 `scripts/local/k8s/.data/`，**不污染**本机 `~/.kube`
- Harbor 镜像 `localhost/...`：kind 节点已配置 mirror 到 `host.docker.internal`
- 销毁后 OpsFlow 库里的 `local-deploy` / `local-kind` 记录仍在，但连不上；下次 `./start.sh && ./configure-opsflow.sh` 会刷新 kubeconfig
