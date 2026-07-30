# 配置文件目录

此目录用于存放外部配置文件，便于本地、测试、生产环境部署和管理。

## 使用方法

1. **默认配置**：`application.yml`
   - 作为默认完整配置使用
   - 不传环境参数时，默认使用它

2. **环境配置**：
   - `application-dev.yml`：开发环境
   - `application-test.yml`：测试环境
   - `application-prod.yml`：生产环境
   - 这些文件可以直接复制 `application.yml` 的完整内容，再按环境修改差异项

3. **容器 / K8s**：
   - 推荐挂载整个 `config/` 目录到 `/app/config`
   - 启动时通过环境变量 `SPRING_PROFILES_ACTIVE=dev|test|prod` 选择配置

4. **Maven 打包按环境内置配置**：
   - `mvn clean package -DskipTests`
   - `mvn clean package -Pdev -DskipTests`
   - `mvn clean package -Ptest -DskipTests`
   - `mvn clean package -Pprod -DskipTests`
   - 不传 `-P`：默认使用 `application.yml`
   - 传 `-Pdev/-Ptest/-Pprod`：默认激活对应环境配置
   - 运行时仍可通过 `SPRING_PROFILES_ACTIVE` 覆盖

## 启动命令

```bash
# 使用默认配置启动
java -jar opsflow.jar

# 使用外部配置目录启动（推荐）
java -jar opsflow.jar --spring.config.additional-location=file:./config/

# 指定 dev 环境
export SPRING_PROFILES_ACTIVE=dev
java -jar opsflow.jar --spring.config.additional-location=file:./config/
```

## 配置优先级

Spring Boot 配置加载顺序（简化版）：
1. 激活 profile：`SPRING_PROFILES_ACTIVE` 或 `--spring.profiles.active`
2. 外部配置目录：`./config/`
3. 默认配置：`application.yml`
4. 环境覆盖：`application-<profile>.yml`

## 安全建议

- ⚠️ 生产环境不要把真实密码直接写进 Git 里的 profile 文件
- ✅ 推荐在 `application-dev.yml / application-prod.yml` 中使用 `${DB_PASSWORD}` 占位符
- ✅ 通过环境变量或 K8s Secret 注入敏感信息
