# 配置说明

## 原则

- **配置只放 `config/` 目录**：jar 里不包含 `application*.yml`，所有应用配置都在这里，一眼能看全。
- **一个 jar 适用所有环境**：只用 `mvn clean package` 打包，启动时用 `SPRING_PROFILES_ACTIVE` 选择环境。
- **环境文件只写差异**：公共配置只维护一份，dev/test/prod 文件里只有与公共配置不同的几行。
- **敏感信息不进仓库**：数据库密码等通过环境变量或本机私有文件注入。

## 文件位置

```
config/
├── application.yml                # 入库：公共配置（所有环境共用）
├── application-dev.yml            # 入库：dev 差异（数据库地址、DEBUG 日志）
├── application-test.yml           # 入库：test 差异
├── application-prod.yml           # 入库：prod 差异（必须显式提供 DB_URL）
├── application-local.yml          # 不入库：本机私有配置（DB_PASSWORD 等）
└── application-local.yml.example  # 入库：本机私有配置模板
```

## 数据库连接

所有环境统一用三个变量注入，配置文件里只有占位符和默认值：

| 变量 | 说明 |
|------|------|
| `DB_URL` | JDBC 地址，prod 必填；dev/test 的默认值指向占位主机名 `mysql-dev` / `mysql-test`（仓库里不写真实地址），请用 `DB_URL` 覆盖为实际地址，或让该主机名能解析 |
| `DB_USERNAME` | 用户名，默认 `root`（test/prod 默认 `opsflow`） |
| `DB_PASSWORD` | 密码，必填 |

取值优先级：环境变量 > `config/application-local.yml` > 配置文件中的默认值。

数据库脚本（`scripts/database/migrate.sh` 等）与应用使用同一套规则解析连接，见 `scripts/database/db_env.sh`。

| 变量 | 说明 |
| --- | --- |
| `OPSFLOW_DB_MIGRATE` | 启动时是否自动执行数据库迁移，默认 `true`。设为 `false` 时启动不改库，有未执行的迁移就拒绝启动，需先执行 `scripts/database/migrate.sh` |

## 快速开始

```bash
cp config/application-local.yml.example config/application-local.yml
vim config/application-local.yml        # 填写 DB_PASSWORD

mvn clean package -DskipTests
java -jar app/target/opsflow.jar                                 # 默认环境（本机库）
SPRING_PROFILES_ACTIVE=dev java -jar app/target/opsflow.jar      # dev 环境
```

**必须在项目根目录（或部署目录）下启动**：Spring Boot 从当前工作目录的 `config/` 读取配置。
IDE 中运行 `OpsFlowApplication` 时，工作目录也要设为项目根目录（IDEA 默认即是）。

## 服务器 / 容器 / K8s

- **服务器**：部署目录放 `opsflow.jar` 和整个 `config/` 目录，再放 `config/application-local.yml` 或在 systemd 中设置 `Environment=DB_PASSWORD=...`。
- **Docker**：镜像构建时会把 `config/` 中入库的配置拷到 `/app/config/`（不含 `application-local.yml`）。
  `./scripts/deploy/start.sh --docker` 还会把项目 `config/` 挂载到 `/app/config`，并透传宿主机上已设置的 `DB_*` 环境变量。容器内访问宿主机 MySQL 需用 `host.docker.internal`。
- **K8s**：`SPRING_PROFILES_ACTIVE` 放 ConfigMap，`DB_PASSWORD` 放 Secret，均以环境变量注入。

## 凭据加密主密钥

钥匙串、组件认证信息、节点 SSH 密码/私钥以 AES-256-GCM 加密存库，主密钥按以下顺序取得：

1. 环境变量 `OPSFLOW_SECRET_KEY`：Base64 编码的 32 字节，可用 `openssl rand -base64 32` 生成；
2. 密钥文件 `OPSFLOW_SECRET_KEY_FILE`（默认 `./data/secret.key`，相对启动目录）：不存在时首次启动自动生成，权限 600。

- 多实例或 K8s 部署请显式设置 `OPSFLOW_SECRET_KEY`（放 Secret），所有实例必须一致；
- 主密钥丢失或更换后，已保存的凭据无法解密（页面上显示为未配置），只能重新录入，请与数据库一起备份；
- 从引入 Flyway 之前的很早版本升级：若启动时提示「数据库结构落后于迁移基线」，先执行一次 `bash scripts/database/legacy/run_migrations.sh`（补上 `build_node.host_key` 等列），再启动应用；启动时会自动把历史明文凭据加密回写。

## 覆盖其它配置项

任何配置项都可以写在 `config/application-local.yml` 中，它的优先级高于 `application.yml` 和当前激活的 `application-<env>.yml`
（未激活 profile 时由 `application.yml` 引入，激活了 profile 时由对应的 `application-<env>.yml` 引入）。

加载优先级（低 → 高）：`application.yml` → `application-<env>.yml` → `application-local.yml` → 环境变量 → 命令行参数。
