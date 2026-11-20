# 本地调试用构建节点：SSH + JDK8 + Maven + Node 22 + Git + Docker CLI（挂宿主机 docker.sock）
# Node 取自官方镜像（jammy 的 apt 源只有 Node 12，跑不了 Vite 6）
FROM node:22-bookworm-slim AS node

FROM eclipse-temurin:8-jdk-jammy

ENV DEBIAN_FRONTEND=noninteractive
# apt 装 maven/docker 可能再拉系统 JDK，固定用 Temurin 8
ENV JAVA_HOME=/opt/java/openjdk
ENV PATH="${JAVA_HOME}/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"

RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        openssh-server \
        git \
        maven \
        curl \
        ca-certificates \
        docker.io \
    && rm -rf /var/lib/apt/lists/* \
    && mkdir -p /var/run/sshd /root/opsflow \
    && ssh-keygen -A \
    && sed -i 's/^#\?PermitRootLogin.*/PermitRootLogin yes/' /etc/ssh/sshd_config \
    && sed -i 's/^#\?PasswordAuthentication.*/PasswordAuthentication yes/' /etc/ssh/sshd_config \
    && sed -i 's/^#\?PubkeyAuthentication.*/PubkeyAuthentication yes/' /etc/ssh/sshd_config \
    && echo 'root:Build12345' | chpasswd \
    && echo 'export JAVA_HOME=/opt/java/openjdk' > /etc/profile.d/java.sh \
    && echo 'export PATH="$JAVA_HOME/bin:$PATH"' >> /etc/profile.d/java.sh \
    # OpsFlow SSH 用非登录 shell，默认 PATH 里 /usr/bin/java 可能被 apt 装成 11；强制指到 Temurin 8
    && ln -sfn /opt/java/openjdk/bin/java /usr/local/bin/java \
    && ln -sfn /opt/java/openjdk/bin/javac /usr/local/bin/javac \
    && if [ -e /usr/bin/java ] && [ ! -L /usr/bin/java ]; then mv /usr/bin/java /usr/bin/java.distro; fi \
    && ln -sfn /opt/java/openjdk/bin/java /usr/bin/java \
    && if [ -e /usr/bin/javac ] && [ ! -L /usr/bin/javac ]; then mv /usr/bin/javac /usr/bin/javac.distro; fi \
    && ln -sfn /opt/java/openjdk/bin/javac /usr/bin/javac

# Node.js + npm：装到 /usr/local（已在默认 PATH，SSH 非登录 shell 可直接用）
COPY --from=node /usr/local/bin/node /usr/local/bin/node
COPY --from=node /usr/local/lib/node_modules /usr/local/lib/node_modules
RUN ln -sfn ../lib/node_modules/npm/bin/npm-cli.js /usr/local/bin/npm \
    && ln -sfn ../lib/node_modules/npm/bin/npx-cli.js /usr/local/bin/npx \
    && node -v && npm -v

COPY entrypoint.sh /entrypoint.sh
RUN chmod +x /entrypoint.sh

EXPOSE 22
WORKDIR /root/opsflow
ENTRYPOINT ["/entrypoint.sh"]
