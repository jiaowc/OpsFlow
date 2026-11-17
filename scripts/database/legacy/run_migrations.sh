#!/usr/bin/env bash
# 【已归档】引入 Flyway 之前的增量迁移，只用于把很早的旧库追平到 Flyway 基线，之后不再新增脚本。
# 什么时候需要：应用启动或 migrate.sh 报「这个数据库的结构落后于迁移基线」时，执行一次本脚本，然后重新启动。
# 新的表结构/默认数据变更请加到 src/main/resources/db/migration/（V3 起）。
#
# 增量数据库迁移（可重复执行，已存在的变更会跳过）
# 使用场景：拉取新代码后有数据库变更时手动执行，不必每次重启都跑
#
#   bash scripts/database/legacy/run_migrations.sh              # 默认环境（application.yml）
#   bash scripts/database/legacy/run_migrations.sh --profile=dev  # dev 环境（application-dev.yml）
#   连接解析规则见 scripts/database/db_env.sh
#   OPSFLOW_PROFILE=dev bash scripts/database/legacy/run_migrations.sh
#
# 也可直接覆盖（优先级最高）：
#   DB_HOST=172.16.50.183 DB_USER=root DB_PASS='xxx' DB_NAME=opsflow bash scripts/database/legacy/run_migrations.sh

set -e

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
MIGRATIONS_DIR="${PROJECT_DIR}/scripts/database/legacy/migrations"
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

# shellcheck source=db_env.sh
source "${PROJECT_DIR}/scripts/database/db_env.sh"
if ! resolve_db_env "$PROFILE"; then
  echo "也可直接传入: DB_PASS='你的密码' $0 --profile=${PROFILE:-dev}" >&2
  exit 1
fi

echo "迁移配置:"
echo "  config : $DB_CONFIG_SOURCE"
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
run_sql "${MIGRATIONS_DIR}/migrate_audit_log.sql" "audit_log 操作审计 + audit:view"
run_sql "${MIGRATIONS_DIR}/migrate_pipeline_webhook.sql" "pipeline_webhook_trigger Git Webhook 自动构建"
run_sql "${MIGRATIONS_DIR}/migrate_project.sql" "project / project_member 项目隔离 + project:manage"
run_sql "${MIGRATIONS_DIR}/migrate_approval_multi_approver.sql" "审批步骤多审批人"
run_sql "${MIGRATIONS_DIR}/migrate_fix_comment_encoding.sql" "修复乱码字段注释"
run_sql "${MIGRATIONS_DIR}/migrate_drop_unused_columns.sql" "删除不再使用的字段"
run_sql "${MIGRATIONS_DIR}/migrate_build_node_host_key.sql" "build_node SSH 主机指纹"
run_sql "${MIGRATIONS_DIR}/migrate_secret_column_width.sql" "凭据加密存储列宽"

echo
echo "全部迁移执行完成"
