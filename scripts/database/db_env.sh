#!/usr/bin/env bash
# 数据库脚本共用：解析 OpsFlow 的 MySQL 连接信息，与应用启动时的取值规则一致。
#
# 用法（在其它脚本中 source 后调用）：
#   source "${PROJECT_DIR}/scripts/database/db_env.sh"
#   resolve_db_env "$PROFILE"     # PROFILE 为空表示默认环境
#
# 取值优先级（高 → 低）：
#   1. 环境变量 DB_HOST / DB_PORT / DB_NAME / DB_USER / DB_PASS（脚本传统变量名）
#   2. 环境变量 DB_URL / DB_USERNAME / DB_PASSWORD（与应用一致）
#   3. config/application-local.yml 中的 DB_URL / DB_USERNAME / DB_PASSWORD
#   4. config/application-<profile>.yml、application.yml 中 ${KEY:默认值} 的默认值
#
# 结果写入：DB_HOST DB_PORT DB_NAME DB_USER DB_PASS DB_CONFIG_SOURCE

_DB_ENV_PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
_DB_ENV_CONFIG_DIR="${_DB_ENV_PROJECT_DIR}/config"
_DB_ENV_LOCAL_FILE="${_DB_ENV_PROJECT_DIR}/config/application-local.yml"

# 去掉 YAML 值两端空白与引号
_db_strip_yaml_value() {
  local v="$1"
  v="${v#"${v%%[![:space:]]*}"}"
  v="${v%"${v##*[![:space:]]}"}"
  if [[ "$v" == \"*\" && "$v" == *\" ]]; then
    v="${v:1:${#v}-2}"
  elif [[ "$v" == \'*\' && "$v" == *\' ]]; then
    v="${v:1:${#v}-2}"
  fi
  printf '%s' "$v"
}

# 读取 spring.datasource.<key>（只在 datasource 段内匹配）
_db_extract_ds() {
  local file="$1" key="$2"
  [ -f "$file" ] || return 0
  awk -v key="$key" '
    BEGIN { in_ds=0 }
    /^[[:space:]]*datasource:[[:space:]]*$/ { in_ds=1; next }
    in_ds && /^[^[:space:]#]/ { in_ds=0 }
    in_ds && $0 ~ "^[[:space:]]*" key ":" {
      sub("^[[:space:]]*" key ":[[:space:]]*", "", $0)
      print $0
      exit
    }
  ' "$file"
}

# 读取顶层键（如 application-local.yml 中的 DB_PASSWORD）
_db_extract_top() {
  local file="$1" key="$2"
  [ -f "$file" ] || return 0
  awk -v key="$key" '
    $0 ~ "^" key ":" {
      sub("^" key ":[[:space:]]*", "", $0)
      print $0
      exit
    }
  ' "$file"
}

# 展开 ${KEY} / ${KEY:默认值}：环境变量 > application-local.yml > 默认值
_db_resolve_placeholder() {
  local v="$1"
  if [[ "$v" =~ ^\$\{([A-Za-z_][A-Za-z0-9_]*)(:(.*))?\}$ ]]; then
    local name="${BASH_REMATCH[1]}" fallback="${BASH_REMATCH[3]}" has_default="${BASH_REMATCH[2]}"
    if [ -n "${!name:-}" ]; then
      printf '%s' "${!name}"
      return
    fi
    local from_local
    from_local="$(_db_strip_yaml_value "$(_db_extract_top "$_DB_ENV_LOCAL_FILE" "$name")")"
    if [ -n "$from_local" ]; then
      printf '%s' "$from_local"
      return
    fi
    if [ -n "$has_default" ]; then
      printf '%s' "$fallback"
    fi
    return
  fi
  printf '%s' "$v"
}

# 按 profile 文件优先、公共文件兜底读取 datasource 字段并展开占位符
_db_ds_value() {
  local profile="$1" key="$2" raw=""
  if [ -n "$profile" ]; then
    raw="$(_db_strip_yaml_value "$(_db_extract_ds "${_DB_ENV_CONFIG_DIR}/application-${profile}.yml" "$key")")"
  fi
  if [ -z "$raw" ]; then
    raw="$(_db_strip_yaml_value "$(_db_extract_ds "${_DB_ENV_CONFIG_DIR}/application.yml" "$key")")"
  fi
  _db_resolve_placeholder "$raw"
}

resolve_db_env() {
  local profile="${1:-}"
  if [ -n "$profile" ] && [ ! -f "${_DB_ENV_CONFIG_DIR}/application-${profile}.yml" ]; then
    echo "未知环境: ${profile}（不存在 config/application-${profile}.yml）" >&2
    return 1
  fi

  local url user pass
  url="$(_db_ds_value "$profile" url)"
  user="$(_db_ds_value "$profile" username)"
  pass="$(_db_ds_value "$profile" password)"

  if [ -z "$url" ] && [ -z "${DB_HOST:-}" ]; then
    echo "未解析到数据库地址（prod 等环境需设置 DB_URL 或 DB_HOST）" >&2
    return 1
  fi

  # 解析 jdbc:mysql://host:port/dbname?...
  local jdbc="${url#jdbc:mysql://}"
  jdbc="${jdbc#mysql://}"
  local hostport="${jdbc%%/*}" pathdb="${jdbc#*/}"
  local url_host="${hostport%%:*}" url_port="" url_db="${pathdb%%\?*}"
  if [[ "$hostport" == *:* ]]; then
    url_port="${hostport##*:}"
  fi

  DB_HOST="${DB_HOST:-${url_host:-127.0.0.1}}"
  DB_PORT="${DB_PORT:-${url_port:-3306}}"
  DB_NAME="${DB_NAME:-${url_db:-opsflow}}"
  DB_USER="${DB_USER:-${user:-root}}"
  DB_PASS="${DB_PASS:-$pass}"
  DB_CONFIG_SOURCE="profile=${profile:-默认}"
  [ -f "$_DB_ENV_LOCAL_FILE" ] && DB_CONFIG_SOURCE="${DB_CONFIG_SOURCE}, config/application-local.yml"

  if [ -z "$DB_PASS" ]; then
    echo "未解析到数据库密码：请设置 DB_PASSWORD（或 DB_PASS），或写入 config/application-local.yml" >&2
    return 1
  fi
  return 0
}
