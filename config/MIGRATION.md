# 配置文件迁移指南

## 配置文件说明

### 项目内配置文件（开发环境）
- **位置**: `web/src/main/resources/application.yml`
- **用途**: 开发环境默认配置
- **Git**: ✅ 提交到 Git（不包含敏感信息）

### 外部配置文件（生产环境）
- **位置**: `config/application.yml`
- **用途**: 生产环境配置，可覆盖项目内配置
- **Git**: ❌ 不提交到 Git（包含敏感信息）

### 配置模板
- **位置**: `config/application.yml.example`
- **用途**: 配置模板，供参考
- **Git**: ✅ 提交到 Git

## 配置优先级

Spring Boot 配置加载优先级（从高到低）：
1. 命令行参数 `--spring.config.location`
2. 外部配置文件 `./config/application.yml`
3. 项目内配置文件 `classpath:application.yml`

## 迁移方式

### 方式一：使用迁移脚本（推荐）

```bash
# 运行迁移脚本
./scripts/tools/migrate_config.sh
```

脚本会：
- 自动从项目内配置文件复制到外部配置
- 如果外部配置已存在，会提示备份或对比
- 完成后提示编辑敏感信息

### 方式二：手动迁移

```bash
# 1. 复制配置文件
cp web/src/main/resources/application.yml config/application.yml

# 2. 编辑配置文件，修改敏感信息
vim config/application.yml
# 修改数据库密码、连接信息等
```

### 方式三：从模板创建

```bash
# 1. 复制模板
cp config/application.yml.example config/application.yml

# 2. 参考项目内配置，填写实际值
vim config/application.yml
```

## 配置内容对比

### 项目内配置 vs 外部配置

**相同点**：
- 配置项结构相同
- 配置项名称相同

**不同点**：
- 外部配置可以包含生产环境的实际值（密码、IP等）
- 外部配置可以覆盖项目内配置的任何值

### 推荐做法

1. **开发环境**：
   - 使用项目内默认配置
   - 配置文件中使用占位符或示例值

2. **生产环境**：
   - 使用外部配置文件
   - 包含实际的生产环境配置值
   - 敏感信息使用环境变量或加密

## 配置示例

### 项目内配置（开发环境）

```yaml
spring:
  datasource:
    url: jdbc:mysql://127.0.0.1:3306/opsflow?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai
    username: root
    password: dev_password  # 开发环境密码
```

### 外部配置（生产环境）

```yaml
spring:
  datasource:
    url: jdbc:mysql://prod-db.example.com:3306/opsflow?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai
    username: opsflow_user
    password: ${DB_PASSWORD}  # 使用环境变量，更安全
```

## 安全建议

1. ⚠️ **不要将包含真实密码的配置文件提交到 Git**
2. ✅ 外部配置文件已在 `.gitignore` 中
3. ✅ 使用环境变量管理敏感信息
4. ✅ 定期更新密码
5. ✅ 不同环境使用不同的配置文件

## 常见问题

### Q: 外部配置和项目内配置都要保留吗？
A: 是的。项目内配置作为默认配置和开发环境使用，外部配置用于生产环境。

### Q: 如果外部配置不存在会怎样？
A: 应用会使用项目内的默认配置（`web/src/main/resources/application.yml`）。

### Q: 如何切换回项目内配置？
A: 删除或重命名外部配置文件即可，应用会自动使用项目内配置。

### Q: 可以同时使用多个外部配置文件吗？
A: 可以，使用 `--spring.config.location` 指定多个配置文件，用逗号分隔。
