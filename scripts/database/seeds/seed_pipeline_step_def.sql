-- 默认步骤定义（可被 Pipeline 模板引用）
SET NAMES utf8mb4;

ALTER TABLE `pipeline_step_def` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

DELETE FROM `pipeline_step_def`;

INSERT INTO `pipeline_step_def` (`id`, `name`, `step_type`, `phase`, `description`, `content_config`, `status`) VALUES
(1, '拉取代码', 'checkout', 'ci', '从 Git 仓库拉取代码', '{}', 1),
(2, '代码构建', 'build', 'ci', 'Maven/Gradle 构建', '{"buildCommand":"mvn clean package -DskipTests"}', 1),
(3, '镜像制作', 'docker_build', 'ci', 'Docker 镜像构建', '{"dockerfilePath":"Dockerfile","dockerfileContent":"FROM openjdk:8-jre-slim\\nWORKDIR /app\\nCOPY target/*.jar app.jar\\nEXPOSE ${servicePort}\\nCMD [\\"java\\", \\"-jar\\", \\"app.jar\\"]"}', 1),
(4, '上传镜像', 'push_image', 'ci', '推送镜像到 Harbor', '{}', 1),
(5, '开始部署', 'deploy', 'cd', '更新 K8s 部署', '{}', 1),
(6, '检查部署状态', 'check_deploy', 'cd', '等待部署滚动完成', '{"timeoutSeconds":"300"}', 1),
(7, '清理空间', 'clean_workspace', 'ci', '清理构建节点工作目录，防止历史代码或缓存影响构建', '{"workspaceBase":"~/opsflow","cleanDocker":"false"}', 1),
(8, '回滚部署', 'rollback', 'cd', '将 K8s Deployment 回滚到上一版本或指定 revision', '{"waitRollout":"true","timeoutSeconds":"300"}', 1),
(9, '渲染模版', 'render_template', 'cd', '根据 Deployment / Service 模版渲染 K8s 清单', '{"outputDir":"manifests"}', 1);

-- 将默认 Pipeline 模板改为引用步骤定义
UPDATE `pipeline` SET `steps_config` = '[{"stepTemplateId":1,"order":1,"enabled":true},{"stepTemplateId":2,"order":2,"enabled":true},{"stepTemplateId":3,"order":3,"enabled":true},{"stepTemplateId":4,"order":4,"enabled":true},{"stepTemplateId":5,"order":5,"enabled":true},{"stepTemplateId":6,"order":6,"enabled":true}]'
WHERE `name` = '标准构建发布流水线';
