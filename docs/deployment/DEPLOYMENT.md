# OpsFlow 部署文档

部署方式优先级：

1. **Docker Compose 一键安装（推荐）** → [DOCKER.md](./DOCKER.md)
2. **传统 jar**（本文下方）
3. **Kubernetes**（同一镜像编排，清单后续补充）

## 方式 A：Docker Compose（推荐）

```bash
cp .env.example .env
./scripts/deploy/compose-up.sh
# 浏览器 http://localhost:8080 ，初始账号 admin / admin（首次登录会被要求改密）
```

完整说明（外部库、备份、升级、排障）：[DOCKER.md](./DOCKER.md)。

---

## 方式 B：传统 jar 部署

### 1. 环境要求

- JDK 17（构建和运行都需要）
- Maven 3.6+
- MySQL 5.7+
- 构建节点（SSH，跑流水线时需要）
- Kubernetes 集群（部署到 K8s 时需要）

### 2. 数据库准备

只需建一个空库，表结构和默认数据（角色权限、初始管理员、步骤定义、默认模版、通用流水线）由应用启动时自动建立：

```bash
mysql -u root -p -e "CREATE DATABASE IF NOT EXISTS opsflow DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"
```

生产环境如果不希望应用启动时改库，设置 `OPSFLOW_DB_MIGRATE=false`，并在每次升级启动前手动执行（需先完成第 3 步的配置文件，脚本从中读取数据库连接）：

```bash
bash scripts/database/migrate.sh --info --profile=prod   # 查看待执行的版本
bash scripts/database/migrate.sh --profile=prod          # 执行
```

初始账号为 `admin` / `admin`，首次登录会被要求修改密码。

### 3. 配置文件准备

```bash
# 部署目录下创建本机私有配置（不入库），填写 DB_PASSWORD；prod 还需 DB_URL
cp config/application-local.yml.example config/application-local.yml
vim config/application-local.yml
```

详见 [config/README.md](../../config/README.md)。

### 4. 使用部署脚本

```bash
# 编译并启动
./scripts/deploy/restart.sh

# 指定环境编译并启动（示例：dev）
SPRING_PROFILES_ACTIVE=dev ./scripts/deploy/restart.sh

# 或分步执行
./scripts/deploy/start.sh   # 启动
./scripts/deploy/stop.sh    # 停止
```

### 5. 手动部署

```bash
# 1. 编译打包（所有环境共用同一个 jar）
mvn clean package -DskipTests

# 2. 在部署目录下启动（默认环境）
java -jar target/opsflow.jar

# 3. 指定环境启动（示例：dev）
SPRING_PROFILES_ACTIVE=dev java -jar target/opsflow.jar
```

## 配置说明

### 配置文件位置

所有应用配置都在部署目录（工作目录）下的 `config/`，jar 内不含配置文件，部署时需连同 `config/` 一起拷贝：

- 公共配置：`config/application.yml`
- 环境差异：`config/application-dev.yml`、`application-test.yml`、`application-prod.yml`
- 本机私有配置：`config/application-local.yml`（不入库）
- 通过 `SPRING_PROFILES_ACTIVE` 选择环境；数据库连接通过 `DB_URL` / `DB_USERNAME` / `DB_PASSWORD` 注入

## 日志管理

### 日志位置

- 应用日志: `logs/app.log`
- 查看日志: `tail -f logs/app.log`

### 日志轮转

建议使用 `logrotate` 或类似工具进行日志轮转：

```bash
# /etc/logrotate.d/opsflow
/path/to/OpsFlow/logs/*.log {
    daily
    rotate 7
    compress
    delaycompress
    missingok
    notifempty
    create 0644 user group
}
```

## 服务管理

### 使用 systemd（Linux）

创建服务文件 `/etc/systemd/system/opsflow.service`:

```ini
[Unit]
Description=OpsFlow DevOps Platform
After=network.target mysql.service

[Service]
Type=simple
User=opsflow
WorkingDirectory=/opt/opsflow
Environment=SPRING_PROFILES_ACTIVE=prod
# DB_URL / DB_PASSWORD 可写在 /opt/opsflow/config/application-local.yml，或用 EnvironmentFile 注入
EnvironmentFile=-/opt/opsflow/opsflow.env
ExecStart=/usr/bin/java -jar /opt/opsflow/target/opsflow.jar
Restart=always
RestartSec=10

[Install]
WantedBy=multi-user.target
```

启动服务：

```bash
sudo systemctl daemon-reload
sudo systemctl enable opsflow
sudo systemctl start opsflow
sudo systemctl status opsflow
```

## 健康检查

### 检查应用状态

```bash
# 检查端口
lsof -i :8080

# 检查进程
ps aux | grep opsflow.jar

# 检查API
curl http://localhost:8080/api/build/envs
```

## 故障排查

### 常见问题

1. **端口被占用**
   ```bash
   lsof -i :8080
   kill -9 <PID>
   ```

2. **数据库连接失败**
   - 检查数据库服务是否运行
   - 检查配置文件中的连接信息
   - 检查防火墙规则

3. **JAR文件不存在**
   ```bash
   mvn clean package -DskipTests
   ```

4. **日志文件权限问题**
   ```bash
   chmod 755 logs/
   chown user:group logs/
   ```

## 备份与恢复

### 数据库备份

```bash
# 备份
mysqldump -u root -p opsflow > backup_$(date +%Y%m%d).sql

# 恢复
mysql -u root -p opsflow < backup_20240109.sql
```

### 配置文件备份

```bash
# 备份本机私有配置
cp config/application-local.yml config/application-local.yml.backup
```

## 升级指南

1. 停止应用
2. 备份数据库和配置文件
3. 更新代码
4. 执行数据库迁移脚本（如有）
5. 重新编译打包
6. 启动应用
