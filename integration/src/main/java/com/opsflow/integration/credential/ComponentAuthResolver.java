package com.opsflow.integration.credential;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opsflow.dao.mapper.CredentialMapper;
import com.opsflow.dao.model.Credential;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.HashMap;
import java.util.Map;

/**
 * 从组件或关联钥匙串解析认证信息
 */
@Slf4j
@org.springframework.stereotype.Component
public class ComponentAuthResolver {

    @Autowired
    private CredentialMapper credentialMapper;

    private final ObjectMapper objectMapper = new ObjectMapper();

    public ResolvedAuth resolve(com.opsflow.dao.model.Component component) {
        if (component == null) {
            return new ResolvedAuth();
        }
        if (component.getCredentialId() != null) {
            Credential credential = credentialMapper.selectById(component.getCredentialId());
            if (credential != null && credential.getStatus() != null && credential.getStatus() == 1) {
                return fromCredential(credential);
            }
            log.warn("组件关联的钥匙串不存在或已禁用: componentId={}, credentialId={}",
                    component.getId(), component.getCredentialId());
        }
        return fromComponent(component);
    }

    public ResolvedAuth fromCredential(Credential credential) {
        ResolvedAuth resolved = new ResolvedAuth();
        if (credential == null) {
            return resolved;
        }
        resolved.setAuthType(mapCredentialTypeToAuthType(credential.getCredentialType()));
        resolved.setAuthConfig(parseConfig(credential.getConfigData()));
        return resolved;
    }

    private ResolvedAuth fromComponent(com.opsflow.dao.model.Component component) {
        ResolvedAuth resolved = new ResolvedAuth();
        resolved.setAuthType(component.getAuthType());
        resolved.setAuthConfig(parseConfig(component.getAuthConfig()));
        return resolved;
    }

    private Map<String, String> parseConfig(String configJson) {
        if (configJson == null || configJson.trim().isEmpty()) {
            return new HashMap<>();
        }
        try {
            return objectMapper.readValue(configJson, new TypeReference<Map<String, String>>() {});
        } catch (Exception e) {
            log.warn("解析认证配置失败", e);
            return new HashMap<>();
        }
    }

    /**
     * 将钥匙串类型映射为组件 auth_type（兼容现有客户端逻辑）
     */
    public String mapCredentialTypeToAuthType(String credentialType) {
        if (credentialType == null) {
            return null;
        }
        switch (credentialType) {
            case "username_password":
            case "token":
            case "api_key":
            case "oauth":
                return credentialType;
            case "kubeconfig":
                return "kubeconfig";
            case "ssh_password":
                return "username_password";
            case "ssh_key":
                return "ssh_key";
            default:
                return credentialType;
        }
    }
}
