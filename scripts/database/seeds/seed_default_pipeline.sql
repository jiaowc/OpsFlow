-- 修复 Pipeline 表字符集并写入默认模板
ALTER TABLE `pipeline` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

DELETE FROM `pipeline`;

INSERT INTO `pipeline` (`name`, `description`, `steps_config`, `status`) VALUES
(
  '标准构建发布流水线',
  '拉取代码 → 代码构建 → 镜像制作 → 上传镜像 → 开始部署 → 检查部署状态',
  '[{"stepType":"checkout","stepName":"拉取代码","enabled":true,"order":1},{"stepType":"build","stepName":"代码构建","enabled":true,"order":2},{"stepType":"docker_build","stepName":"镜像制作","enabled":true,"order":3},{"stepType":"push_image","stepName":"上传镜像","enabled":true,"order":4},{"stepType":"deploy","stepName":"开始部署","enabled":true,"order":5},{"stepType":"check_deploy","stepName":"检查部署状态","enabled":true,"order":6}]',
  1
);
