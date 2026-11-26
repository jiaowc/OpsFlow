# OpsFlow 技术架构

面向贡献者：说明代码怎么组织、关键机制怎么工作。本地开发和测试见 [DEVELOPMENT.md](DEVELOPMENT.md)，部署见 [deployment/DEPLOYMENT.md](deployment/DEPLOYMENT.md)。

## 1. 模块划分

```
OpsFlow/
├── pom.xml                  # 父 POM：模块列表、依赖版本、JDK 版本约束（enforcer 限定 [17,22)）
├── core/                    # 普通 jar：全部业务代码、资源、数据库迁移和测试
│   └── src/main/
│       ├── java/com/opsflow/
│       │   ├── api/         # DTO
│       │   ├── common/      # 工具、常量、异常、加密（common/crypto）
│       │   ├── dao/         # 实体（model）和 MyBatis-Plus Mapper
│       │   ├── integration/ # 外部系统：pipeline、ssh、k8s、harbor、git、ldap、credential
│       │   ├── service/     # 业务逻辑
│       │   ├── spi/         # 扩展点和社区版默认实现
│       │   └── web/         # Controller、拦截器、安全配置
│       └── resources/
│           ├── mapper/        # MyBatis XML
│           ├── db/migration/  # Flyway 版本文件
│           └── static/        # 前端页面、脚本、样式、文案
├── app/                     # Spring Boot 可执行包：只有启动类和打包配置，产物 app/target/opsflow.jar
├── config/                  # 全部应用配置（application*.yml），不打进 jar
├── scripts/                 # deploy（部署）、database（迁移、示例数据）、docker（镜像入口）、local（本地辅助环境）、dev（前端检查）
├── docs/                    # 文档
├── Dockerfile               # 程序镜像
├── Dockerfile.allinone      # 体验镜像（内置 MySQL）
└── docker-compose*.yml      # Compose 部署
```

- **core**（artifactId `opsflow-core`）是一个普通 jar，包含所有业务代码；**app**（`opsflow-app`）只负责把它打成可执行的 Spring Boot jar。在根目录执行 `mvn clean package` 一次构建全部模块。
- **核心不依赖商业代码**：核心里没有任何企业版的类、包名或 License 校验。守护测试 `CoreDoesNotDependOnCommercialCodeTest` 会扫描核心源码，出现商业代码的引用就失败。企业版是独立的模块，只通过下面的扩展点接入，不修改核心代码。
- **配置不放 resources**：所有 `application*.yml` 都在根目录 `config/`，启动时从工作目录读取，所以必须在项目根目录（或部署目录）下启动。配置规则见 [config/README.md](../config/README.md)。
- **Git 管理**：源码、`config/` 下入库的 `application*.yml` 和 `*.example`、`scripts/`、`docs/` 提交到 Git；`target/`、`logs/`、`data/`、`config/application-local.yml` 不提交。

## 2. 扩展点（`com.opsflow.spi`）

核心在需要扩展的地方定义接口，社区版提供默认实现或者不提供实现。扩展点分两类：

- **单实例**：核心用 `@Bean` + 条件注册默认实现（`SpiDefaultsConfiguration`），扩展模块提供同类型的 Bean 时替换掉默认实现。用 `@Bean` 而不是 `@Component`，是为了让条件在所有组件注册完之后才判断，替换不受包名和扫描顺序影响。
- **多实例**：没有默认实现，核心注入的是可能为空的列表，按顺序逐个调用。

| 扩展点 | 类型 | 用途 | 社区版行为 |
|---|---|---|---|
| `EditionInfo` | 单实例 | 当前版本名和已启用的功能码，供 `GET /api/edition` | `DefaultEditionInfo`：社区版，没有功能码 |
| `ProdDeployPolicy` | 单实例 | 普通流水线能否直接部署某个环境 | `DefaultProdDeployPolicy`：非生产放行；生产要求当前用户有 `pipeline:run:prod`；Webhook 触发没有会话，直接放行 |
| `JobNotifyExtension` | 单实例 | 流水线通知的外部通道 | `DefaultJobNotifyExtension`：不推送外部通道，站内信由核心发 |
| `BuildJobLifecycleListener` | 多实例 | 构建任务结束、服务释放、回滚开始等生命周期回调 | 无实现 |
| `ResourceReferenceChecker` | 多实例 | 删除核心资源前，检查扩展自己的数据是否还引用它 | 无实现，只检查核心自己的表 |
| `StatisticsContributor` | 多实例 | 往工作台统计里追加数据 | 无实现 |
| `SystemConfigExtension` | 多实例 | 某类系统配置的写入校验、写入前转换（如加密敏感项）、读取时脱敏、写入后处理 | 无实现，原样读写 |
| `UiExtension` | 多实例 | 前端扩展：菜单项、页签、页面片段、脚本、样式、文案包、登录页脚本 | 无实现 |
| `UserAccountExtension` | 多实例 | 保存用户后处理扩展字段，返回用户详情前补充扩展字段 | 无实现 |
| `WebSecurityExtension` | 多实例 | 扩展需要免登录的路径、免来源校验的路径（只用于自带签名校验的回调） | 无实现 |

约定：

- 多实例回调里，单个实现抛异常一般只记日志，不影响核心流程。`BuildJobLifecycleListener.onRollbackStarted` 例外，它和回滚在同一个事务里，异常照常抛出。详见各接口的 Javadoc。
- `UiExtension` 声明的内容由 `GET /api/ui/extensions` 汇总给前端，`js/ui-extensions.js` 动态插入。菜单和页签的显示按页面上的 `data-feature` / `data-permission` 控制，**后端不按用户过滤**；真正的访问控制由扩展自己的业务接口负责。
- 新增或修改扩展点时，保持向后兼容：新方法写成 `default` 方法，社区版行为不变。

企业版通过这些扩展点提供更多功能，见 <https://www.opslink.cn>。

## 3. 流水线执行模型

OpsFlow 用自己的原生 Pipeline 执行，不依赖 Jenkins 等外部 CI。

### 3.1 组成

| 概念 | 说明 |
|---|---|
| 步骤定义（`pipeline-step-def`） | 一种步骤的类型、阶段、默认内容（命令、模版）和超时 |
| 流水线（`pipeline`） | 有序的步骤列表，每一步引用一个步骤定义，可以覆盖参数 |
| 流水线任务 / 构建记录（`build_job`） | 某个服务在某个环境上按某条流水线的一次执行，记录每个阶段的状态和日志 |

### 3.2 执行流程

1. **提交**（`PipelineJobRunner`）：校验权限和互斥后，在事务里建好构建记录（状态 BUILDING）和各阶段记录；事务提交后才派发执行，避免执行线程读不到记录。
2. **排队**（`PipelineJobDispatcher`）：固定数量的执行线程 + 先进先出队列。同时执行的任务数上限是 `opsflow.pipeline.max-concurrent-jobs`（默认 10），超出的任务排队，界面显示「排队中」和排队位置。步骤大量阻塞在 SSH 和 kubectl 上，所以不用 ForkJoin 公共池。
3. **执行**（`NativePipelineExecutor`）：按顺序执行已启用的步骤。每一步按类型找到 `StepExecutor`，合并步骤参数、上下文参数和前面步骤的输出，然后在单独的线程里带超时执行（步骤定义没设超时时默认 60 秒）。一步失败，整个任务失败，后面的步骤不再执行。
4. **落库**：阶段开始和结束由 `PipelineStageListener` 写库；任务结束后写终态，并通知 `BuildJobLifecycleListener`。

### 3.3 步骤执行器

每种步骤类型一个 `StepExecutor` 实现（`integration/pipeline/step/impl`）：

| 实现 | 步骤 |
|---|---|
| `CheckoutStepExecutor` | 拉代码 |
| `BuildStepExecutor` | 编译 |
| `DockerBuildStepExecutor` | 构建镜像 |
| `PushImageStepExecutor` | 推送镜像 |
| `RenderTemplateStepExecutor` | 渲染部署模版 |
| `DeployStepExecutor` | 部署 |
| `CheckDeployStepExecutor` | 检查部署状态 |
| `RollbackStepExecutor` | 回滚 |
| `CleanStepExecutor`、`CleanWorkspaceStepExecutor` | 清理 |
| `NotifyStepExecutor` | 通知（外部通道走 `JobNotifyExtension`，社区版没有外部通道） |

新增步骤类型：实现 `StepExecutor`（`supports(stepType)` + `execute(...)`），注册成 Spring Bean 即可被执行器发现。

### 3.4 构建节点和 SSH

- 命令通过 SSH 在构建节点上执行（`NodeCommandHelper`、`SshClient`，基于 JSch）。
- **节点选择**（`BuildNodeSelector`）：流水线配置了多个构建节点时，读取各节点的 `/proc/loadavg` 和 CPU 核数，选负载最低的；读取失败时按默认顺序。
- **主机指纹**：首次连接时记录节点的主机公钥（TOFU，`TofuHostKeyRepository`），之后公钥变化会拒绝连接，防中间人。
- **工作目录**：默认 `~/opsflow/<服务编码>/<环境名>/<分支>`，同一服务、环境、分支复用同一个目录；可以用步骤参数 `workspaceBase` / `workspacePath` 覆盖。

### 3.5 互斥、恢复和回滚

- **互斥**：同一个服务在同一个环境上同时只能有一个任务在运行，再提交会提示「该服务在此环境已有任务在运行」。
- **重启恢复**（`PipelineJobLiveness`）：执行状态在内存里。应用重启时，库里还是运行中的任务会被标记为失败；运行中但已不在本进程里的「僵尸」任务也会被回收。
- **回滚**（`PipelineRollbackService`）：在原任务上回滚到所选的历史镜像版本，跳过拉代码、编译、构建镜像、推送镜像这些纯 CI 步骤，只执行部署相关的步骤。

### 3.6 日志落盘

- 完整的阶段日志写到文件（`StageLogStore`），目录是 `opsflow.pipeline.log-dir`（默认 `./data/pipeline-logs`）；数据库只保存日志文件的相对路径和一段预览。
- 读取时（`PipelineStageLogService`）优先读文件，按 offset 分段返回，前端轮询追加；文件不存在时回退到库里的摘要。
- 保留策略（`opsflow.retention`）每天清理：超期的阶段日志只保留末尾摘要，超期的构建记录删除，但每个任务至少保留最近若干次。

## 4. 数据库迁移

- 用 Flyway 管理表结构和默认数据，版本文件在 `core/src/main/resources/db/migration`：`V1` 是基线表结构，`V2` 是默认数据（角色权限、初始管理员、步骤定义、默认模版、通用流水线），之后每个变更一个新版本。
- 应用启动时自动执行未执行的版本（`OPSFLOW_DB_MIGRATE=true`，默认）；设为 `false` 时启动只检查，有未执行的版本就拒绝启动，需要先执行 `scripts/database/migrate.sh`。
- **库结构只增不删**：新版本只加表、加列、加数据，不删表、不删列、不改列的含义。这样旧版本程序也能跑在新库上，回退时只换程序、不改库。确实需要清理的结构，放到单独评审的迁移里，并在升级说明里写清楚。
- **已发布的版本文件不能修改**：Flyway 会校验已执行版本的校验和，改了会导致已有环境启动失败。要改就加一个新版本。
- 新增版本时同步更新 [UPGRADE.md](UPGRADE.md)，写清楚新增了什么、回退时怎么处理。
- 引入 Flyway 之前的旧库，先用 `scripts/database/legacy/` 下的脚本追平，见 [scripts/database/README.md](../scripts/database/README.md)。

## 5. 凭据加密与主密钥

- 钥匙串、组件认证信息、节点 SSH 密码和私钥、两步验证密钥等敏感字段，用 AES-256-GCM 加密后存库（`common/crypto/SecretCipher`）。实体字段通过 MyBatis 的 `EncryptedStringTypeHandler` 透明加解密。
- 密文格式：`enc:v1:<Base64(iv[12] | 密文 | tag[16])>`。没有这个前缀的值视为历史明文原样返回，所以旧数据可以平滑升级，启动时会把历史明文加密回写。
- **主密钥**按顺序取得：
  1. 环境变量或系统属性 `OPSFLOW_SECRET_KEY`：Base64 编码的 32 字节；
  2. 密钥文件 `OPSFLOW_SECRET_KEY_FILE`（默认 `./data/secret.key`）：不存在时首次启动自动生成，权限 600。
- 主密钥丢失或被换掉后，已加密的数据都无法解密，只能重新录入。多实例部署必须用同一个主密钥。
- 用户登录密码不加密而是做哈希：新密码统一用 BCrypt（`PasswordHasher`），兼容历史的无盐 MD5，登录成功后升级为 BCrypt。
- 接口返回敏感字段时用掩码 `******`；修改时传 `******` 或留空表示不修改。

## 6. 权限模型

权限分两层，两层都满足才能操作。

### 6.1 功能权限：RBAC + 权限码

- 用户 → 角色 → 权限，权限用权限码表示，如 `pipeline:run`、`service:manage`、`system:config`。
- 接口上用 `@RequiresPermission({"a", "b"})` 声明，拥有其中任意一个即可；权限码 `*` 表示全部权限。由 `AuthInterceptor` 统一校验登录和权限。
- 默认角色和权限在 `V2__default_data.sql` 里，之后新增的权限码各自在迁移里加（如 `V6` 的 `pipeline:run:prod`：普通流水线部署生产环境）。
- 前端用 `data-permission` 控制按钮和菜单的显示，**只是界面提示**；后端接口的 `@RequiresPermission` 才是真正的校验。
- `AdminGuard` 保证系统始终至少有一个可用的管理员：内置的管理员角色不能删除或禁用，最后一个启用的管理员账号不能被删除、禁用或摘掉管理员角色。

### 6.2 数据权限：项目成员

- 有 `project:manage` 的用户是平台管理员，可以看到和操作所有项目。
- 其他用户只能看到自己是成员的项目，以及项目下的服务和流水线。项目内的角色：`VIEWER` 只读，`DEVELOPER` 可以构建、部署、维护服务，`ADMIN` 另外可以管理成员和项目信息（`ProjectAccessService`）。
- 定时任务、Webhook 内部触发等非 Web 请求不受项目成员限制。

### 6.3 其他安全机制

- **登录会话**：基于 Cookie 的会话，`SameSite=Lax`、`HttpOnly`。
- **CSRF**：写接口校验 `Origin` / `Referer` 与当前站点一致（`CsrfOriginInterceptor`），反向代理场景用 `OPSFLOW_ALLOWED_ORIGINS` 补充允许的来源。
- **两步验证**：TOTP 动态码 + 恢复码，策略存在系统配置 `security.twoFactorPolicy`（`off` / `optional` / `required`），只对账号密码登录和 LDAP 登录生效。

## 7. 前端

- 纯静态页面，没有构建步骤：`core/src/main/resources/static` 下的 HTML、JS、CSS 由 Spring Boot 直接提供，改完刷新页面即可。
- 核心页面路由表在 `js/routes.js`，菜单和区块在 `dashboard.html`，后端转发在 `DashboardController`，三者由 `scripts/dev/check-routes.js` 核对一致。
- 中英文文案在 `js/i18n/`，由 `scripts/dev/check-i18n.js` 检查 key 是否一一对应。
- 拼进 HTML 的变量必须转义（`escapeHtml`），由 `scripts/dev/check-html-escape.js` 检查。
- 必填项的标签带红色星号 `${REQUIRED_MARK}`，输入框带 `required`，星号、`required` 和提交时的校验三者一致，由 `scripts/dev/check-required-mark.js` 检查。后端按同样的必填规则校验，空值返回 400。
