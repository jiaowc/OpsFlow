package com.opsflow.integration.k8s;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.opsflow.dao.mapper.ClusterMapper;
import com.opsflow.dao.mapper.ComponentMapper;
import com.opsflow.dao.mapper.CredentialMapper;
import com.opsflow.dao.model.Cluster;
import com.opsflow.dao.model.Component;
import com.opsflow.dao.model.Credential;
import com.opsflow.integration.credential.ComponentAuthResolver;
import com.opsflow.integration.credential.ResolvedAuth;
import com.opsflow.integration.pipeline.NodeCommandHelper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;
import java.util.Map;

/**
 * 解析 K8s kubeconfig，优先集群关联钥匙串，其次 K8s 组件
 */
@Slf4j
@org.springframework.stereotype.Component
public class K8sCredentialService {

    @Autowired
    private ComponentMapper componentMapper;

    @Autowired
    private ClusterMapper clusterMapper;

    @Autowired
    private CredentialMapper credentialMapper;

    @Autowired
    private ComponentAuthResolver componentAuthResolver;

    public Component resolveComponent(Long componentId) {
        if (componentId != null) {
            Component component = componentMapper.selectById(componentId);
            if (component != null && component.getStatus() != null && component.getStatus() == 1) {
                return component;
            }
            log.warn("K8s 组件不存在或已禁用: componentId={}", componentId);
        }
        QueryWrapper<Component> wrapper = new QueryWrapper<>();
        wrapper.in("type", "k8s", "kubernetes");
        wrapper.eq("status", 1);
        wrapper.orderByDesc("create_time");
        wrapper.last("LIMIT 1");
        return componentMapper.selectOne(wrapper);
    }

    /**
     * 解析 kubeconfig 内容。
     * 优先级：集群关联钥匙串 &gt; 指定/默认 K8s 组件
     */
    public String resolveKubeconfigContent(Long componentId) {
        return resolveKubeconfigContent(null, componentId);
    }

    public String resolveKubeconfigContent(Long clusterId, Long componentId) {
        String fromCluster = resolveFromCluster(clusterId);
        if (fromCluster != null && !fromCluster.trim().isEmpty()) {
            return fromCluster;
        }
        return resolveFromComponent(componentId);
    }

    private String resolveFromCluster(Long clusterId) {
        if (clusterId == null) {
            return null;
        }
        Cluster cluster = clusterMapper.selectById(clusterId);
        if (cluster == null || cluster.getCredentialId() == null) {
            return null;
        }
        Credential credential = credentialMapper.selectById(cluster.getCredentialId());
        if (credential == null || credential.getStatus() == null || credential.getStatus() != 1) {
            log.warn("集群关联的钥匙串不存在或已禁用: clusterId={}, credentialId={}",
                    clusterId, cluster.getCredentialId());
            return null;
        }
        ResolvedAuth auth = componentAuthResolver.fromCredential(credential);
        return extractKubeconfig(auth.getAuthConfig());
    }

    private String resolveFromComponent(Long componentId) {
        Component component = resolveComponent(componentId);
        if (component == null) {
            return null;
        }
        ResolvedAuth auth = componentAuthResolver.resolve(component);
        Map<String, String> authConfig = auth.getAuthConfig();
        if (authConfig == null) {
            authConfig = Collections.emptyMap();
        }

        String content = extractKubeconfig(authConfig);
        if (content != null && !content.trim().isEmpty()) {
            return content;
        }

        String configFile = authConfig.get("configFile");
        if (configFile == null || configFile.trim().isEmpty()) {
            if (component.getUrl() != null && !component.getUrl().trim().isEmpty()
                    && !"/path/to/kubeconfig".equals(component.getUrl().trim())) {
                configFile = component.getUrl().trim();
            }
        }
        return readConfigFile(configFile);
    }

    private String extractKubeconfig(Map<String, String> authConfig) {
        if (authConfig == null || authConfig.isEmpty()) {
            return null;
        }
        String configContent = authConfig.get("configContent");
        if (configContent != null && !configContent.trim().isEmpty()) {
            return configContent;
        }
        return readConfigFile(authConfig.get("configFile"));
    }

    private String readConfigFile(String configFile) {
        if (configFile == null || configFile.trim().isEmpty()) {
            return null;
        }
        try {
            java.io.File file = new java.io.File(configFile.trim());
            if (file.exists()) {
                return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            }
            log.warn("K8s 配置文件不存在: {}", configFile);
        } catch (Exception e) {
            log.warn("读取 K8s 配置文件失败: {}", configFile, e);
        }
        return null;
    }

    /**
     * 在远程 shell 中写入 kubeconfig 并设置 KUBECONFIG 环境变量
     */
    public String buildRemoteKubeconfigSetup(String sessionKey, Long componentId) {
        return buildRemoteKubeconfigSetup(sessionKey, null, componentId);
    }

    public String buildRemoteKubeconfigSetup(String sessionKey, Long clusterId, Long componentId) {
        String content = resolveKubeconfigContent(clusterId, componentId);
        if (content == null || content.trim().isEmpty()) {
            return "";
        }
        String safeKey = sanitizeSessionKey(sessionKey);
        String filePath = "/tmp/opsflow-kubeconfig-" + safeKey + ".yaml";
        String delimiter = "OPSFLOW_KUBECONFIG_" + safeKey + "_EOF";
        while (content.contains(delimiter)) {
            delimiter += "_X";
        }
        return "cat <<'" + delimiter + "' > " + NodeCommandHelper.shellQuote(filePath) + "\n"
            + content + "\n" + delimiter + "\n"
            + "export KUBECONFIG=" + NodeCommandHelper.shellQuote(filePath) + "\n";
    }

    private String sanitizeSessionKey(String sessionKey) {
        if (sessionKey == null || sessionKey.trim().isEmpty()) {
            return "default";
        }
        return sessionKey.trim().replaceAll("[^a-zA-Z0-9_-]", "_");
    }
}
