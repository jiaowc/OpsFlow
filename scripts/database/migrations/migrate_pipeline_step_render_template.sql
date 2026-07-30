-- 增量添加：渲染模版步骤（可重复执行）
SET NAMES utf8mb4;

INSERT INTO `pipeline_step_def` (`name`, `step_type`, `phase`, `description`, `content_config`, `status`)
SELECT '渲染模版',
       'render_template',
       'cd',
       '根据 Deployment / Service 模版渲染 K8s 清单，支持 ${namespace}、${image}、${deployment} 等变量',
       '{"outputDir":"manifests","deploymentTemplateContent":"apiVersion: apps/v1\\nkind: Deployment\\nmetadata:\\n  name: ${deployment}\\n  namespace: ${namespace}\\nspec:\\n  replicas: 1\\n  selector:\\n    matchLabels:\\n      app: ${serviceName}\\n  template:\\n    metadata:\\n      labels:\\n        app: ${serviceName}\\n    spec:\\n      containers:\\n        - name: ${serviceName}\\n          image: ${image}\\n          ports:\\n            - containerPort: ${servicePort}"}',
       1
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM `pipeline_step_def` WHERE `step_type` = 'render_template');
