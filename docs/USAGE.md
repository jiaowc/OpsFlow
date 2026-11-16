# OpsFlow 使用文档

> 面向平台管理员与日常使用者：从零完成「接入资源 → 配置流水线 → 构建发布 → 上线审批」的全流程。
> 安装部署见 [deployment/DOCKER.md](deployment/DOCKER.md)，开发与配置见根目录 [README.md](../README.md)。

---

## 目录

1. [快速上手（5 分钟跑通一次构建）](#1-快速上手)
2. [核心概念](#2-核心概念)
3. [首次初始化：管理员配置顺序](#3-首次初始化管理员配置顺序)
4. [日常使用：工作视图与流水线 Job](#4-日常使用工作视图与流水线-job)
5. [上线任务与审批（Pro）](#5-上线任务与审批pro)
6. [流水线配置详解](#6-流水线配置详解)
7. [用户、角色与权限](#7-用户角色与权限)
8. [集成与通知、品牌、License](#8-集成与通知品牌license)
9. [Git Webhook 推送自动构建](#9-git-webhook-推送自动构建)
10. [常见问题排查](#10-常见问题排查)
11. [附录](#11-附录)

---

## 1. 快速上手

```bash
cp .env.example .env
./scripts/deploy/compose-up.sh
```

浏览器打开 `http://<服务器>:8080`，用 **admin / 123456** 登录（首次登录后请立即在「用户管理」修改密码）。

想在本机完整体验构建→推镜像→部署，可依次拉起仓库自带的本地调试环境（都在 `scripts/local/` 下，各自带 `configure-opsflow.sh` 自动把资源注册到 OpsFlow）：

| 目录 | 作用 |
|------|------|
| `scripts/local/build-nodes` | 两个 SSH 构建节点容器（JDK8 / Maven / Git / Docker CLI） |
| `scripts/local/harbor-nexus` | 本地 Harbor 镜像仓库 + Nexus |
| `scripts/local/k8s` | 本地 kind K8s 集群 |

```bash
cd scripts/local/build-nodes && ./start.sh && ./configure-opsflow.sh
```

然后进入「工作视图 → 新建 Job」，选服务、环境、分支，点「构建」即可。

---

## 2. 核心概念

| 概念 | 说明 |
|------|------|
| **项目** | 服务、流水线、上线任务的隔离边界。成员只能看到/操作自己所在项目的资源 |
| **服务** | 一个 Git 仓库对应的可部署单元（后端 / 前端 / lib），绑定 Git 组件与对外端口 |
| **环境** | 部署目标：关联一个 K8s 集群 + 命名空间，分为 **非生产 / 生产** 两类 |
| **集群** | K8s 集群，通过钥匙串中的 Kubeconfig 访问 |
| **节点** | 通过 SSH 接入的机器：**构建节点**（拉代码、编译、打镜像）和 **部署节点**（执行 kubectl） |
| **钥匙串** | 统一保存凭据（账户密码 / Token / Kubeconfig / SSH 密钥等），被组件、集群引用 |
| **组件** | 外部系统连接：GitLab、GitHub、Harbor、Nexus、SonarQube、K8s 等 |
| **步骤** | 流水线的最小执行单元（拉代码、构建、镜像制作、部署……） |
| **流水线模版** | 若干步骤的有序组合，类型为 CI / CD / CI/CD |
| **Job** | 「服务 + 环境 + 流水线模版 + 分支/Tag」的组合，日常点「构建」就是运行 Job |
| **视图** | 按环境筛选 Job 的 Tab，可按角色分配可见性 |
| **上线任务** | 生产发布单：多个服务模块 + 审批流 + CD 流水线（Pro 功能） |

关系示意：

```
项目 ─┬─ 服务 ──(Git 组件 + 仓库路径)
      │
环境 ─┴─ 集群 ── 钥匙串(Kubeconfig)
      
Job = 服务 + 环境 + 流水线模版 + 分支/Tag ──运行于──> 构建节点 / 部署节点
上线任务 = 多个服务版本 + 集群/Namespace + 审批流 + CD 流水线模版
```

---

## 3. 首次初始化：管理员配置顺序

依赖关系决定了顺序，建议严格按下表从上到下配置：

| 步骤 | 菜单 | 要做什么 |
|------|------|----------|
| ① | 配置 → 凭据与组件 → **钥匙串管理** | 录入 Git 账号/Token、Harbor 账号、Kubeconfig、SSH 密钥 |
| ② | 配置 → 凭据与组件 → **组件管理** | 添加 GitLab/GitHub、Harbor 等组件，关联①的钥匙，点「测试连接」 |
| ③ | **节点管理** | 添加构建节点、部署节点，点「环境检查」 |
| ④ | **集群管理** | 添加 K8s 集群，关联①中的 Kubeconfig 钥匙，点「连通检测」 |
| ⑤ | **环境配置** | 添加环境，关联集群、填写 Namespace、选择环境类型 |
| ⑥ | **项目管理** | 新建项目，设置负责人与成员 |
| ⑦ | **服务管理** | 在项目下添加服务 |
| ⑧ | 配置 → **流水线配置** | 检查步骤、模版（系统已内置默认步骤与模版，可直接使用） |
| ⑨ | **用户管理** | 添加用户、分配角色 |
| ⑩ | 配置 → 流水线配置 → **视图管理**（可选） | 按环境建视图并分配可见角色 |

### 3.1 钥匙串管理

「添加钥匙」→ 填写名称、选择凭证类型：

| 凭证类型 | 典型用途 | 需填写 |
|----------|----------|--------|
| 账户密码 | Git、Harbor | 用户名、密码 |
| Token | GitLab / GitHub 访问令牌 | Token |
| API 密钥 | 各类 API | API 密钥 |
| OAuth | OAuth 应用 | Client ID / Secret |
| K8s 集群 | 集群访问 | Kubeconfig 内容（或文件路径） |
| SSH 账户密码 / SSH 公钥私钥 | 节点登录 | 用户名 + 密码 / 私钥（可选口令） |

> 钥匙内容保存后不会明文回显；禁用钥匙会导致引用它的组件/集群不可用。

### 3.2 组件管理

「添加组件」→ 选择组件类型、填写访问地址，认证方式二选一：

- **关联钥匙串**（推荐）：下拉里只显示与该组件类型兼容的钥匙（如 K8s 组件只能选 Kubeconfig 类型）；
- **手动填写认证信息**。

保存后点「测试连接」确认可用。服务的 Git 仓库、镜像推送的 Harbor 都依赖这里。

### 3.3 节点管理

「添加节点」字段说明：

| 字段 | 说明 |
|------|------|
| 节点类型 | **构建节点**：执行拉代码/编译/docker build/push；**部署节点**：执行 kubectl |
| 标签 | 可用于分组与选择 |
| SSH 主机地址 / 端口 | 节点 SSH 地址 |
| 远程工作目录 | 构建工作区根目录，默认 `~/opsflow` |
| 认证方式 | 账户密码 或 私钥（可带私钥口令） |

保存后点 **环境检查**，后端会 SSH 登录并检测依赖，同时回写在线/离线状态：

- 构建节点必检：SSH、JDK、Maven、Git；可选：Docker（需要打镜像时必须有）
- 部署节点必检：SSH、kubectl；可选：K8s 集群连通（需 kubeconfig 正确）

### 3.4 集群管理与环境配置

1. 「添加集群」：填写集群名称、地址，**关联 Kubeconfig 钥匙串**，保存后点「连通检测」。
2. 「添加环境」：

| 字段 | 说明 |
|------|------|
| 环境名称 | 如 dev / test / prod |
| 关联集群 | 部署到哪个 K8s 集群 |
| K8s 命名空间 | 部署到的 Namespace，模版中用 `${namespace}` 引用 |
| 环境类型 | **非生产环境** / **生产环境**。生产环境不能在工作视图直接建 Job 构建，需走上线任务；生产回滚需 Pro |

### 3.5 项目与服务

- **项目管理**：新建项目、指定负责人、维护成员。成员角色决定能在项目内执行哪些操作；拥有 `project:manage` 的用户可查看和管理所有项目。
- **服务管理** →「添加服务」：

| 字段 | 说明 |
|------|------|
| 所属项目 | 只能选你有写权限的项目 |
| 服务名称 | 会生成服务标识 `serviceName`（用于镜像名、K8s 资源名） |
| 服务类型 | 后端 / 前端 / lib |
| 对外提供服务端口 | 模版中 `${servicePort}` / `${port}`，默认 8080 |
| 组件 | 选择 Git 组件（GitLab/GitHub） |
| 仓库路径 | 如 `group/repo` |
| 负责人 | 从项目成员中选择 |

---

## 4. 日常使用：工作视图与流水线 Job

### 4.1 新建 Job

「工作视图」→「新建 Job」：

1. 选择 **服务**、**部署环境**（仅非生产环境）、**Pipeline 模板**（CI 或 CI/CD）；
2. 选择 Git 类型（分支 / Tag），点「加载」拉取仓库的分支或 Tag 列表后选择；
3. 可选勾选「**代码推送到该分支时自动构建**」（见第 9 节）；
4. 保存。

### 4.2 运行与查看

| 操作 | 说明 |
|------|------|
| **构建** | 触发一次运行；同一个 Job 运行中不可重复触发或编辑 |
| 列表内切换分支/Tag | 直接在列表单元格下拉修改 Job 的 Git 引用 |
| **详情 / 阶段视图** | 查看每次构建的各阶段状态、耗时；点阶段卡片看**阶段日志**（运行中自动刷新） |
| **重试** | CD 类型 Job 失败后可重试 |
| **运行实例** | 查看该服务在 K8s 中的 Pod、当前镜像、重启次数，查看 Pod 日志（含上一次崩溃日志） |
| **回滚** | 见 4.3 |
| 搜索 | 按 服务 / 仓库 / 分支 / 环境 / 用户 过滤 |
| 视图 Tab | 页面顶部按环境切换视图；有配置权限的用户可拖拽调整顺序 |

构建状态：`PENDING`（排队）→ `BUILDING`（构建中）→ `DEPLOYING`（部署中）→ `SUCCESS` / `FAILED`。
OpsFlow 服务重启后，会自动回收因重启而中断的流水线，不会一直卡在运行中（需要时重新点「构建」即可）。

### 4.3 回滚

适用于包含镜像部署的 CD / CI/CD Job：

1. 点「回滚」，从历史**成功构建的镜像**中选择目标版本；
2. 确认后执行回滚部署。

限制：

- 需要 `pipeline:rollback` 权限；
- **生产环境**回滚需额外勾选确认，且 License 需开通 `prod_rollback`（Pro）。

---

## 5. 上线任务与审批（Pro）

需 License 开通 `deploy_approval`。

### 5.1 先配置审批流

「审批流程」→「创建审批流」：填写名称、按顺序添加审批步骤；每一步可多选审批人，**任一人审批即通过该步**；不选审批人则任意登录用户可审批。保存后需为「启用」状态。

### 5.2 创建上线任务

「上线任务」→「创建上线任务」：

| 字段 | 说明 |
|------|------|
| 所属项目 / 任务名称 | — |
| 上线模块 | 每行一个：服务 + Harbor 项目 + 镜像版本（版本从 Harbor 拉取）；可调整顺序 |
| 上线集群 / Namespace | 部署目标 |
| 部署策略 | **串行**（按模块顺序逐个）/ **并行**（并发数默认 3，最大 20） |
| 审批流 | 选择已启用的审批流 |
| CD 流水线模版 | 发布时执行的部署流水线 |
| 审批通知方式 | 站内信 / 飞书（需在「集成与通知」开启） |

### 5.3 生命周期

```
编辑中 ──锁定──> 已锁定 ──提交审批──> 待审批 → 审批中 ──全部通过──> 待发布 ──发布──> 发布中 → 已发布
   ↑              │                              │                                         └─> 失败
   └────解锁──────┘                              └──拒绝──> 已拒绝
```

- **编辑中**：多人协作编辑模块；
- **锁定**：冻结模块，锁定后不可编辑；解锁会取消进行中的审批（解锁他人锁定的任务需 `deploy:unlock`）；
- **提交审批** → 审批人在任务列表/站内信中通过或拒绝（需 `deploy:approve`）；
- **发布**：审批全部通过后才能点击，按部署策略执行 CD 流水线；
- **删除**：会一并清除关联的审批记录和 CD 构建（需 `deploy:delete`）。

---

## 6. 流水线配置详解

菜单「配置 → 流水线配置」，共 4 个 Tab。

### 6.1 步骤管理

每个步骤 = 步骤类型 + 阶段（CI/CD）+ 超时秒数 + 参数。

| 步骤类型 | 阶段 | 作用 / 主要参数 |
|----------|------|-----------------|
| 拉取代码 `checkout` | CI | 在构建节点拉取代码；默认分支可留空（使用 Job 分支） |
| 代码构建 `build` | CI | 构建命令，如 `mvn clean package -DskipTests` |
| 镜像制作 `docker_build` | CI | 选 Dockerfile 模版（内容可微调）、Dockerfile 路径、build 额外参数、build 命令 |
| 上传镜像 `push_image` | CI | 自动 docker login 后执行 `docker push ${imageFullName}` |
| 清理缓存 `clean` | CI | 自定义清理命令，如 `mvn clean` |
| 清理空间 `clean_workspace` | CI | 清理工作目录，可选同时清理 Docker 缓存 |
| 渲染模版 `render_template` | CD | 选 Deployment 模版 + 可选 Service 模版，渲染到输出目录（默认 `manifests`） |
| 开始部署 `deploy` | CD | kubectl apply 渲染结果，可附加部署脚本 |
| 检查部署状态 `check_deploy` | CD | 等待 rollout 完成，超时默认 300 秒 |
| 回滚 `rollback` | CD | 回滚到指定 revision（留空回滚上一版本） |
| 通知 `notify` | CD | 调用通知 Webhook |

docker build 命令可用变量：`${harbor}` `${namespace}` `${serviceName}` `${tag}` `${dockerfilePath}` `${workspace}`，默认：

```bash
docker build -f ${dockerfilePath} -t ${harbor}/${namespace}/${serviceName}:${tag} ${workspace}
```

### 6.2 流水线管理

「添加模版」：选择类型 **CI / CD / CI/CD**，按顺序编排步骤，并指定执行节点。系统内置一套默认流水线可参考。

- CI：拉代码 → 构建 → 镜像制作 → 上传镜像
- CD：渲染模版 → 部署 → 检查部署状态
- CI/CD：两者串联（工作视图里最常用）

### 6.3 模版管理

| 模版 | 用途 |
|------|------|
| Dockerfile 模版 | 被「镜像制作」步骤引用，记录基础镜像 |
| Deployment 模版 | 被「渲染模版」步骤引用 |
| Service 模版 | 被「渲染模版」步骤引用，可选 ClusterIP / NodePort / LoadBalancer |

模版占位符写法为 `${变量名}`，保存时会校验变量是否可识别。可用变量：

| 变量 | 含义 |
|------|------|
| `${service}` | 服务名称 |
| `${serviceName}`（兼容 `${serviceCode}`） | 服务标识，适合镜像名/K8s 资源名 |
| `${deployment}` | K8s Deployment 名 |
| `${branch}` | 分支 |
| `${servicePort}` / `${port}` | 服务端口，默认 8080 |
| `${env}` / `${namespace}` | 环境名 / K8s 命名空间 |
| `${image}` / `${imageFullName}` | 完整镜像地址 |
| `${imageTag}` / `${commitId}` | 镜像 Tag / 提交 ID |
| `${serviceType}` | Service 类型，默认 ClusterIP |

### 6.4 视图管理

「添加视图」：填写名称、**关联环境**、**可见角色**。视图只是筛选入口，删除视图不会删除 Job。

---

## 7. 用户、角色与权限

「用户管理」页含三个子页：用户列表、角色管理、权限管理。

- **用户**：本地账号，或经 SSO 登录自动创建（「来源」列区分）；可分配多个角色、启用/禁用。
- **角色**：权限码的集合；视图可见性也按角色分配。
- 连续登录失败会被临时锁定。

常用权限码：

| 权限码 | 说明 |
|--------|------|
| `statistics:view` | 工作台 |
| `pipeline:view` / `run` / `edit` / `delete` | 查看 / 触发重试 / 编辑 / 删除流水线 Job |
| `pipeline:rollback` | 回滚到历史成功镜像 |
| `deploy:view` / `create` / `approve` / `unlock` / `delete` | 上线任务查看 / 创建 / 审批 / 解锁他人锁定 / 删除 |
| `project:manage` | 创建/删除项目，管理所有项目 |
| `service:view` / `service:manage` | 服务 |
| `env:view` / `env:manage` | 环境 |
| `cluster:view` / `cluster:manage` | 集群 |
| `node:view` / `node:manage` | 节点 |
| `approval:view` / `approval:manage` | 审批流 |
| `pipeline_config:manage` | 流水线模版/步骤/视图 |
| `credential:manage` | 凭据与组件 |
| `user:manage` / `role:manage` / `permission:manage` | 用户 / 角色 / 权限码 |
| `audit:view` | 操作审计（Pro） |
| `system:config` | 集成与通知、品牌、License |

> 菜单按权限自动显示/隐藏，后端接口同样校验，只隐藏菜单不会绕过权限。

---

## 8. 集成与通知、品牌、License

### 8.1 集成与通知（「配置 → 集成与通知」）

| Tab | 内容 |
|-----|------|
| 通知设置 | **站内信**（开源）开关；**飞书审批通知**（Pro `notify_integration`）：App ID、App Secret、API 地址、接收 ID 类型（user_id / open_id / email）、OpsFlow 访问地址（用于消息中的跳转链接） |
| SSO 登录 | 飞书 / 钉钉 / 企业微信：应用 ID、密钥、回调地址，勾选启用 |
| LDAP | 服务器地址、Base DN、管理员 DN 与密码 |

右上角铃铛为**站内信**收件箱，审批待办、审批结果等会在这里提醒。

### 8.2 操作审计（Pro `audit`）

「操作审计」按 用户 / 模块 / 结果 / 关键字（动作、路径、对象 ID、参数）查询，展示操作人、IP、耗时等。未开通时审计仍会记录，开通后可查历史。

### 8.3 品牌设置（Pro `branding`）

设置品牌名称，上传企业 Logo（PNG/JPG/WEBP/SVG，≤512KB），显示在登录页与控制台顶栏。

### 8.4 License

「配置 → License」粘贴 `.lic` 内容或选文件导入，页面显示计划、到期时间、各功能开关。

| 版本 | 能力 |
|------|------|
| Community（免费） | 项目/服务/环境/集群/节点、凭据组件、流水线全流程、非生产回滚、RBAC、工作台、站内信、Webhook |
| Pro（订阅） | + 上线任务与审批、操作审计、飞书通知、生产回滚、企业品牌 |

到期后有 **7 天宽限期**（仅告警），之后回退为 Community，数据与 Job 均保留；续费只需重新导入新 License。

---

## 9. Git Webhook 推送自动构建

1. 服务端配置环境变量 `OPSFLOW_WEBHOOK_SECRET`（即 `opsflow.webhook.secret`），未配置时 Webhook 不可用；
2. 在 Job 编辑框勾选「代码推送到该分支时自动构建」，列表中会出现 ⚡ 推送构建 标记；
3. 在 Git 仓库添加 **Push 事件** Webhook：

| 平台 | URL | 密钥字段 |
|------|-----|----------|
| GitLab | `http://<opsflow>/api/webhook/git` | Secret token |
| Gitee | 同上 | WebHook 密码 |
| GitHub | 同上 | Secret（HMAC-SHA256 签名） |

> 仅对**非生产环境、按分支构建**的 Job 生效。Job 编辑框下方会显示当前服务端的完整 Webhook 地址。

---

## 10. 常见问题排查

| 现象 | 排查方向 |
|------|----------|
| 新建 Job 时提示「暂无可用服务/环境/流水线模版」 | 分别到 服务管理 / 环境配置（需非生产且启用）/ 流水线管理 中补齐 |
| 加载分支失败 | 服务绑定的 Git 组件「测试连接」是否通过；钥匙是否有该仓库读权限；仓库路径是否正确 |
| 拉代码或构建阶段失败 | 节点「环境检查」是否全绿；查看阶段日志中的具体命令输出 |
| 镜像制作失败 | 构建节点是否安装 Docker 且当前用户可用；Dockerfile 模版内容是否正确 |
| 推送镜像失败 | Harbor 组件凭据是否有效；Harbor 中对应项目是否存在 |
| 部署失败 / 检查部署超时 | 集群「连通检测」；Namespace 是否存在；在「运行实例」中查看 Pod 状态与日志 |
| 模版保存提示变量无法识别 | 对照 6.3 节变量表，注意大小写（`${serviceName}` 而非 `${ServiceName}`） |
| 上线任务无法发布 | 审批是否全部通过（状态需为「待发布」） |
| 菜单看不到某功能 | 角色缺少对应权限码，或 License 未开通该功能 |
| Webhook 无效 | 是否配置 `OPSFLOW_WEBHOOK_SECRET`；Job 是否勾选自动构建、是否非生产且按分支 |
| 构建一直卡在运行中 | 查看服务日志 `docker compose logs -f opsflow`；重启服务会自动回收中断的流水线，之后重新触发 |

---

## 11. 附录

### 11.1 运维常用命令

```bash
docker compose ps                         # 查看状态
docker compose logs -f opsflow            # 应用日志
./scripts/deploy/compose-down.sh          # 停止（保留数据）
./scripts/deploy/compose-down.sh --volumes  # 停止并清空数据库（慎用）
bash scripts/database/run_migrations.sh   # 升级后补跑数据库迁移
```

### 11.2 相关文档

- [deployment/DOCKER.md](deployment/DOCKER.md) — Docker Compose 安装
- [deployment/DEPLOYMENT.md](deployment/DEPLOYMENT.md) — jar / Kubernetes 部署
- [../config/README.md](../config/README.md) — 配置文件说明
- [licensing/OPEN_CORE_BOUNDARY.md](licensing/OPEN_CORE_BOUNDARY.md) — 开源/商业功能边界
