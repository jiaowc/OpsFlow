#!/bin/bash
set -euo pipefail

PASS="${DEPLOY_NODE_SSH_PASSWORD:-Build12345}"
echo "root:${PASS}" | chpasswd
mkdir -p /root/opsflow /root/.kube

# 启动时若挂载了 kubeconfig，确保权限可读
if [[ -f /root/.kube/config ]]; then
  chmod 600 /root/.kube/config || true
fi

exec /usr/sbin/sshd -D -e
