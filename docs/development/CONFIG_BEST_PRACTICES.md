# 配置文件最佳实践

## ❌ 不要删除项目内配置文件

### 为什么不应该删除？

1. **作为默认配置**
   - 如果外部配置文件不存在，应用会使用项目内配置
   - 提供配置的默认值和结构

2. **开发环境使用**
   - 开发环境通常直接使用项目内配置
   - 不需要每次启动都指定外部配置文件

3. **配置模板和文档**
   - 项目内配置展示了所有可用的配置项
   - 新开发者可以快速了解配置结构

4. **Spring Boot 标准做法**
   - Spring Boot 官方推荐保留项目内配置
   - 外部配置用于覆盖特定环境的值

## ✅ 推荐做法

### 方案一：项目内配置使用占位符（推荐）

**项目内配置** (`src/main/resources/application.yml`)：
```yaml
spring:
  datasource:
    url: jdbc:mysql://127.0.0.1:3306/opsflow?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai
    username: root
    password: ${DB_PASSWORD:dev_password}  # 使用环境变量，默认值为 dev_password
    driver-class-name: com.mysql.cj.jdbc.Driver
```

**外部配置** (`config/application.yml`)：
```yaml
spring:
  datasource:
    url: jdbc:mysql://prod-db.example.com:3306/opsflow?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai
    username: opsflow_user
    password: ${DB_PASSWORD}  # 从环境变量读取
```

### 方案二：项目内配置使用示例值

**项目内配置**：
```yaml
spring:
  datasource:
    url: jdbc:mysql://127.0.0.1:3306/opsflow?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai
    username: root
    password: YOUR_PASSWORD_HERE  # 占位符，提醒需要配置
    driver-class-name: com.mysql.cj.jdbc.Driver
```

**外部配置**：
```yaml
spring:
  datasource:
    url: jdbc:mysql://127.0.0.1:3306/opsflow?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai
    username: root
    password: actual_production_password  # 实际密码
    driver-class-name: com.mysql.cj.jdbc.Driver
```

## 配置优先级

Spring Boot 配置加载顺序（从高到低）：

1. **命令行参数** `--spring.config.location`
2. **外部配置文件** `./config/application.yml`
3. **项目内配置文件** `classpath:application.yml`

**重要**：外部配置会**覆盖**项目内配置，但不会完全替代。如果外部配置中某个配置项不存在，会使用项目内配置的值。

## 实际使用场景

### 开发环境
```bash
# 直接启动，使用项目内配置
java -jar target/opsflow.jar
```

### 生产环境
```bash
# 使用外部配置
java -jar target/opsflow.jar --spring.config.location=file:./config/application.yml
```

### 测试环境
```bash
# 可以创建 config/application-test.yml
java -jar target/opsflow.jar --spring.profiles.active=test
```

## 安全建议

### ✅ 应该做的

1. **项目内配置使用占位符或示例值**
   ```yaml
   password: ${DB_PASSWORD:dev_password}
   # 或
   password: YOUR_PASSWORD_HERE
   ```

2. **外部配置包含实际值**
   - 外部配置已在 `.gitignore` 中
   - 不会提交到 Git

3. **使用环境变量**
   ```yaml
   password: ${DB_PASSWORD}
   ```
   启动时设置：
   ```bash
   export DB_PASSWORD=actual_password
   java -jar app.jar
   ```

### ❌ 不应该做的

1. ❌ 删除项目内配置文件
2. ❌ 在项目内配置中硬编码生产环境密码
3. ❌ 将包含真实密码的配置文件提交到 Git

## 总结

**保留项目内配置文件**，但：
- 使用占位符或环境变量
- 外部配置用于生产环境
- 两者配合使用，而不是替代关系
