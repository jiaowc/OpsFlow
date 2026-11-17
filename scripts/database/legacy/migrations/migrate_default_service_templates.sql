USE opsflow;
SET NAMES utf8mb4;

-- 默认 Service 模版拆成两份：通用后端 / 通用前端（与 Dockerfile、Deployment 模版成对）
-- 可重复执行：后端模版按 id=3 或名称更新；前端模版按名称判断，已存在则只更新内容
-- 注意：会覆盖这两份默认模版上的手工修改；自定义需求请另建模版

-- 1) 通用后端 Service（沿用原 id=3 的 service 模版，已引用它的流水线不受影响）
UPDATE `pipeline_template` SET
  `name` = '通用后端 Service 模版',
  `description` = '通用后端 Service：端口与容器端口一致，类型由模版下拉注入；可选 Prometheus 注解、额外端口（默认注释）',
  `service_type` = 'ClusterIP',
  `content` = 'apiVersion: v1
kind: Service
metadata:
  name: ${serviceName}-svc
  namespace: ${namespace}
  labels:
    app: ${serviceName}
    env: ${env}
  # 可选：Prometheus 按注解抓取指标时打开（Spring Boot 需引入 actuator + micrometer-registry-prometheus）
  # annotations:
  #   prometheus.io/scrape: "true"
  #   prometheus.io/port: "${servicePort}"
  #   prometheus.io/path: /actuator/prometheus
spec:
  # ClusterIP / NodePort / LoadBalancer（由模版「服务类型」下拉注入）
  # 后端服务建议 ClusterIP，对外流量经 Ingress / 网关进入
  type: ${serviceType}
  selector:
    app: ${serviceName}
  ports:
  - name: http
    # 对外端口与容器端口一致，取自服务管理的「对外端口」
    port: ${servicePort}
    targetPort: ${servicePort}
  # 可选：额外端口（独立的管理/监控端口、gRPC 等），容器需同时监听该端口
  # - name: management
  #   port: 8081
  #   targetPort: 8081
',
  `update_time` = NOW()
WHERE `type` = 'service' AND (`id` = 3 OR `name` IN ('service', '通用后端 Service 模版'));

-- 2) 通用前端 Service：不存在则新增
INSERT INTO `pipeline_template` (`name`, `type`, `description`, `content`, `service_type`, `status`, `create_time`, `update_time`)
SELECT '通用前端 Service 模版', 'service', '', '', 'ClusterIP', 1, NOW(), NOW()
FROM DUAL
WHERE NOT EXISTS (
  SELECT 1 FROM `pipeline_template` WHERE `type` = 'service' AND `name` = '通用前端 Service 模版'
);

UPDATE `pipeline_template` SET
  `description` = '通用前端/静态站点 Service：端口与容器端口一致（nginx 镜像一般为 80），类型由模版下拉注入',
  `service_type` = 'ClusterIP',
  `content` = 'apiVersion: v1
kind: Service
metadata:
  name: ${serviceName}-svc
  namespace: ${namespace}
  labels:
    app: ${serviceName}
    env: ${env}
spec:
  # ClusterIP / NodePort / LoadBalancer（由模版「服务类型」下拉注入）
  # 前端站点建议 ClusterIP，对外流量经 Ingress 进入；本地调试没有 Ingress 时可改 NodePort
  type: ${serviceType}
  selector:
    app: ${serviceName}
  ports:
  - name: http
    # 对外端口与容器端口一致，取自服务管理的「对外端口」（nginx 镜像一般为 80）
    port: ${servicePort}
    targetPort: ${servicePort}
',
  `update_time` = NOW()
WHERE `type` = 'service' AND `name` = '通用前端 Service 模版';

-- 3) 渲染步骤指向对应模版，内嵌的模版正文与模版管理保持一致
--    （运行时按 serviceTemplateId 取最新内容，内嵌正文只是缓存）
UPDATE `pipeline_step_def` d
JOIN `pipeline_template` t ON t.`type` = 'service' AND t.`name` = '通用后端 Service 模版'
SET d.`content_config` = JSON_SET(d.`content_config`,
      '$.serviceTemplateId', CAST(t.`id` AS CHAR),
      '$.serviceType', t.`service_type`,
      '$.serviceTemplateContent', t.`content`),
    d.`update_time` = NOW()
WHERE d.`step_type` = 'render_template'
  AND (d.`id` = 9 OR d.`name` = 'service渲染模版')
  AND JSON_VALID(d.`content_config`);

UPDATE `pipeline_step_def` d
JOIN `pipeline_template` t ON t.`type` = 'service' AND t.`name` = '通用前端 Service 模版'
SET d.`content_config` = JSON_SET(d.`content_config`,
      '$.serviceTemplateId', CAST(t.`id` AS CHAR),
      '$.serviceType', t.`service_type`,
      '$.serviceTemplateContent', t.`content`),
    d.`update_time` = NOW()
WHERE d.`step_type` = 'render_template'
  AND (d.`id` = 14 OR d.`name` = '前端渲染模版')
  AND JSON_VALID(d.`content_config`);

-- 核对
SELECT `id`, `name`, `type`, `service_type`, `status`, CHAR_LENGTH(`content`) AS content_len
FROM `pipeline_template` WHERE `type` = 'service' ORDER BY `id`;

SELECT `id`, `name`,
       JSON_UNQUOTE(JSON_EXTRACT(`content_config`, '$.deploymentTemplateId')) AS deployment_tpl,
       JSON_UNQUOTE(JSON_EXTRACT(`content_config`, '$.serviceTemplateId')) AS service_tpl,
       JSON_UNQUOTE(JSON_EXTRACT(`content_config`, '$.serviceType')) AS service_type
FROM `pipeline_step_def` WHERE `step_type` = 'render_template' ORDER BY `id`;
