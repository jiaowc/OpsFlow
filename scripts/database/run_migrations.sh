# 增量数据库迁移（可重复执行，已存在的变更会跳过）
# 使用场景：拉取新代码后有数据库变更时手动执行，不必每次重启都跑
#   bash scripts/database/run_migrations.sh

set -e

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
CONFIG_FILE="${PROJECT_DIR}/config/application.yml"
MIGRATIONS_DIR="${PROJECT_DIR}/scripts/database/migrations"

if [ ! -f "$CONFIG_FILE" ]; then
  echo "配置文件不存在: $CONFIG_FILE"
  exit 1
fi

DB_URL=$(grep -E '^\s*url:' "$CONFIG_FILE" | head -1 | sed 's/.*url:[[:space:]]*//')
DB_USER=$(grep -E '^\s*username:' "$CONFIG_FILE" | head -1 | awk '{print $2}')
DB_PASS=$(grep -E '^\s*password:' "$CONFIG_FILE" | head -1 | sed 's/.*password:[[:space:]]*//')
DB_NAME=$(echo "$DB_URL" | sed -E 's|.*:[0-9]+/([^?]+).*|\1|')

run_sql() {
  local file="$1"
  local label="$2"
  if [ ! -f "$file" ]; then
    return 0
  fi
  echo "执行迁移: ${label:-$(basename "$file")}"
  if mysql -h127.0.0.1 -u"$DB_USER" -p"$DB_PASS" --default-character-set=utf8mb4 "$DB_NAME" < "$file"; then
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
run_sql "${MIGRATIONS_DIR}/migrate_rbac.sql" "RBAC 用户角色权限 + 种子数据"
run_sql "${MIGRATIONS_DIR}/migrate_user_source.sql" "user.source 用户来源"
run_sql "${MIGRATIONS_DIR}/migrate_stage_log_file.sql" "阶段日志文件路径/预览字段"
run_sql "${MIGRATIONS_DIR}/migrate_build_job_indexes.sql" "build_job 热查询索引"
