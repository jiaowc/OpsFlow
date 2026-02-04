package com.opsflow.integration.k8s;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.opsflow.dao.mapper.ComponentMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
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
            
            // 解析authConfig JSON
            java.util.Map<String, String> authConfig = null;
            if (component.getAuthConfig() != null && !component.getAuthConfig().trim().isEmpty()) {
                try {
                    authConfig = objectMapper.readValue(
                        component.getAuthConfig(),
                        new TypeReference<java.util.Map<String, String>>() {}
                    );
                } catch (Exception e) {
                    log.warn("K8s组件认证配置格式错误，将使用默认配置", e);
                }
            }
            
            // 从authConfig中获取config-file路径
            if (authConfig != null && authConfig.containsKey("configFile")) {
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
     * 回滚Deployment
     */
    public void rollbackDeployment(String namespace, String deploymentName) {
        try {
            AppsV1Api api = getAppsV1Api();
            // 获取当前Deployment信息
            api.readNamespacedDeployment(deploymentName, namespace, null);
            
            // 获取当前revision，回滚到上一个版本
            // 注意：K8s API没有直接的rollback方法，需要通过DeploymentRollback资源实现
            // 这里简化处理，实际应该调用AppsV1Api的createNamespacedDeploymentRollback方法
            // 或者使用kubectl命令：kubectl rollout undo deployment/{deploymentName} -n {namespace}
            
            log.info("回滚Deployment: {}/{}", namespace, deploymentName);
            log.warn("回滚功能需要实现DeploymentRollback API调用");
            
        } catch (ApiException e) {
            log.error("回滚Deployment失败", e);
            throw new RuntimeException("回滚Deployment失败: " + e.getMessage(), e);
        }
    }
}

