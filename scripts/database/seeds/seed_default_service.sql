-- 默认示例服务（便于本地创建 Pipeline 任务）
INSERT INTO `service` (`name`, `code`, `git_repo`, `default_branch`, `service_type`, `service_port`, `k8s_deployment`, `k8s_namespace`, `dockerfile_path`, `build_command`, `status`)
SELECT '示例服务', 'demo-service', 'https://github.com/example/demo-service.git', 'develop', 'backend', 8080, 'demo-service', 'dev', 'Dockerfile', 'mvn clean package -DskipTests', 1
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM `service` LIMIT 1);
