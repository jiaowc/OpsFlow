#!/usr/bin/env bash
# 增量数据库迁移（可重复执行，已存在的变更会跳过）
# 使用场景：拉取新代码后有数据库变更时手动执行，不必每次重启都跑
#
#   bash scripts/database/run_migrations.sh              # 默认读 config/application.yml
#   bash scripts/database/run_migrations.sh --profile=dev  # 读 config/application-dev.yml
#   OPSFLOW_PROFILE=dev bash scripts/database/run_migrations.sh
#
# 也可直接覆盖（优先级最高）：
#   DB_HOST=172.16.50.183 DB_USER=root DB_PASS='xxx' DB_NAME=opsflow bash scripts/database/run_migrations.sh

set -e

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
MIGRATIONS_DIR="${PROJECT_DIR}/scripts/database/migrations"
PROFILE="${OPSFLOW_PROFILE:-}"

for arg in "$@"; do
  case "$arg" in
    --profile=*)
      PROFILE="${arg#--profile=}"
      ;;
    -p|--profile)
      echo "请使用 --profile=dev 形式" >&2
      exit 1
      ;;
    -h|--help)
      echo "用法: $0 [--profile=dev|test|prod]"
      exit 0
      ;;
  esac
done

if [ -n "$PROFILE" ]; then
  CONFIG_FILE="${PROJECT_DIR}/config/application-${PROFILE}.yml"
else
  CONFIG_FILE="${PROJECT_DIR}/config/application.yml"
fi

if [ ! -f "$CONFIG_FILE" ]; then
  echo "配置文件不存在: $CONFIG_FILE" >&2
  exit 1
fi

# 去掉 YAML 值两端引号与首尾空白
strip_yaml_value() {
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

# 从 spring.datasource 段解析（避免误匹配其它 username/password）
extract_ds() {
  local key="$1"
  awk -v key="$key" '
    BEGIN { in_ds=0 }
    /^[[:space:]]*datasource:[[:space:]]*$/ { in_ds=1; next }
    in_ds && /^[^[:space:]#]/ { in_ds=0 }
    in_ds && $0 ~ "^[[:space:]]*" key ":" {
      sub("^[[:space:]]*" key ":[[:space:]]*", "", $0)
      print $0
      exit
    }
  ' "$CONFIG_FILE"
}

if [ -z "${DB_URL:-}" ]; then
  DB_URL=$(strip_yaml_value "$(extract_ds url)")
fi
if [ -z "${DB_USER:-}" ]; then
  DB_USER=$(strip_yaml_value "$(extract_ds username)")
fi
if [ -z "${DB_PASS:-}" ]; then
  DB_PASS=$(strip_yaml_value "$(extract_ds password)")
fi

# 解析 jdbc:mysql://host:port/dbname?...
if [ -z "${DB_HOST:-}" ] || [ -z "${DB_NAME:-}" ] || [ -z "${DB_PORT:-}" ]; then
  local_jdbc="${DB_URL#jdbc:mysql://}"
  local_jdbc="${local_jdbc#mysql://}"
  hostport="${local_jdbc%%/*}"
  pathdb="${local_jdbc#*/}"
  dbname="${pathdb%%\?*}"
  if [ -z "${DB_HOST:-}" ]; then
    DB_HOST="${hostport%%:*}"
  fi
  if [ -z "${DB_PORT:-}" ]; then
    if [[ "$hostport" == *:* ]]; then
      DB_PORT="${hostport##*:}"
    else
      DB_PORT=3306
    fi
  fi
  if [ -z "${DB_NAME:-}" ]; then
    DB_NAME="$dbname"
  fi
fi

DB_HOST="${DB_HOST:-127.0.0.1}"
DB_PORT="${DB_PORT:-3306}"
DB_USER="${DB_USER:-root}"
DB_NAME="${DB_NAME:-opsflow}"

if [ -z "$DB_PASS" ] || [[ "$DB_PASS" == \$\{* ]]; then
  echo "数据库密码未解析到有效值（配置: $CONFIG_FILE）" >&2
  echo "请检查 password，或通过环境变量传入: DB_PASS='你的密码' $0 --profile=${PROFILE:-dev}" >&2
  exit 1
fi

echo "迁移配置:"
echo "  config : $CONFIG_FILE"
echo "  host   : $DB_HOST:$DB_PORT"
echo "  db     : $DB_NAME"
echo "  user   : $DB_USER"
echo

run_sql() {
  local file="$1"
  local label="$2"
  if [ ! -f "$file" ]; then
    return 0
  fi
  echo "执行迁移: ${label:-$(basename "$file")}"
  # 使用 --password= 形式，避免特殊字符被 shell 再解析；失败时不把密码打到日志
  if MYSQL_PWD="$DB_PASS" mysql -h"$DB_HOST" -P"$DB_PORT" -u"$DB_USER" \
      --default-character-set=utf8mb4 "$DB_NAME" < "$file"; then
    echo "  ✓ 完成"
  else
    echo "  ✗ 失败: $file" >&2
    return 1
  fi
}

run_sql "${MIGRATIONS_DIR}/migrate_component.sql" "component 表"
run_sql "${MIGRATIONS_DIR}/migrate_cluster.sql" "cluster 表"
run_sql "${MIGRATIONS_DIR}/migrate_pipeline_native.sql" "pipeline 原生表"
run_sql "${MIGRATIONS_DIR}/migrate_build_job_task_name.sql" "build_job.task_name"
run_sql "${MIGRATIONS_DIR}/migrate_pipeline_node.sql" "pipeline 节点字段"
run_sql "${MIGRATIONS_DIR}/migrate_jenkins_node_work_dir.sql" "jenkins_node.work_dir"
run_sql "${MIGRATIONS_DIR}/migrate_work_dir_home_opsflow.sql" "工作目录默认值"
run_sql "${MIGRATIONS_DIR}/migrate_pipeline_step_def.sql" "pipeline_step_def 表"
run_sql "${MIGRATIONS_DIR}/migrate_pipeline_step_clean_rollback.sql" "清理/回滚步骤"
run_sql "${MIGRATIONS_DIR}/migrate_pipeline_step_render_template.sql" "渲染模版步骤"
run_sql "${MIGRATIONS_DIR}/migrate_env_drop_legacy_columns.sql" "env 遗留字段清理"
run_sql "${MIGRATIONS_DIR}/migrate_env_type.sql" "env.env_type"
run_sql "${MIGRATIONS_DIR}/migrate_service_type.sql" "service.service_type"
run_sql "${MIGRATIONS_DIR}/migrate_service_git_type.sql" "service.git_type"
run_sql "${MIGRATIONS_DIR}/migrate_credential.sql" "credential + component.credential_id"
run_sql "${MIGRATIONS_DIR}/migrate_cluster_credential.sql" "cluster.credential_id"
run_sql "${MIGRATIONS_DIR}/migrate_service_component.sql" "service.component_id + git_repo_path"
run_sql "${MIGRATIONS_DIR}/migrate_service_port.sql" "service.service_port"
run_sql "${MIGRATIONS_DIR}/migrate_service_project_name.sql" "service.project_name 所属项目"
run_sql "${MIGRATIONS_DIR}/migrate_build_job_git_type.sql" "build_job.git_type"
run_sql "${MIGRATIONS_DIR}/migrate_pipeline_build_node_ids.sql" "pipeline.build_node_ids"
run_sql "${MIGRATIONS_DIR}/migrate_drop_jenkins_legacy.sql" "清理 Jenkins 遗留命名"
run_sql "${MIGRATIONS_DIR}/migrate_pipeline_step_def_timeout.sql" "pipeline_step_def.timeout_seconds"
run_sql "${MIGRATIONS_DIR}/migrate_pipeline_template.sql" "pipeline_template 表"
run_sql "${MIGRATIONS_DIR}/migrate_pipeline_view.sql" "pipeline_view 表"
run_sql "${MIGRATIONS_DIR}/migrate_pipeline_view_role.sql" "pipeline_view_role 视图角色授权"
run_sql "${MIGRATIONS_DIR}/migrate_approval.sql" "审批流/审批记录/上线任务"
run_sql "${MIGRATIONS_DIR}/migrate_deploy_task_cluster.sql" "deploy_task.cluster_id/namespace"
run_sql "${MIGRATIONS_DIR}/migrate_feishu_approval.sql" "飞书用户映射/审批消息ID"
run_sql "${MIGRATIONS_DIR}/migrate_system_config.sql" "system_config 表"
run_sql "${MIGRATIONS_DIR}/migrate_inbox_notify.sql" "站内信/任务通知渠道"
run_sql "${MIGRATIONS_DIR}/migrate_pipeline_type_deploy_cd.sql" "pipeline类型 + 上线任务CD关联"
run_sql "${MIGRATIONS_DIR}/migrate_deploy_task_parallel.sql" "deploy_task 部署策略/并发数"
run_sql "${MIGRATIONS_DIR}/migrate_deploy_task_lock.sql" "deploy_task 锁定字段 + deploy:unlock"
run_sql "${MIGRATIONS_DIR}/migrate_rbac.sql" "RBAC 用户角色权限 + 种子数据"
run_sql "${MIGRATIONS_DIR}/migrate_user_source.sql" "user.source 用户来源"
run_sql "${MIGRATIONS_DIR}/migrate_stage_log_file.sql" "阶段日志文件路径/预览字段"
run_sql "${MIGRATIONS_DIR}/migrate_build_job_indexes.sql" "build_job 热查询索引"

echo
echo "全部迁移执行完成"
