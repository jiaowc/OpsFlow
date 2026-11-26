-- 流水线任务「下次构建用的分支/Tag」。
-- build_job 一行是一次构建，没有单独的任务配置行；以前编辑任务的分支时会把同组所有历史行的 branch 一起改掉，
-- 历史记录就看不出当时构建的是哪个分支。现在编辑只写组内最新一行的 next_branch / next_git_type，
-- 历史行的 branch / git_type 不再改动；重新构建、推送自动构建匹配、任务列表展示优先读这两列，为空时回退到 branch / git_type。

ALTER TABLE `build_job`
  ADD COLUMN `next_branch` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '下次构建使用的分支/Tag（编辑任务时写入，为空表示沿用 branch）' AFTER `git_type`,
  ADD COLUMN `next_git_type` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '下次构建使用的 Git 类型：branch/tag（为空表示沿用 git_type）' AFTER `next_branch`;
