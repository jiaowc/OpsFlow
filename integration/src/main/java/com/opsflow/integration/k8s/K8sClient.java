package com.opsflow.integration.k8s;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.opsflow.dao.mapper.ComponentMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opsflow.integration.credential.ComponentAuthResolver;
import com.opsflow.integration.credential.ResolvedAuth;
import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.openapi.apis.AppsV1Api;
import io.kubernetes.client.openapi.models.V1Deployment;
import io.kubernetes.client.util.Config;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Kubernetes客户端
 */
@Slf4j
@Component
public class K8sClient {

    @Autowired
    private ComponentMapper componentMapper;

    @Autowired
    private ComponentAuthResolver componentAuthResolver;
    
    private ObjectMapper objectMapper = new ObjectMapper();
    
    private String k8sConfigFile;
    private ApiClient apiClient;
    private AppsV1Api appsV1Api;
    
    /**
     * 初始化或获取AppsV1Api（懒加载）
     */
    private AppsV1Api getAppsV1Api() {
        if (appsV1Api == null) {
            synchronized (this) {
                if (appsV1Api == null) {
                    init();
                }
            }
        }
        return appsV1Api;
    }
    
    /**
     * 从组件管理加载配置并初始化
     */
    private void init() {
        try {
            QueryWrapper<com.opsflow.dao.model.Component> wrapper = new QueryWrapper<>();
            wrapper.eq("type", "k8s");
            wrapper.eq("status", 1);
            wrapper.orderByDesc("create_time");
            wrapper.last("LIMIT 1");
            
            com.opsflow.dao.model.Component component = componentMapper.selectOne(wrapper);
            if (component == null) {
                log.warn("未找到启用的K8s组件配置，将使用默认K8s配置");
                // K8s配置可选，如果没有配置则使用默认配置
                apiClient = Config.defaultClient();
                appsV1Api = new AppsV1Api(apiClient);
                log.info("K8s客户端使用默认配置初始化成功");
                return;
            }
            
            // 解析认证配置（支持钥匙串）
            ResolvedAuth resolvedAuth = componentAuthResolver.resolve(component);
            java.util.Map<String, String> authConfig = resolvedAuth.getAuthConfig();
            
            if (authConfig != null && authConfig.containsKey("configContent")
                    && authConfig.get("configContent") != null
                    && !authConfig.get("configContent").trim().isEmpty()) {
                java.nio.file.Path tempFile = java.nio.file.Files.createTempFile("opsflow-kubeconfig-", ".yaml");
                java.nio.file.Files.write(tempFile, authConfig.get("configContent").getBytes(java.nio.charset.StandardCharsets.UTF_8));
                tempFile.toFile().deleteOnExit();
                k8sConfigFile = tempFile.toString();
            } else if (authConfig != null && authConfig.containsKey("configFile")) {
                k8sConfigFile = authConfig.get("configFile");
            } else if (component.getUrl() != null && !component.getUrl().trim().isEmpty()) {
                // 如果没有configFile，尝试使用url作为config-file路径
                k8sConfigFile = component.getUrl();
            }
            
            if (k8sConfigFile != null && !k8sConfigFile.isEmpty() && !k8sConfigFile.equals("/path/to/kubeconfig")) {
                java.io.File configFile = new java.io.File(k8sConfigFile);
                if (configFile.exists()) {
                    apiClient = Config.fromConfig(k8sConfigFile);
                    log.info("使用指定的K8s配置文件: {}", k8sConfigFile);
                } else {
                    log.warn("K8s配置文件不存在: {}，使用默认配置", k8sConfigFile);
                    apiClient = Config.defaultClient();
                }
            } else {
                apiClient = Config.defaultClient();
                log.info("使用默认K8s配置");
            }
            
            appsV1Api = new AppsV1Api(apiClient);
            log.info("K8s客户端初始化成功");
        } catch (IOException e) {
            log.warn("K8s客户端初始化失败，将使用默认配置: {}", e.getMessage());
            try {
                apiClient = Config.defaultClient();
                appsV1Api = new AppsV1Api(apiClient);
                log.info("K8s客户端使用默认配置初始化成功");
            } catch (IOException ex) {
                log.error("K8s客户端初始化完全失败", ex);
                // 不抛出异常，允许应用启动，K8s功能将不可用
            }
        }
    }
    
    /**
     * 更新Deployment的镜像
     */
    @SuppressWarnings("null")
    public void updateDeploymentImage(String namespace, String deploymentName, String containerName, String image) {
        try {
            AppsV1Api api = getAppsV1Api();
            V1Deployment deployment = api.readNamespacedDeployment(deploymentName, namespace, null);
            
            // 检查 deployment 和 spec 是否存在
            if (deployment == null || deployment.getSpec() == null || 
                deployment.getSpec().getTemplate() == null || 
                deployment.getSpec().getTemplate().getSpec() == null) {
                throw new RuntimeException("Deployment 配置不完整: " + namespace + "/" + deploymentName);
            }
            
            // 使用局部变量保存已检查的值
            io.kubernetes.client.openapi.models.V1DeploymentSpec spec = deployment.getSpec();
            io.kubernetes.client.openapi.models.V1PodSpec templateSpec = spec.getTemplate().getSpec();
            
            // 更新镜像
            templateSpec.getContainers().stream()
                .filter(container -> container.getName().equals(containerName))
                .forEach(container -> container.setImage(image));
            
            // 应用更新
            String pretty = null;
            String dryRun = null;
            String fieldManager = null;
            String fieldValidation = null;
            api.replaceNamespacedDeployment(
                deploymentName, 
                namespace, 
                deployment, 
                pretty,
                dryRun,
                fieldManager,
                fieldValidation
            );
            
            log.info("更新Deployment镜像成功: {}/{} -> {}", namespace, deploymentName, image);
        } catch (ApiException e) {
            log.error("更新Deployment镜像失败", e);
            throw new RuntimeException("更新Deployment镜像失败: " + e.getMessage(), e);
        }
    }
    
    /**
     * 等待Deployment滚动更新完成
     */
    @SuppressWarnings("null")
    public void waitForDeploymentRollout(String namespace, String deploymentName, int timeoutSeconds) {
        try {
            AppsV1Api api = getAppsV1Api();
            long startTime = System.currentTimeMillis();
            long timeoutMillis = timeoutSeconds * 1000L;
            
            while (System.currentTimeMillis() - startTime < timeoutMillis) {
                V1Deployment deployment = api.readNamespacedDeployment(deploymentName, namespace, null);
                
                if (deployment != null && deployment.getStatus() != null && deployment.getSpec() != null) {
                    io.kubernetes.client.openapi.models.V1DeploymentStatus status = deployment.getStatus();
                    io.kubernetes.client.openapi.models.V1DeploymentSpec spec = deployment.getSpec();
                    
                    Integer readyReplicas = status.getReadyReplicas();
                    Integer updatedReplicas = status.getUpdatedReplicas();
                    Integer desiredReplicas = spec.getReplicas();
                    
                    if (readyReplicas != null && updatedReplicas != null && desiredReplicas != null &&
                        readyReplicas.equals(desiredReplicas) && 
                        updatedReplicas.equals(desiredReplicas)) {
                        log.info("Deployment滚动更新完成: {}/{}", namespace, deploymentName);
                        return;
                    }
                }
                
                // 等待1秒后重试
                Thread.sleep(1000);
            }
            
            throw new RuntimeException("等待Deployment滚动更新超时: " + namespace + "/" + deploymentName);
        } catch (ApiException e) {
            log.error("等待Deployment滚动更新失败", e);
            throw new RuntimeException("等待Deployment滚动更新失败: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("等待Deployment滚动更新被中断", e);
        }
    }
    
    /**
     * 回滚 Deployment 到上一版本或指定 revision
     */
    public String rollbackDeployment(String namespace, String deploymentName, Integer toRevision) {
        try {
            getAppsV1Api();

            java.util.List<String> command = new java.util.ArrayList<>();
            command.add("kubectl");
            if (k8sConfigFile != null && !k8sConfigFile.isEmpty()) {
                java.io.File configFile = new java.io.File(k8sConfigFile);
                if (configFile.exists()) {
                    command.add("--kubeconfig");
                    command.add(k8sConfigFile);
                }
            }
            command.add("rollout");
            command.add("undo");
            command.add("deployment/" + deploymentName);
            command.add("-n");
            command.add(namespace);
            if (toRevision != null && toRevision > 0) {
                command.add("--to-revision=" + toRevision);
            }

            ProcessBuilder processBuilder = new ProcessBuilder(command);
            processBuilder.redirectErrorStream(true);
            Process process = processBuilder.start();

            StringBuilder output = new StringBuilder();
            try (java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append('\n');
                }
            }

            int exitCode = process.waitFor();
            String logText = output.toString().trim();
            if (exitCode != 0) {
                throw new RuntimeException(logText.isEmpty() ? "kubectl rollout undo 执行失败" : logText);
            }

            String revisionInfo = toRevision != null ? ("revision " + toRevision) : "上一版本";
            String message = String.format("已回滚 Deployment %s/%s 到 %s", namespace, deploymentName, revisionInfo);
            if (!logText.isEmpty()) {
                message += "\n" + logText;
            }
            log.info(message);
            return message;
        } catch (Exception e) {
            log.error("回滚Deployment失败", e);
            throw new RuntimeException("回滚Deployment失败: " + e.getMessage(), e);
        }
    }

    /**
     * 回滚 Deployment（默认回滚到上一版本）
     */
    public void rollbackDeployment(String namespace, String deploymentName) {
        rollbackDeployment(namespace, deploymentName, null);
    }
}

