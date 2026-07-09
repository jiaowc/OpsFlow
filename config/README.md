# 配置文件目录

此目录用于存放外部配置文件，便于生产环境部署和管理。

## 使用方法

1. **开发环境**：配置文件在 `web/src/main/resources/application.yml`（已包含在项目中）

2. **生产环境**：
   - 将 `application.yml.example` 复制为 `application.yml`
   - 修改数据库连接等配置
   - 启动应用时使用 `--spring.config.location` 参数指定配置文件路径

## 启动命令

```bash
# 使用外部配置文件启动
java -jar opsflow.jar --spring.config.location=file:./config/application.yml

# 或者使用环境变量
export SPRING_CONFIG_LOCATION=file:./config/application.yml
java -jar opsflow.jar
```

## 配置优先级

Spring Boot 配置加载优先级（从高到低）：
1. 命令行参数 `--spring.config.location`
2. 环境变量 `SPRING_CONFIG_LOCATION`
3. 外部配置文件 `./config/application.yml`
4. 项目内配置文件 `classpath:application.yml`

## 安全建议

- ⚠️ **不要将包含密码的 `application.yml` 提交到 Git**
- ✅ 将 `application.yml` 添加到 `.gitignore`
- ✅ 使用 `application.yml.example` 作为模板
- ✅ 生产环境建议使用环境变量或配置中心管理敏感信息
