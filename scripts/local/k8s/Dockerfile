# 本地调试用部署节点：SSH + kubectl（测完随 kind 一起销毁）
FROM debian:bookworm-slim

ENV DEBIAN_FRONTEND=noninteractive
ARG KUBECTL_VERSION=v1.29.7

# 管道（echo | chpasswd）里任一命令失败都让构建失败
SHELL ["/bin/bash", "-o", "pipefail", "-c"]

# DL3008：本地调试镜像，apt 包不固定版本（基础镜像更新后固定的版本会下架，反而构建不了）
# hadolint ignore=DL3008
RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        openssh-server \
        ca-certificates \
        curl \
        bash \
    && rm -rf /var/lib/apt/lists/* \
    && ARCH="$(dpkg --print-architecture)" \
    && case "$ARCH" in \
         amd64) KARCH=amd64 ;; \
         arm64) KARCH=arm64 ;; \
         *) echo "unsupported arch: $ARCH" >&2; exit 1 ;; \
       esac \
    && curl -fsSL "https://dl.k8s.io/release/${KUBECTL_VERSION}/bin/linux/${KARCH}/kubectl" \
         -o /usr/local/bin/kubectl \
    && chmod +x /usr/local/bin/kubectl \
    && mkdir -p /var/run/sshd /root/opsflow /root/.kube \
    && ssh-keygen -A \
    && sed -i 's/^#\?PermitRootLogin.*/PermitRootLogin yes/' /etc/ssh/sshd_config \
    && sed -i 's/^#\?PasswordAuthentication.*/PasswordAuthentication yes/' /etc/ssh/sshd_config \
    && echo 'root:Build12345' | chpasswd

COPY entrypoint.sh /entrypoint.sh
RUN chmod +x /entrypoint.sh

EXPOSE 22
WORKDIR /root/opsflow
ENTRYPOINT ["/entrypoint.sh"]
