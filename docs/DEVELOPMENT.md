# 开发指南

面向贡献者：搭本地环境、构建、测试、提交。代码结构和关键机制见 [ARCHITECTURE.md](ARCHITECTURE.md)。

## 1. 本地环境

| 依赖 | 要求 |
|---|---|
| JDK | **17**。父 POM 的 maven-enforcer-plugin 限定 `[17,22)`，JDK 22 及以上 Lombok 会失效 |
| Maven | 3.6+ |
| MySQL | 8.0，字符集 `utf8mb4` |
| Node.js | 只用来跑 `scripts/dev/check-*.js`，不参与构建 |
| Docker | 可选：跑 Compose、镜像和 `scripts/local` 下的辅助环境时需要 |

`mvn -v` 显示的不是 JDK 17 时（如系统默认是 JDK 8，或装的是更新的 JDK），先指定 `JAVA_HOME`：

```bash
# macOS：brew install openjdk@17 后用下面的路径；也可以用 /usr/libexec/java_home -v 17 查
# Linux：填 JDK 17 的安装目录，如 /usr/lib/jvm/java-17-openjdk
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
mvn -v    # 确认 Java version: 17.x
```

### 数据库

建一个空库，表结构和默认数据由应用启动时自动建立（Flyway）：

```bash
mysql -u root -p -e "CREATE DATABASE IF NOT EXISTS opsflow DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"
```

想要一套现成的集群、环境、节点、服务示例联调时（可选）：

```bash
mysql -u root -p opsflow < scripts/database/sample/sample-data.sql
```

示例数据里的节点密码和钥匙串配置是空的，编辑这些记录时要先补上才能保存。详见 [scripts/database/README.md](../scripts/database/README.md)。

### 配置

```bash
cp config/application-local.yml.example config/application-local.yml
# 编辑 config/application-local.yml，填 DB_PASSWORD（本机私有，不入库）
```

默认连 `127.0.0.1:3306/opsflow`，账号 `root`；要连别的库就在同一个文件里填 `DB_URL`、`DB_USERNAME`。配置规则和优先级见 [config/README.md](../config/README.md)。

### 本地辅助环境（`scripts/local`）

跑通完整流水线需要构建节点、镜像仓库和 K8s 集群。`scripts/local` 下有用 Docker 模拟这些依赖的脚本，只用于本机调试：

| 目录 | 内容 |
|---|---|
| [build-nodes](../scripts/local/build-nodes/README.md) | 两个容器模拟构建节点，经 SSH 接入，带 JDK、Maven、Node、Git、Docker CLI |
| [harbor-nexus](../scripts/local/harbor-nexus/README.md) | 本机 Harbor 和 Nexus，用于镜像推送和组件连通性测试 |
| [k8s](../scripts/local/k8s/README.md) | 可一键销毁的 kind 集群和一个带 kubectl 的部署节点，不写入本机 `~/.kube` |

每个目录都有 `start.sh` / `stop.sh` 和把它接入 OpsFlow 的 `configure-opsflow.sh`，用法见各自的 README。这些环境里的账号密码是公开的调试口令，不要用在其他地方。

## 2. 构建和运行

```bash
mvn clean package -DskipTests                # 产物：app/target/opsflow.jar
java -jar app/target/opsflow.jar     # 在项目根目录执行，从 ./config 读配置
```

- **必须在项目根目录启动**：配置在 `config/`，不在 jar 里。IDE 里运行启动类时，工作目录也要设成项目根目录（IntelliJ IDEA 默认就是）。
- 浏览器打开 <http://localhost:8080>，初始账号 `admin` / `admin`，首次登录会被要求修改密码。
- 也可以用脚本：`./scripts/deploy/restart.sh`（停止 → 编译 → 后台启动，日志在 `logs/app.log`），见 [scripts/deploy/README.md](../scripts/deploy/README.md)。

### 前端没有构建步骤

页面、脚本、样式都在 `core/src/main/resources/static`，由 Spring Boot 直接提供。不需要 npm install，也不需要打包。从 IDE 运行时改完刷新浏览器即可；用 jar 运行时要重新打包。

## 3. 测试

```bash
mvn -B verify                                          # 全部测试，和 CI 一致
mvn -B test -pl core -Dtest=SomeServiceTest    # 只跑一个测试类
```

- 测试在 `core/src/test`，按 `common` / `config` / `integration` / `service` / `spi` / `web` 分目录，不依赖外部 MySQL。
- `spi/CoreDoesNotDependOnCommercialCodeTest` 是守护测试：核心源码里出现商业代码的引用就失败。新增代码不要引用核心以外的模块。

CI（`.github/workflows/ci.yml`）在每次 push 和 PR 上执行：

| 任务 | 内容 |
|---|---|
| lint | `shellcheck`（`scripts/` 下的 `.sh`，error 级别为门禁）、`hadolint`（`Dockerfile`） |
| test | `mvn -B -ntp verify`（JDK 17） |
| image | 构建程序镜像和体验镜像（只验证能构建，不推送）；打 `v*` tag 时构建双架构并发布 |

## 4. 前端检查脚本（`scripts/dev`）

CI 不跑这几个脚本，**改前端后提交前自己跑一遍**。都是只读检查，不改文件，有问题时退出码为 1：

```bash
node scripts/dev/check-i18n.js
node scripts/dev/check-html-escape.js
node scripts/dev/check-routes.js
node scripts/dev/check-required-mark.js
```

| 脚本 | 检查什么 |
|---|---|
| `check-i18n.js` | 中英文文案的 key 一一对应；列出各文件剩余的硬编码中文行数（`-v` 打印具体行，`--strict <文件>` 要求指定文件不能有硬编码中文） |
| `check-html-escape.js` | 拼进 HTML 的表达式是否都转义了：模板字面量里的 `${...}`、和 HTML 字符串用 `+` 拼接的表达式。`escapeHtml(...)`、`encodeURIComponent(...)`、数值、字面量等视为安全 |
| `check-routes.js` | 以 `js/routes.js` 为准，核对路由表本身、`dashboard.html` 的菜单和区块、标题文案、loader 函数、`DashboardController` 的转发路径 |
| `check-required-mark.js` | 必填项的标签带 `${REQUIRED_MARK}`，输入框带 `required`（或 `aria-required="true"`），星号、`required` 和提交时的校验一致 |

`-v` 都可以打印更多细节。`ext-modules.js` 是这几个脚本共用的辅助模块，用来找带扩展页面的模块，社区版里不用管。

写前端时的约定：

- 界面文字都放到 `js/i18n/` 的中英文文案里，不要在页面里写死中文。
- 拼进 HTML 的变量用 `escapeHtml(...)` 转义；确实已经是安全 HTML 的变量，变量名以 `Html` 结尾。
- 必填项的标签写成 `<label>文字${REQUIRED_MARK}</label>`；后端也要按同样的规则校验（`RequiredFields`），空值返回 400「X不能为空」。
- 颜色用 `css/` 里已有的变量，不要写死色值。

改了 `scripts/` 下的 shell 脚本，提交前跑一下 `shellcheck`；改了 `Dockerfile`，跑一下 `hadolint`。

## 5. 数据库变更

- 加一个新的 Flyway 版本文件：`core/src/main/resources/db/migration/V<N>__<说明>.sql`，`N` 接着现有的最大版本号。
- **只增不删**：加表、加列、加数据，不删表、不删列、不改列的含义；已发布的版本文件不能修改。原因见 [ARCHITECTURE.md「数据库迁移」](ARCHITECTURE.md#4-数据库迁移)。
- 在 [UPGRADE.md](UPGRADE.md) 的「未发布」段落写清楚这次的库变化和回退方式。

## 6. 提交规范

- 提交信息格式：`<类型>(<范围>): <中文说明>`，范围可以省略。常用类型：

  | 类型 | 用途 |
  |---|---|
  | `feat` | 新功能 |
  | `fix` | 修复问题 |
  | `docs` | 只改文档 |
  | `style` | 不影响行为的样式、格式调整 |
  | `refactor` | 不改行为的重构 |
  | `perf` | 性能优化 |
  | `test` | 只改测试 |
  | `build` | 构建、镜像、CI |
  | `chore` | 其他杂项（脚本、依赖等） |

  范围按改动的部分写，如 `ui`（前端）。例：`feat(ui): 节点列表加重置指纹按钮`、`fix: 节点修改不带 host 时不再清掉主机指纹`。
- 一个提交只做一件事；说明写清楚改了什么、为什么改。
- 影响接口、配置或数据库的改动，同时更新 [UPGRADE.md](UPGRADE.md) 和对应的使用文档。
- 不要提交 `config/application-local.yml`、`.env`、`data/`（含 `secret.key`）、`logs/`，也不要在代码、日志、提交信息里写密码、Token、密钥。
- 提交前确认：`mvn -B verify` 通过；改了前端的，四个 `check-*.js` 通过。
