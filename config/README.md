# 配置文件目录

此目录用于存放外部配置文件，便于本地、测试、生产环境部署和管理。

## 使用方法

1. **默认配置**：`application.yml`
   - 作为默认完整配置使用
   - 不传环境参数时，默认使用它
   - 注意：不要在这个文件里写 Maven 占位符 `@...@`，否则本地/外部挂载时 YAML 会解析失败

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
   - 传 `-Pdev/-Ptest/-Pprod`：会把 `spring.profiles.active` 写入 jar 内 `application.properties`
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

- `config/application.yml` 会随仓库提交；密码请用 `${DB_PASSWORD}` 占位符，不要写入真实口令
- 也可提交 `application-*.yml` / `application-*.yml.example`
- ✅ 生产环境通过环境变量或 K8s Secret 注入敏感信息
- ✅ CI/CD 使用 `-Pdev` 时，仓库中至少要有 `application-dev.yml` 或 `application-dev.yml.example`
