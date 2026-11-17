USE opsflow;
SET NAMES utf8mb4;

-- 通用后端 Deployment
UPDATE `pipeline_template` SET
  `name` = '通用服务 Deployment 模版',
  `description` = '通用 Deployment：TCP 启停探针、可选 ConfigMap/Secret、无 hostPath；就绪 HTTP 探针按项目取消注释',
  `content` = 'apiVersion: apps/v1
kind: Deployment
metadata:
  name: ${serviceName}
  namespace: ${namespace}
  labels:
    app: ${serviceName}
    env: ${env}
spec:
  replicas: 1
  revisionHistoryLimit: 5
  selector:
    matchLabels:
      app: ${serviceName}
  strategy:
    type: RollingUpdate
    rollingUpdate:
      maxUnavailable: 0
      maxSurge: 1
  template:
    metadata:
      labels:
        app: ${serviceName}
        env: ${env}
    spec:
      restartPolicy: Always
      # 私有仓库拉取密钥（集群里需预先创建同名 Secret）
      imagePullSecrets:
      - name: harbor-registry-secret
      containers:
      - name: ${serviceName}
        image: ${image}
        imagePullPolicy: IfNotPresent
        ports:
        - name: http
          containerPort: ${servicePort}
        env:
        - name: TZ
          value: Asia/Shanghai
        # 可选：共享/按服务的配置与密钥（不存在也不影响部署）
        envFrom:
        - configMapRef:
            name: shared-config
            optional: true
        - secretRef:
            name: shared-secret
            optional: true
        - configMapRef:
            name: ${serviceName}-config
            optional: true
        - secretRef:
            name: ${serviceName}-secret
            optional: true
        # 启动探针：只探端口，避免预热期被误杀（约 10+5*36=190s）
        startupProbe:
          tcpSocket:
            port: ${servicePort}
          initialDelaySeconds: 10
          periodSeconds: 5
          timeoutSeconds: 2
          failureThreshold: 36
        # 存活探针：进程端口存活即可
        livenessProbe:
          tcpSocket:
            port: ${servicePort}
          periodSeconds: 10
          timeoutSeconds: 3
          failureThreshold: 3
        # 就绪探针：按项目打开并改 path
        # Spring Boot 常见: /actuator/health ；自定义常见: /api/health
        # readinessProbe:
        #   httpGet:
        #     path: /actuator/health
        #     port: ${servicePort}
        #     scheme: HTTP
        #   initialDelaySeconds: 10
        #   periodSeconds: 5
        #   timeoutSeconds: 2
        #   failureThreshold: 3
        #   successThreshold: 1
        resources:
          requests:
            cpu: "100m"
            memory: "256Mi"
          limits:
            cpu: "1000m"
            memory: "1Gi"
',
  `update_time` = NOW()
WHERE `id` = 2 OR (`type` = 'deployment' AND `name` IN ('java服务Deployment 模版', '通用服务 Deployment 模版'));

-- 通用前端 Deployment（更轻资源）
UPDATE `pipeline_template` SET
  `name` = '通用前端 Deployment 模版',
  `description` = '通用前端/静态站点 Deployment：TCP 探针、轻量资源、无 hostPath',
  `content` = 'apiVersion: apps/v1
kind: Deployment
metadata:
  name: ${serviceName}
  namespace: ${namespace}
  labels:
    app: ${serviceName}
    env: ${env}
spec:
  replicas: 1
  revisionHistoryLimit: 5
  selector:
    matchLabels:
      app: ${serviceName}
  strategy:
    type: RollingUpdate
    rollingUpdate:
      maxUnavailable: 0
      maxSurge: 1
  template:
    metadata:
      labels:
        app: ${serviceName}
        env: ${env}
    spec:
      restartPolicy: Always
      imagePullSecrets:
      - name: harbor-registry-secret
      containers:
      - name: ${serviceName}
        image: ${image}
        imagePullPolicy: IfNotPresent
        ports:
        - name: http
          containerPort: ${servicePort}
        env:
        - name: TZ
          value: Asia/Shanghai
        envFrom:
        - configMapRef:
            name: shared-config
            optional: true
        - configMapRef:
            name: ${serviceName}-config
            optional: true
        startupProbe:
          tcpSocket:
            port: ${servicePort}
          initialDelaySeconds: 5
          periodSeconds: 5
          timeoutSeconds: 2
          failureThreshold: 12
        livenessProbe:
          tcpSocket:
            port: ${servicePort}
          periodSeconds: 10
          timeoutSeconds: 3
          failureThreshold: 3
        resources:
          requests:
            cpu: "50m"
            memory: "64Mi"
          limits:
            cpu: "500m"
            memory: "256Mi"
',
  `update_time` = NOW()
WHERE `id` = 6 OR (`type` = 'deployment' AND `name` IN ('前端部署模版', '通用前端 Deployment 模版'));

-- Service 模版略作对齐（增加 env 标签）
UPDATE `pipeline_template` SET
  `description` = '通用 Service：端口与容器端口一致，类型由模版下拉注入',
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
  type: ${serviceType}
  selector:
    app: ${serviceName}
  ports:
  - name: http
    port: ${servicePort}
    targetPort: ${servicePort}
',
  `update_time` = NOW()
WHERE `id` = 3 OR (`type` = 'service' AND `name` = 'service');

-- 渲染步骤内嵌模版与上面保持一致
UPDATE `pipeline_step_def` SET
  `description` = '渲染通用 Deployment / Service 清单',
  `content_config` = '{"deploymentTemplateId": "2", "serviceTemplateId": "3", "serviceType": "ClusterIP", "outputDir": "manifests", "deploymentTemplateContent": "apiVersion: apps/v1\\nkind: Deployment\\nmetadata:\\n  name: ${serviceName}\\n  namespace: ${namespace}\\n  labels:\\n    app: ${serviceName}\\n    env: ${env}\\nspec:\\n  replicas: 1\\n  revisionHistoryLimit: 5\\n  selector:\\n    matchLabels:\\n      app: ${serviceName}\\n  strategy:\\n    type: RollingUpdate\\n    rollingUpdate:\\n      maxUnavailable: 0\\n      maxSurge: 1\\n  template:\\n    metadata:\\n      labels:\\n        app: ${serviceName}\\n        env: ${env}\\n    spec:\\n      restartPolicy: Always\\n      # 私有仓库拉取密钥（集群里需预先创建同名 Secret）\\n      imagePullSecrets:\\n      - name: harbor-registry-secret\\n      containers:\\n      - name: ${serviceName}\\n        image: ${image}\\n        imagePullPolicy: IfNotPresent\\n        ports:\\n        - name: http\\n          containerPort: ${servicePort}\\n        env:\\n        - name: TZ\\n          value: Asia/Shanghai\\n        # 可选：共享/按服务的配置与密钥（不存在也不影响部署）\\n        envFrom:\\n        - configMapRef:\\n            name: shared-config\\n            optional: true\\n        - secretRef:\\n            name: shared-secret\\n            optional: true\\n        - configMapRef:\\n            name: ${serviceName}-config\\n            optional: true\\n        - secretRef:\\n            name: ${serviceName}-secret\\n            optional: true\\n        # 启动探针：只探端口，避免预热期被误杀（约 10+5*36=190s）\\n        startupProbe:\\n          tcpSocket:\\n            port: ${servicePort}\\n          initialDelaySeconds: 10\\n          periodSeconds: 5\\n          timeoutSeconds: 2\\n          failureThreshold: 36\\n        # 存活探针：进程端口存活即可\\n        livenessProbe:\\n          tcpSocket:\\n            port: ${servicePort}\\n          periodSeconds: 10\\n          timeoutSeconds: 3\\n          failureThreshold: 3\\n        # 就绪探针：按项目打开并改 path\\n        # Spring Boot 常见: /actuator/health ；自定义常见: /api/health\\n        # readinessProbe:\\n        #   httpGet:\\n        #     path: /actuator/health\\n        #     port: ${servicePort}\\n        #     scheme: HTTP\\n        #   initialDelaySeconds: 10\\n        #   periodSeconds: 5\\n        #   timeoutSeconds: 2\\n        #   failureThreshold: 3\\n        #   successThreshold: 1\\n        resources:\\n          requests:\\n            cpu: \\"100m\\"\\n            memory: \\"256Mi\\"\\n          limits:\\n            cpu: \\"1000m\\"\\n            memory: \\"1Gi\\"\\n", "serviceTemplateContent": "apiVersion: v1\\nkind: Service\\nmetadata:\\n  name: ${serviceName}-svc\\n  namespace: ${namespace}\\n  labels:\\n    app: ${serviceName}\\n    env: ${env}\\nspec:\\n  # ClusterIP / NodePort / LoadBalancer（由模版「服务类型」下拉注入）\\n  type: ${serviceType}\\n  selector:\\n    app: ${serviceName}\\n  ports:\\n  - name: http\\n    port: ${servicePort}\\n    targetPort: ${servicePort}\\n"}',
  `update_time` = NOW()
WHERE `id` = 9 OR (`step_type` = 'render_template');

