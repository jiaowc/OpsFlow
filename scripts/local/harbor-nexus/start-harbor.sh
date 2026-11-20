#!/usr/bin/env bash
# 下载官方 Harbor online installer，配置 HTTP，启动。
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=env.sh
source "${SCRIPT_DIR}/env.sh"

mkdir -p "$HN_DATA_DIR"
INSTALLER_TGZ="${HN_DATA_DIR}/harbor-online-installer-${HARBOR_VERSION}.tgz"
INSTALLER_URL="https://github.com/goharbor/harbor/releases/download/${HARBOR_VERSION}/harbor-online-installer-${HARBOR_VERSION}.tgz"

if [[ ! -d "$HARBOR_INSTALL_DIR" || ! -f "$HARBOR_INSTALL_DIR/prepare" ]]; then
  if [[ ! -f "$INSTALLER_TGZ" ]]; then
    echo "[harbor] 下载 ${INSTALLER_URL} ..."
    curl -fL --retry 3 -o "$INSTALLER_TGZ" "$INSTALLER_URL"
  else
    echo "[harbor] 使用已下载的安装包: $INSTALLER_TGZ"
  fi
  rm -rf "$HARBOR_INSTALL_DIR"
  mkdir -p "$HN_DATA_DIR"
  tar -xzf "$INSTALLER_TGZ" -C "$HN_DATA_DIR"
  # 解压后目录名为 harbor
  if [[ ! -d "$HARBOR_INSTALL_DIR" ]]; then
    echo "[harbor] 解压后未找到 $HARBOR_INSTALL_DIR" >&2
    exit 1
  fi
fi

HARBOR_YML="${HARBOR_INSTALL_DIR}/harbor.yml"
if [[ ! -f "$HARBOR_YML" ]]; then
  if [[ -f "${HARBOR_INSTALL_DIR}/harbor.yml.tmpl" ]]; then
    cp "${HARBOR_INSTALL_DIR}/harbor.yml.tmpl" "$HARBOR_YML"
  else
    echo "[harbor] 找不到 harbor.yml / harbor.yml.tmpl" >&2
    exit 1
  fi
fi

export HARBOR_DATA_VOLUME="${HN_DATA_DIR}/harbor-data"
mkdir -p "$HARBOR_DATA_VOLUME"

echo "[harbor] 写入 harbor.yml（HTTP ${HARBOR_HTTP_PORT}，hostname=${HARBOR_HOSTNAME}）..."
python3 - "$HARBOR_YML" <<'PY'
import re, sys, os
path = sys.argv[1]
hostname = os.environ["HARBOR_HOSTNAME"]
http_port = os.environ["HARBOR_HTTP_PORT"]
admin_pass = os.environ["HARBOR_ADMIN_PASSWORD"]
data_volume = os.environ["HARBOR_DATA_VOLUME"]
external_url = os.environ["HARBOR_EXTERNAL_URL"]

with open(path, "r", encoding="utf-8") as f:
    text = f.read()

def set_simple(key, value, text):
    # hostname: xxx
    pat = re.compile(r"^(" + re.escape(key) + r":\s*).*$", re.M)
    if pat.search(text):
        return pat.sub(r"\g<1>" + value, text, count=1)
    return text + f"\n{key}: {value}\n"

text = set_simple("hostname", hostname, text)
text = set_simple("harbor_admin_password", admin_pass, text)
text = set_simple("data_volume", data_volume, text)

# http.port
text = re.sub(
    r"(?m)^(http:\s*\n(?:[ \t]+.*\n)*?[ \t]+port:\s*)\d+",
    r"\g<1>" + http_port,
    text,
    count=1,
)

# 注释掉整个 https: 块（保持缩进块被注释）
lines = text.splitlines(True)
out = []
i = 0
while i < len(lines):
    line = lines[i]
    if re.match(r"^https:\s*$", line) or re.match(r"^#\s*https:\s*$", line):
        # 注释 https 及其缩进子行
        if not line.lstrip().startswith("#"):
            out.append("# " + line)
        else:
            out.append(line)
        i += 1
        while i < len(lines) and (lines[i].startswith(" ") or lines[i].startswith("\t") or lines[i].strip() == "" or lines[i].lstrip().startswith("#")):
            # 空行直接保留；未注释的缩进行加 #
            if lines[i].strip() and not lines[i].lstrip().startswith("#") and (lines[i].startswith(" ") or lines[i].startswith("\t")):
                out.append("# " + lines[i])
            else:
                out.append(lines[i])
            i += 1
        continue
    out.append(line)
    i += 1
text = "".join(out)

# external_url（部分版本有；没有则追加）
if re.search(r"(?m)^external_url:\s*", text):
    text = re.sub(r"(?m)^external_url:\s*.*$", "external_url: " + external_url, text, count=1)
else:
    text = text.rstrip() + f"\n\nexternal_url: {external_url}\n"

with open(path, "w", encoding="utf-8") as f:
    f.write(text)
print("updated", path)
PY

cd "$HARBOR_INSTALL_DIR"
echo "[harbor] 运行 prepare ..."
if [[ -x ./prepare ]]; then
  ./prepare
else
  python3 prepare
fi

echo "[harbor] docker compose up -d ..."
if docker compose version >/dev/null 2>&1; then
  docker compose up -d
elif command -v docker-compose >/dev/null 2>&1; then
  docker-compose up -d
else
  echo "[harbor] 需要 docker compose" >&2
  exit 1
fi

echo "[harbor] 等待 API 就绪: ${HARBOR_COMPONENT_URL}/api/v2.0/systeminfo ..."
for i in $(seq 1 90); do
  CODE="$(curl -s -o /dev/null -w '%{http_code}' "${HARBOR_COMPONENT_URL}/api/v2.0/systeminfo" || true)"
  if [[ "$CODE" == "200" ]]; then
    echo "[harbor] 已就绪 (HTTP ${CODE})"
    break
  fi
  if [[ $i -eq 90 ]]; then
    echo "[harbor] 超时未就绪，最后 HTTP ${CODE}" >&2
    echo "[harbor] 查看: cd ${HARBOR_INSTALL_DIR} && docker compose ps && docker compose logs --tail=80" >&2
    exit 1
  fi
  sleep 4
done

# 创建测试 project（library 默认已有）
create_project() {
  local name="$1"
  local code
  code="$(curl -s -o /tmp/harbor-proj.out -w '%{http_code}' \
    -u "admin:${HARBOR_ADMIN_PASSWORD}" \
    -H 'Content-Type: application/json' \
    -X POST \
    -d "{\"project_name\":\"${name}\",\"public\":true}" \
    "${HARBOR_COMPONENT_URL}/api/v2.0/projects" || true)"
  if [[ "$code" == "201" ]]; then
    echo "[harbor] 已创建 project: ${name}"
  elif [[ "$code" == "409" ]]; then
    echo "[harbor] project 已存在: ${name}"
  else
    echo "[harbor] 创建 project ${name} 返回 HTTP ${code}: $(cat /tmp/harbor-proj.out 2>/dev/null || true)" >&2
  fi
}

create_project "library"
create_project "$HARBOR_TEST_PROJECT"

echo "[harbor] UI: ${HARBOR_COMPONENT_URL}  用户: admin / ${HARBOR_ADMIN_PASSWORD}"
echo "[harbor] Registry host（docker login）: ${HARBOR_REGISTRY_HOST}"
