# OpsFlow 社区版镜像：多阶段构建，源码进镜像内编译，无需本机先 mvn package
#   docker compose up -d --build
#   或：docker build -t opsflow:1.1.2 .
#   多架构：docker buildx build --platform linux/amd64,linux/arm64 -t <镜像名>:<版本> .

# ---------- build ----------
# jar 与 CPU 架构无关：多架构构建时固定在构建机本机架构上编译一次，不走模拟
FROM --platform=$BUILDPLATFORM maven:3.9.9-eclipse-temurin-17 AS build
WORKDIR /build

COPY pom.xml .
COPY core/pom.xml core/
COPY app/pom.xml app/
# 先拉依赖，源码变更时尽量复用层缓存
RUN mvn -B -q -DskipTests dependency:go-offline || true

COPY core/src ./core/src
COPY app/src ./app/src
RUN mvn -B -q -DskipTests package \
    && test -f app/target/opsflow.jar

# ---------- runtime ----------
FROM eclipse-temurin:17-jre-jammy
ARG OPSFLOW_VERSION=1.1.2
LABEL maintainer="OpsFlow" \
      app="opsflow" \
      description="DevOps 构建发布平台" \
      org.opencontainers.image.title="opsflow" \
      org.opencontainers.image.description="OpsFlow 社区版：DevOps 构建发布平台" \
      org.opencontainers.image.version="${OPSFLOW_VERSION}" \
      org.opencontainers.image.licenses="AGPL-3.0-only" \
      org.opencontainers.image.source="https://github.com/jiaowc/OpsFlow"

WORKDIR /app

# curl 只用于健康检查。不固定版本：基础镜像随安全更新变化，固定的版本从源里下架后会导致无法重建
# hadolint ignore=DL3008
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && mkdir -p /app/config /app/logs /app/data \
    && groupadd -r opsflow \
    && useradd -r -g opsflow -d /app -s /sbin/nologin opsflow \
    && chown -R opsflow:opsflow /app

COPY --from=build /build/app/target/opsflow.jar /app/opsflow.jar
COPY --chown=opsflow:opsflow config/ /app/config/
COPY scripts/docker/entrypoint.sh /app/docker-entrypoint.sh
RUN chmod +x /app/docker-entrypoint.sh

ENV TZ=Asia/Shanghai \
    JAVA_OPTS="-Xms256m -Xmx512m"

EXPOSE 8080

HEALTHCHECK --interval=15s --timeout=5s --start-period=45s --retries=8 \
  CMD ["sh", "-c", "curl -fsS http://127.0.0.1:8080/api/health >/dev/null || exit 1"]

# 以 root 进入 entrypoint，修正挂载目录权限后降权为 opsflow
ENTRYPOINT ["/app/docker-entrypoint.sh"]
