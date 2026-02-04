# OpsFlow 项目目录结构

## 目录结构说明

```
OpsFlow/
├── src/                          # 源代码目录
│   ├── main/
│   │   ├── java/                 # Java 源代码
│   │   │   └── com/opsflow/
│   │   │       ├── api/          # API 模块（DTO）
│   │   │       ├── common/       # 公共模块（工具类、常量）
│   │   │       ├── dao/          # 数据访问层（Mapper、Model）
│   │   │       ├── integration/  # 集成模块（Jenkins/K8s/Harbor客户端）
│   │   │       ├── service/      # 业务逻辑层
│   │   │       ├── web/          # Web层（Controller）
│   │   │       └── Application.java  # 启动类
│   │   └── resources/            # 资源文件
│   │       ├── static/           # 静态资源（HTML、CSS、JS）
│   │       └── mapper/           # MyBatis Mapper XML
│   └── test/                     # 测试代码
│       └── java/
│
├── config/                       # 配置文件目录（外部配置，不提交到Git）
│   ├── application.yml           # 主配置文件（生产环境）
│   ├── application-dev.yml       # 开发环境配置
│   ├── application-prod.yml      # 生产环境配置
│   └── application.yml.example  # 配置模板（提交到Git）
│
├── scripts/                      # 脚本目录
│   ├── deploy/                   # 部署脚本
│   │   ├── start.sh              # 启动脚本
│   │   ├── stop.sh               # 停止脚本
│   │   └── restart.sh            # 重启脚本
│   ├── database/                 # 数据库脚本
│   │   ├── init.sql              # 初始化脚本
│   │   └── migrate/              # 迁移脚本
│   └── tools/                    # 工具脚本
│
├── docs/                         # 文档目录
│   ├── api/                      # API 文档
│   ├── deployment/               # 部署文档
│   └── development/              # 开发文档
│
├── logs/                         # 日志目录（不提交到Git）
│   └── app.log                   # 应用日志
│
├── data/                         # 数据目录（不提交到Git）
│   └── uploads/                  # 上传文件
│
├── target/                       # 编译输出目录（不提交到Git）
│   └── admin-1.0.0.jar          # 打包后的JAR文件
│
├── .gitignore                    # Git 忽略文件
├── pom.xml                       # Maven 主POM
├── README.md                     # 项目说明
└── PROJECT_STRUCTURE.md          # 项目结构说明（本文件）
```

## 目录说明

### 模块说明（Maven 多模块项目）

- **api/**: API 模块，定义数据传输对象（DTO）
- **common/**: 公共模块，包含工具类、常量定义
- **dao/**: 数据访问层，包含 MyBatis Mapper 和实体类（Model）
- **integration/**: 集成模块，包含第三方服务客户端（Jenkins、K8s、Harbor）
- **service/**: 业务逻辑层，实现业务逻辑
- **web/**: Web 层，包含 REST Controller
- **admin/**: 启动模块，包含主应用类和静态资源

### config/ - 配置文件目录
- **application.yml**: 主配置文件（生产环境，不提交到Git）
- **application-dev.yml**: 开发环境配置
- **application-prod.yml**: 生产环境配置
- **application.yml.example**: 配置模板（提交到Git，供参考）

### scripts/ - 脚本目录
- **deploy/**: 部署相关脚本（启动、停止、重启）
- **database/**: 数据库相关脚本（初始化、迁移）
- **tools/**: 工具脚本

### docs/ - 文档目录
- **api/**: API 接口文档
- **deployment/**: 部署文档
- **development/**: 开发文档

### logs/ - 日志目录
- 应用运行日志（不提交到Git）

### data/ - 数据目录
- 上传文件、临时数据等（不提交到Git）

## 配置文件管理

### 开发环境
使用项目内的默认配置：`src/main/resources/application.yml`

### 生产环境
1. 复制配置模板：
   ```bash
   cp config/application.yml.example config/application.yml
   ```

2. 修改配置：
   ```bash
   vim config/application.yml
   ```

3. 启动时指定配置：
   ```bash
   java -jar target/admin-1.0.0.jar --spring.config.location=file:./config/application.yml
   ```

## Git 管理

### 提交到 Git
- ✅ 源代码（src/）
- ✅ 配置模板（config/*.example）
- ✅ 脚本（scripts/）
- ✅ 文档（docs/）
- ✅ 数据库脚本（scripts/database/）

### 不提交到 Git（.gitignore）
- ❌ 实际配置文件（config/application.yml）
- ❌ 编译输出（target/）
- ❌ 日志文件（logs/）
- ❌ 数据文件（data/）
- ❌ IDE 配置（.idea/, .vscode/）

## 迁移建议

如果需要迁移现有项目到新结构：

1. 创建新目录结构
2. 移动文件到对应目录
3. 更新脚本中的路径引用
4. 更新文档说明
