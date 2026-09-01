# OpsFlow 运行镜像
# 使用前请先在本地编译：
#   mvn clean package -DskipTests
# 再构建镜像：
#   docker build -t opsflow:1.0.0 .

FROM eclipse-temurin:8-jre-jammy
LABEL maintainer="OpsFlow" \
      app="opsflow" \
      description="DevOps 构建发布平台"

WORKDIR /app

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && mkdir -p /app/config /app/logs /app/data \
    && groupadd -r opsflow \
    && useradd -r -g opsflow -d /app -s /sbin/nologin opsflow \
    && chown -R opsflow:opsflow /app

COPY target/opsflow.jar /app/opsflow.jar

ENV TZ=Asia/Shanghai

EXPOSE 8080

HEALTHCHECK --interval=15s --timeout=5s --start-period=40s --retries=8 \
  CMD curl -fsS http://127.0.0.1:8080/api/health >/dev/null || exit 1

USER opsflow

# Spring Boot 会自动加载 /app/config/application.yml（file:./config/）
# 未挂载配置时则使用 jar 内默认 application.yml
ENTRYPOINT ["java", "-Xms256m", "-Xmx512m", "-jar", "/app/opsflow.jar"]
