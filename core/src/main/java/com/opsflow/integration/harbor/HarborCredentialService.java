package com.opsflow.integration.harbor;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.opsflow.dao.mapper.ComponentMapper;
import com.opsflow.dao.model.Component;
import com.opsflow.integration.credential.ComponentAuthResolver;
import com.opsflow.integration.credential.ResolvedAuth;
import com.opsflow.integration.pipeline.NodeCommandHelper;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Map;

/**
 * 解析 Harbor 组件凭据，供 API 与流水线节点命令使用
 */
@Slf4j
@org.springframework.stereotype.Component
public class HarborCredentialService {

    @Autowired
    private ComponentMapper componentMapper;

    @Autowired
    private ComponentAuthResolver componentAuthResolver;

    public Component resolveComponent(Long componentId) {
        if (componentId != null) {
            Component component = componentMapper.selectById(componentId);
            if (component != null && component.getStatus() != null && component.getStatus() == 1) {
                return component;
            }
            log.warn("Harbor 组件不存在或已禁用: componentId={}", componentId);
        }
        QueryWrapper<Component> wrapper = new QueryWrapper<>();
        wrapper.eq("type", "harbor");
        wrapper.eq("status", 1);
        wrapper.orderByDesc("create_time");
        wrapper.last("LIMIT 1");
        return componentMapper.selectOne(wrapper);
    }

    public String getRegistryHost(Long componentId) {
        Component component = resolveComponent(componentId);
        if (component == null || component.getUrl() == null) {
            return null;
        }
        return extractRegistryHost(component.getUrl());
    }

    public HarborCredentials resolveCredentials(Long componentId) {
        Component component = resolveComponent(componentId);
        if (component == null) {
            return null;
        }
        ResolvedAuth auth = componentAuthResolver.resolve(component);
        if (!auth.hasAuth()) {
            return null;
        }
        Map<String, String> config = auth.getAuthConfig();
        String authType = auth.getAuthType();
        HarborCredentials credentials = new HarborCredentials();
        credentials.setRegistry(getRegistryHost(component.getId()));

        if ("username_password".equals(authType)) {
            credentials.setUsername(config.get("username"));
            credentials.setPassword(config.get("password"));
        } else if ("token".equals(authType)) {
            credentials.setUsername(config.getOrDefault("username", "admin"));
            credentials.setPassword(config.get("token"));
        } else if ("api_key".equals(authType)) {
            credentials.setUsername(config.getOrDefault("username", "admin"));
            credentials.setPassword(config.get("apiKey"));
        } else {
            return null;
        }
        if (credentials.getUsername() == null || credentials.getPassword() == null
                || credentials.getUsername().isEmpty() || credentials.getPassword().isEmpty()) {
            return null;
        }
        return credentials;
    }

    /**
     * 在远程 shell 中执行的 docker login 前缀（含 &&）
     */
    public String buildDockerLoginPrefix(String registry, Long componentId) {
        if (registry == null || registry.trim().isEmpty()) {
            registry = getRegistryHost(componentId);
        }
        if (registry == null || registry.trim().isEmpty()) {
            return "";
        }
        HarborCredentials credentials = resolveCredentials(componentId);
        if (credentials == null) {
            return "";
        }
        return "echo " + NodeCommandHelper.shellQuote(credentials.getPassword())
            + " | docker login -u " + NodeCommandHelper.shellQuote(credentials.getUsername())
            + " --password-stdin " + NodeCommandHelper.shellQuote(registry.trim())
            + " && ";
    }

    public static String extractRegistryHost(String url) {
        if (url == null || url.trim().isEmpty()) {
            return null;
        }
        String normalized = url.trim();
        if (normalized.contains("://")) {
            normalized = normalized.substring(normalized.indexOf("://") + 3);
        }
        int slash = normalized.indexOf('/');
        if (slash > 0) {
            normalized = normalized.substring(0, slash);
        }
        return normalized.isEmpty() ? null : normalized;
    }

    public static String extractRegistryFromImage(String imageFullName) {
        if (imageFullName == null || imageFullName.trim().isEmpty()) {
            return null;
        }
        String image = imageFullName.trim();
        if (image.contains("://")) {
            image = image.substring(image.indexOf("://") + 3);
        }
        int slash = image.indexOf('/');
        if (slash <= 0) {
            return null;
        }
        return image.substring(0, slash);
    }

    @Data
    public static class HarborCredentials {
        private String registry;
        private String username;
        private String password;
    }
}
