# 本地调试用部署节点：SSH + kubectl（测完随 kind 一起销毁）
FROM debian:bookworm-slim

ENV DEBIAN_FRONTEND=noninteractive
ARG KUBECTL_VERSION=v1.29.7

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
