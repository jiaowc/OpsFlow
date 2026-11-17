USE opsflow;
SET NAMES utf8mb4;

-- 默认 Dockerfile 模版优化（通用后端 / 通用前端），并统一默认模版命名：
--   通用{后端|前端} {Dockerfile|Deployment|Service} 模版
-- 可重复执行：按 id 或名称定位，沿用原 id，已引用这些模版的流水线不受影响
-- 注意：会覆盖这两份默认 Dockerfile 模版上的手工修改；自定义需求请另建模版
-- 模版内容里的反斜杠、单引号已按 SQL 字符串规则转义（\\ 和 ''），入库后是正常的 Dockerfile

-- 1) 通用后端 Dockerfile（原「java镜像构建模版」）
UPDATE `pipeline_template` SET
  `name` = '通用后端 Dockerfile 模版',
  `description` = '通用 Java 运行镜像：构建产物约定为 target/app.jar；默认 JDK8，换版本只改 FROM；JAVA_OPTS 可由 Deployment 覆盖，支持优雅停机和启动参数',
  `base_image` = 'eclipse-temurin:8-jre',
  `content` = '# 通用后端（Java）运行镜像：构建产物约定为 target/app.jar（单模块/多模块均可，由「后端构建」步骤规范产出）
# 换 JDK 版本只改 FROM：eclipse-temurin:11-jre / 17-jre / 21-jre
FROM eclipse-temurin:8-jre

WORKDIR /app

# JAVA_OPTS 可在 Deployment 的 env 里整体覆盖；堆大小按容器内存限制的百分比自动计算
ENV TZ=Asia/Shanghai \\
    LANG=C.UTF-8 \\
    JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError -Dfile.encoding=UTF-8 -Djava.security.egd=file:/dev/./urandom"

COPY target/app.jar app.jar

# 可选：以非 root 运行（应用写日志/上传文件的目录需提前创建并授权）
# RUN useradd -r -u 1000 -m app && chown -R app:app /app
# USER app

EXPOSE ${servicePort}

# exec 让 java 成为 1 号进程，能收到 K8s 的 SIGTERM 优雅停机；Deployment 的 args 会作为程序参数追加
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar \\"$@\\"", "--"]
',
  `update_time` = NOW()
WHERE `type` = 'dockerfile' AND (`id` = 1 OR `name` IN ('java镜像构建模版', '通用后端 Dockerfile 模版'));

-- 2) 通用前端 Dockerfile（原「Nginx 前端静态资源」）
UPDATE `pipeline_template` SET
  `name` = '通用前端 Dockerfile 模版',
  `description` = '通用前端/静态站点镜像：nginx 托管构建产物（默认 dist/），SPA 路由回退、gzip、静态资源缓存、/healthz；监听端口取服务端口；接口反代默认注释',
  `base_image` = 'nginx:1.30-alpine',
  `content` = '# 通用前端 / 静态站点镜像：nginx 托管构建产物，支持 SPA 路由回退
FROM nginx:1.30-alpine

ENV TZ=Asia/Shanghai

# nginx 站点配置：监听端口与服务管理的「对外端口」一致
# 注释掉的行是可选的接口反代，需要时去掉行首的 # 并改成实际的后端 Service 地址
RUN printf ''%s\\n'' \\
    ''server {'' \\
    ''    listen ${servicePort};'' \\
    ''    server_name _;'' \\
    ''    server_tokens off;'' \\
    ''    root /usr/share/nginx/html;'' \\
    ''    index index.html;'' \\
    ''    gzip on;'' \\
    ''    gzip_min_length 1k;'' \\
    ''    gzip_types text/plain text/css application/javascript application/json image/svg+xml;'' \\
    ''    gzip_static on;'' \\
    ''    location = /healthz {'' \\
    ''        access_log off;'' \\
    ''        return 204;'' \\
    ''    }'' \\
    ''    location / {'' \\
    ''        try_files $uri $uri/ /index.html;'' \\
    ''    }'' \\
    ''    location = /index.html {'' \\
    ''        add_header Cache-Control "no-cache";'' \\
    ''    }'' \\
    ''    location ~* \\.(js|css|png|jpg|jpeg|gif|svg|ico|woff|woff2|ttf|eot|map)$ {'' \\
    ''        expires 7d;'' \\
    ''        try_files $uri =404;'' \\
    ''    }'' \\
#    ''    location /prod-api/ {'' \\
#    ''        proxy_set_header Host $http_host;'' \\
#    ''        proxy_set_header X-Real-IP $remote_addr;'' \\
#    ''        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;'' \\
#    ''        proxy_pass http://backend-svc:8080/;'' \\
#    ''    }'' \\
    ''}'' > /etc/nginx/conf.d/default.conf

WORKDIR /usr/share/nginx/html

# 按实际构建产物目录调整（如 dist / build / public）
COPY dist/ ./

EXPOSE ${servicePort}

CMD ["nginx", "-g", "daemon off;"]
',
  `update_time` = NOW()
WHERE `type` = 'dockerfile' AND (`id` = 4 OR `name` IN ('Nginx 前端静态资源', '通用前端 Dockerfile 模版'));

-- 3) 命名统一：通用服务 Deployment 模版 -> 通用后端 Deployment 模版（内容不变）
UPDATE `pipeline_template` SET
  `name` = '通用后端 Deployment 模版',
  `update_time` = NOW()
WHERE `type` = 'deployment' AND (`id` = 2 OR `name` = '通用服务 Deployment 模版')
  AND `name` <> '通用后端 Deployment 模版';

-- 4) 镜像制作步骤内嵌的 Dockerfile 正文与模版管理保持一致
--    （运行时按 dockerfileTemplateId 取最新内容，内嵌正文只是缓存）
UPDATE `pipeline_step_def` d
JOIN `pipeline_template` t ON t.`type` = 'dockerfile' AND t.`name` = '通用后端 Dockerfile 模版'
SET d.`content_config` = JSON_SET(d.`content_config`,
      '$.dockerfileTemplateId', CAST(t.`id` AS CHAR),
      '$.dockerfileContent', t.`content`),
    d.`update_time` = NOW()
WHERE d.`step_type` = 'docker_build'
  AND (d.`id` = 3 OR d.`name` = '后端镜像制作')
  AND JSON_VALID(d.`content_config`);

-- 前端镜像制作原超时 60 秒，首次拉取基础镜像容易超时，调整为与后端一致的 600 秒
UPDATE `pipeline_step_def` d
JOIN `pipeline_template` t ON t.`type` = 'dockerfile' AND t.`name` = '通用前端 Dockerfile 模版'
SET d.`content_config` = JSON_SET(d.`content_config`,
      '$.dockerfileTemplateId', CAST(t.`id` AS CHAR),
      '$.dockerfileContent', t.`content`),
    d.`timeout_seconds` = GREATEST(IFNULL(d.`timeout_seconds`, 0), 600),
    d.`update_time` = NOW()
WHERE d.`step_type` = 'docker_build'
  AND (d.`id` = 10 OR d.`name` = '前端镜像制作')
  AND JSON_VALID(d.`content_config`);

-- 核对
SELECT `id`, `name`, `type`, `base_image`, `service_type`, `status`, CHAR_LENGTH(`content`) AS content_len
FROM `pipeline_template` ORDER BY `type`, `id`;

SELECT d.`id`, d.`name`, d.`timeout_seconds`,
       JSON_UNQUOTE(JSON_EXTRACT(d.`content_config`, '$.dockerfileTemplateId')) AS dockerfile_tpl,
       JSON_UNQUOTE(JSON_EXTRACT(d.`content_config`, '$.dockerfileContent')) = t.`content` AS content_same
FROM `pipeline_step_def` d
LEFT JOIN `pipeline_template` t
  ON t.`id` = CAST(JSON_UNQUOTE(JSON_EXTRACT(d.`content_config`, '$.dockerfileTemplateId')) AS UNSIGNED)
WHERE d.`step_type` = 'docker_build' ORDER BY d.`id`;
