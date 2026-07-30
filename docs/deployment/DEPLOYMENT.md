# OpsFlow 部署文档

## 部署前准备

### 1. 环境要求

- JDK 1.8+
- Maven 3.6+
- MySQL 5.7+
- 构建节点（SSH）
- Kubernetes集群（已配置）

### 2. 数据库准备

```bash
# 创建数据库
mysql -u root -p
CREATE DATABASE opsflow DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

# 导入表结构
mysql -u root -p opsflow < scripts/database/schema.sql
```

### 3. 配置文件准备

```bash
# 默认配置：config/application.yml
# 按环境覆盖，例如：
vim config/application-dev.yml
```

## 部署步骤

### 方式一：使用部署脚本（推荐）

```bash
# 编译并启动
./scripts/deploy/restart.sh

# 按环境编译并启动（示例：dev）
SPRING_PROFILES_ACTIVE=dev ./scripts/deploy/restart.sh

# 或分步执行
./scripts/deploy/start.sh   # 启动
./scripts/deploy/stop.sh    # 停止
```

### 方式二：手动部署

```bash
# 1. 编译打包
mvn clean package -DskipTests

# 或按环境打包（示例：dev）
mvn clean package -Pdev -DskipTests

# 2. 启动应用（默认使用 application.yml）
java -jar target/opsflow.jar

# 3. 启动应用（示例：dev）
SPRING_PROFILES_ACTIVE=dev java -jar target/opsflow.jar --spring.config.additional-location=file:./config/
```

## 配置说明

### 配置文件位置

- 默认配置：`config/application.yml`
- 环境配置：`config/application-dev.yml`、`config/application-test.yml`、`config/application-prod.yml`
- 不传参数默认使用 `application.yml`
- 通过 `SPRING_PROFILES_ACTIVE` 选择环境覆盖配置

### 配置优先级

1. `SPRING_PROFILES_ACTIVE` / `--spring.profiles.active`
2. 外部配置目录 `./config/`
3. `application.yml`
4. `application-<profile>.yml`

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
ExecStart=/usr/bin/java -jar /opt/opsflow/target/opsflow.jar --spring.config.additional-location=file:/opt/opsflow/config/
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
# 备份配置
cp config/application.yml config/application.yml.backup
```

## 升级指南

1. 停止应用
2. 备份数据库和配置文件
3. 更新代码
4. 执行数据库迁移脚本（如有）
5. 重新编译打包
6. 启动应用
