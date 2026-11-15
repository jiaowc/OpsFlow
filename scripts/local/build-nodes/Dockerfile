# 本地调试用构建节点：SSH + JDK8 + Maven + Git + Docker CLI（挂宿主机 docker.sock）
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

COPY entrypoint.sh /entrypoint.sh
RUN chmod +x /entrypoint.sh

EXPOSE 22
WORKDIR /root/opsflow
ENTRYPOINT ["/entrypoint.sh"]
