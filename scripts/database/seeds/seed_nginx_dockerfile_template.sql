-- Nginx 前端 Dockerfile 模版（幂等：同名同类型已存在则跳过）
INSERT INTO `pipeline_template`
(`name`, `type`, `description`, `content`, `base_image`, `service_type`, `status`)
SELECT
  'Nginx 前端静态资源',
  'dockerfile',
  '适用于前端/静态站点：将构建产物拷贝到 nginx 目录并对外暴露端口',
  'FROM nginx:1.25-alpine\nWORKDIR /usr/share/nginx/html\n# 按实际构建产物目录调整（如 dist / build / public）\nCOPY dist/ ./\nEXPOSE ${servicePort}\nCMD [\"nginx\", \"-g\", \"daemon off;\"]',
  'nginx:1.25-alpine',
  NULL,
  1
FROM DUAL
WHERE NOT EXISTS (
  SELECT 1 FROM `pipeline_template`
  WHERE `type` = 'dockerfile' AND `name` = 'Nginx 前端静态资源'
);
