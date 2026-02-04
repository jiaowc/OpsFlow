# OpsFlow - DevOps构建发布平台

## 项目简介

OpsFlow 是一个基于 Spring Boot 的 DevOps 构建发布平台，支持非生产环境的一键构建发布功能。

## 功能特性

- ✅ 非生产环境一键构建发布（dev/test/demo）
- ✅ 支持手动选择分支和环境
- ✅ 支持随机选择或手动选择Jenkins构建节点
- ✅ 集成Jenkins进行代码构建
- ✅ 自动构建Docker镜像并推送到Harbor
- ✅ 自动部署到K8s集群
- ✅ Jenkins视图管理（作业列表、Pipeline历史、阶段日志）
- ✅ 组件管理（Jenkins、K8s、Harbor配置）
- 🔄 生产环境上线任务和审批流程（待实现）

## 技术栈

- **后端框架**: Spring Boot 2.7.14
- **数据库**: MySQL + MyBatis Plus
- **构建工具**: Maven
- **CI/CD**: Jenkins
- **容器化**: Docker + Kubernetes
- **镜像仓库**: Harbor

## 项目结构

```
OpsFlow/
├── api/              # API模块（DTO类）
├── common/           # 公共模块（工具类、常量）
├── dao/              # 数据访问层（Mapper、Model）
├── integration/      # 集成模块（Jenkins/K8s/Harbor客户端）
├── service/          # 业务逻辑层
├── web/              # Web层（Controller）
├── admin/            # 启动模块（主应用）
├── config/           # 配置文件目录（外部配置）
├── scripts/          # 脚本目录（部署、数据库、工具）
├── docs/             # 文档目录
├── logs/             # 日志目录
└── data/             # 数据目录
```

详细结构说明请查看 [PROJECT_STRUCTURE.md](PROJECT_STRUCTURE.md)

## 快速开始

### 1. 环境要求

- JDK 1.8+
- Maven 3.6+
- MySQL 5.7+
- Jenkins（已配置）
- Kubernetes集群（已配置）

### 2. 数据库初始化

```bash
# 创建数据库
mysql -u root -p
CREATE DATABASE opsflow DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

# 导入表结构
mysql -u root -p opsflow < scripts/database/schema.sql
```

### 3. 配置文件

#### 开发环境
使用项目内默认配置：`admin/src/main/resources/application.yml`

#### 生产环境
1. 复制配置模板：
   ```bash
   cp config/application.yml.example config/application.yml
   ```

2. 修改配置：
   ```bash
   vim config/application.yml
   # 修改数据库连接、密码等配置
```

### 4. 编译打包

```bash
mvn clean package -DskipTests
```

### 5. 运行

#### 使用脚本（推荐）
```bash
# 启动应用
./scripts/deploy/start.sh

# 停止应用
./scripts/deploy/stop.sh

# 重启应用
./scripts/deploy/restart.sh
```

#### 手动启动
```bash
# 使用外部配置
java -jar target/admin-1.0.0.jar --spring.config.location=file:./config/application.yml

# 或使用默认配置
java -jar target/admin-1.0.0.jar
```

## 配置文件管理

### 配置优先级

Spring Boot 配置加载优先级（从高到低）：
1. 命令行参数 `--spring.config.location`
2. 外部配置文件 `./config/application.yml`
3. 项目内配置文件 `classpath:application.yml`

### 安全建议

- ⚠️ **不要将包含密码的 `config/application.yml` 提交到 Git**
- ✅ 将 `config/application.yml` 添加到 `.gitignore`
- ✅ 使用 `config/application.yml.example` 作为模板
- ✅ 生产环境建议使用环境变量或配置中心管理敏感信息

## API接口

### 非生产环境构建发布

#### 1. 启动构建任务

```http
POST /api/build/start
Content-Type: application/json

{
  "serviceId": 1,
  "envId": 1,
  "branch": "develop",
  "jenkinsNode": "node-1",  // 可选，不传则随机选择
  "autoDeploy": true
}
```

#### 2. 查询构建状态

```http
GET /api/build/{jobId}
```

#### 3. 获取构建任务列表

```http
GET /api/build/jobs?envId=3
```

#### 4. 获取服务的分支列表

```http
GET /api/build/service/{serviceId}/branches
```

#### 5. 获取环境列表

```http
GET /api/build/envs
```

#### 6. 获取Jenkins节点列表

```http
GET /api/build/nodes?random=true
```

### Jenkins视图

#### 1. 获取Jenkins作业列表

```http
GET /api/jenkins/jobs?includeHealth=false
```

#### 2. 获取Pipeline构建历史

```http
GET /api/jenkins/pipeline/{jobName}?page=1&pageSize=10
```

#### 3. 获取构建阶段信息

```http
GET /api/jenkins/pipeline/{jobName}/build/{buildNumber}/stages
```

#### 4. 获取阶段日志

```http
GET /api/jenkins/pipeline/{jobName}/build/{buildNumber}/stage/{stageId}/log?stageName={stageName}
```

#### 5. 触发构建

```http
POST /api/jenkins/build/{jobName}
```

### 组件管理

#### 1. 获取组件列表

```http
GET /api/component/list
```

#### 2. 测试组件连接

```http
POST /api/component/{id}/test
```

## Jenkins配置

需要在Jenkins中创建一个Pipeline Job，名称为 `build-and-deploy`（可在环境配置中自定义）。

Pipeline脚本示例请参考项目文档中的 `Jenkinsfile.example`。

## 开发计划

- [ ] 生产环境上线任务功能
- [ ] 审批流程功能
- [ ] LDAP/SSO统一登录
- [ ] 构建任务历史查询
- [ ] 发布回滚功能

## 许可证

MIT License
