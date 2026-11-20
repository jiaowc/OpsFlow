#!/bin/bash
set -euo pipefail

export JAVA_HOME="${JAVA_HOME:-/opt/java/openjdk}"
export PATH="${JAVA_HOME}/bin:${PATH}"

PASS="${BUILD_NODE_SSH_PASSWORD:-Build12345}"
echo "root:${PASS}" | chpasswd
mkdir -p /root/opsflow

# 挂了宿主机 docker.sock 时，尽量保证 root 可访问
if [[ -S /var/run/docker.sock ]]; then
  chmod 666 /var/run/docker.sock 2>/dev/null || true
fi

# sshd 非登录 shell 也能拿到 JAVA_HOME
grep -q 'JAVA_HOME=/opt/java/openjdk' /root/.bashrc 2>/dev/null || {
  echo 'export JAVA_HOME=/opt/java/openjdk' >> /root/.bashrc
  echo 'export PATH="$JAVA_HOME/bin:$PATH"' >> /root/.bashrc
}

exec /usr/sbin/sshd -D -e
