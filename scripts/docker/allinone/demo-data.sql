-- OpsFlow 体验镜像（Dockerfile.allinone）的演示数据，首次启动时导入一次。
--
-- 目的：登录后各页面不是空的，能看到项目 / 环境 / 服务 / 视图之间的关系。
-- 包含：1 个演示项目、1 个占位集群、dev/test/prod 三个环境、2 个占位组件、3 个示例服务、3 个流水线视图。
-- 不含：任何凭据（钥匙串、kubeconfig、Token）、构建/部署节点、审批流、运行历史。
--       集群和 Harbor 的地址是 example.com 占位符，要真正跑流水线需在界面上换成自己的环境并补上凭据和节点。
-- 表结构、角色权限、admin、步骤定义、模版、通用流水线由 Flyway 默认数据提供（core/src/main/resources/db/migration）。
--
-- 全部使用 INSERT IGNORE，重复执行不会报错，也不会覆盖已有记录。

SET NAMES utf8mb4;

-- 演示项目（负责人 = 默认管理员 admin，id 4）
INSERT IGNORE INTO `project` (`id`, `name`, `description`, `owner_user_id`, `status`) VALUES
  (1, 'demo-shop', '演示项目：一个前后端分离的示例系统', 4, 1);

INSERT IGNORE INTO `project_member` (`project_id`, `user_id`, `role`) VALUES
  (1, 4, 'ADMIN');

-- 占位集群：未配置 kubeconfig，仅用于展示环境与集群的关联
INSERT IGNORE INTO `cluster` (`id`, `name`, `server`, `status`, `description`, `credential_id`) VALUES
  (1, 'demo-k8s', 'https://kubernetes.example.com:6443', 1, '演示占位：未配置 kubeconfig，无法实际部署', NULL);

INSERT IGNORE INTO `env` (`id`, `name`, `k8s_namespace`, `env_type`, `status`, `cluster_id`) VALUES
  (1, 'dev',  'dev',  'nonprod', 1, 1),
  (2, 'test', 'test', 'nonprod', 1, 1),
  (3, 'prod', 'prod', 'prod',    1, 1);

-- 组件：GitHub 公开仓库无需凭据；Harbor 为占位地址，默认禁用
INSERT IGNORE INTO `component` (`id`, `name`, `type`, `url`, `auth_type`, `auth_config`, `credential_id`, `description`, `status`) VALUES
  (1, 'github',      'github', 'https://github.com',         'token',             NULL, NULL, '演示：公开仓库，未配置 Token', 1),
  (2, 'harbor-demo', 'harbor', 'https://harbor.example.com', 'username_password', NULL, NULL, '演示占位：换成自己的镜像仓库并关联钥匙串后启用', 0);

-- 示例服务：指向公开的示例仓库
INSERT IGNORE INTO `service` (`id`, `project_id`, `name`, `code`, `git_repo`, `component_id`, `git_repo_path`, `git_type`, `default_branch`, `service_type`, `service_port`, `dockerfile_path`, `build_command`, `owner_user_id`, `status`) VALUES
  (1, 1, 'shop-server', 'shop-server', 'https://github.com/yangzongzhuan/RuoYi-Vue',  1, 'yangzongzhuan/RuoYi-Vue',  'branch', 'springboot2', 'backend',  8080, 'Dockerfile', NULL, 4, 1),
  (2, 1, 'shop-ui',     'shop-ui',     'https://github.com/yangzongzhuan/RuoYi-Vue3', 1, 'yangzongzhuan/RuoYi-Vue3', 'branch', 'master',      'frontend', 80,   'Dockerfile', 'npm install && npm run build:prod', 4, 1),
  (3, 1, 'opsflow',     'opsflow',     'https://github.com/jiaowc/OpsFlow.git',       1, 'jiaowc/OpsFlow',           'branch', 'main',        'backend',  8080, 'Dockerfile', NULL, 4, 1);

-- 流水线视图：每个环境一个，授权给全部内置角色（1 管理员 / 2 开发者 / 3 审批人 / 4 只读 / 6 运维）
INSERT IGNORE INTO `pipeline_view` (`id`, `name`, `env_id`, `description`, `sort_order`, `status`) VALUES
  (1, 'dev',  1, 'dev 环境的流水线任务',  0, 1),
  (2, 'test', 2, 'test 环境的流水线任务', 1, 1),
  (3, 'prod', 3, 'prod 环境的流水线任务', 2, 1);

INSERT IGNORE INTO `pipeline_view_role` (`view_id`, `role_id`) VALUES
  (1, 1), (1, 2), (1, 3), (1, 4), (1, 6),
  (2, 1), (2, 2), (2, 3), (2, 4), (2, 6),
  (3, 1), (3, 2), (3, 3), (3, 4), (3, 6);
