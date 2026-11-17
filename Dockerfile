# OpsFlow 多阶段构建：源码进镜像内编译，无需本机先 mvn package
#   docker compose up -d --build
#   或：docker build -t opsflow:1.0.0 .

# ---------- build ----------
FROM maven:3.9.9-eclipse-temurin-17 AS build
WORKDIR /build

COPY pom.xml .
# 先拉依赖，源码变更时尽量复用层缓存
RUN mvn -B -q -DskipTests dependency:go-offline || true

COPY src ./src
RUN mvn -B -q -DskipTests package \
    && test -f target/opsflow.jar

# ---------- runtime ----------
FROM eclipse-temurin:17-jre-jammy
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

COPY --from=build /build/target/opsflow.jar /app/opsflow.jar
COPY --chown=opsflow:opsflow config/ /app/config/
COPY scripts/docker/entrypoint.sh /app/docker-entrypoint.sh
RUN chmod +x /app/docker-entrypoint.sh

ENV TZ=Asia/Shanghai \
    JAVA_OPTS="-Xms256m -Xmx512m"

EXPOSE 8080

HEALTHCHECK --interval=15s --timeout=5s --start-period=45s --retries=8 \
  CMD curl -fsS http://127.0.0.1:8080/api/health >/dev/null || exit 1

# 以 root 进入 entrypoint，修正挂载目录权限后降权为 opsflow
ENTRYPOINT ["/app/docker-entrypoint.sh"]
