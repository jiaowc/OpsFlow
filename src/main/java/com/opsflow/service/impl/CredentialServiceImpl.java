package com.opsflow.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opsflow.api.dto.CredentialDTO;
import com.opsflow.common.exception.BusinessException;
import com.opsflow.dao.mapper.CredentialMapper;
import com.opsflow.dao.model.Credential;
import com.opsflow.service.CredentialService;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class CredentialServiceImpl implements CredentialService {

    private static final String MASK = "******";

    private static final Set<String> SECRET_KEYS = new HashSet<>();

    static {
        SECRET_KEYS.add("password");
        SECRET_KEYS.add("token");
        SECRET_KEYS.add("apiKey");
        SECRET_KEYS.add("clientSecret");
        SECRET_KEYS.add("privateKey");
        SECRET_KEYS.add("passphrase");
        SECRET_KEYS.add("configContent");
        SECRET_KEYS.add("kubeconfig");
    }

    @Autowired
    private CredentialMapper credentialMapper;

    @Autowired
    private com.opsflow.dao.mapper.ComponentMapper componentMapper;

    @Autowired
    private com.opsflow.dao.mapper.ClusterMapper clusterMapper;

    /** 与前端钥匙串类型下拉、ComponentAuthResolver#mapCredentialTypeToAuthType 保持一致 */
    private static final List<String> CREDENTIAL_TYPES = java.util.Arrays.asList(
            "username_password", "token", "api_key", "oauth", "kubeconfig", "ssh_password", "ssh_key");

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public CredentialDTO create(CredentialDTO request) {
        validateRequest(request, true);
        assertNameUnique(request.getName(), null);
        Credential credential = new Credential();
        applyFields(credential, request, true);
        credential.setStatus(request.getStatus() != null ? request.getStatus() : 1);
        credential.setCreateTime(LocalDateTime.now());
        credential.setUpdateTime(LocalDateTime.now());
        credentialMapper.insert(credential);
        return toDto(credential, true);
    }

    @Override
    public List<CredentialDTO> list(String credentialType) {
        QueryWrapper<Credential> wrapper = new QueryWrapper<>();
        if (credentialType != null && !credentialType.trim().isEmpty()) {
            wrapper.eq("credential_type", credentialType.trim());
        }
        wrapper.orderByDesc("create_time");
        return credentialMapper.selectList(wrapper).stream()
                .map(c -> toDto(c, true))
                .collect(Collectors.toList());
    }

    @Override
    public CredentialDTO getById(Long id) {
        Credential credential = credentialMapper.selectById(id);
        if (credential == null) {
            return null;
        }
        return toDto(credential, true);
    }

    @Override
    public CredentialDTO update(Long id, CredentialDTO request) {
        Credential credential = credentialMapper.selectById(id);
        if (credential == null) {
            throw new BusinessException("钥匙不存在");
        }
        validateRequest(request, false);
        assertNameUnique(request.getName(), id);
        applyFields(credential, request, false);
        if (request.getStatus() != null) {
            credential.setStatus(request.getStatus());
        }
        credential.setUpdateTime(LocalDateTime.now());
        credentialMapper.updateById(credential);
        return toDto(credential, true);
    }

    @Override
    public boolean delete(Long id) {
        long components = componentMapper.selectCount(
                new QueryWrapper<com.opsflow.dao.model.Component>().eq("credential_id", id));
        long clusters = clusterMapper.selectCount(
                new QueryWrapper<com.opsflow.dao.model.Cluster>().eq("credential_id", id));
        if (components > 0 || clusters > 0) {
            throw new BusinessException("该钥匙仍被 " + components + " 个组件、" + clusters
                    + " 个集群引用，请先解除关联后再删除");
        }
        return credentialMapper.deleteById(id) > 0;
    }

    private void assertNameUnique(String name, Long excludeId) {
        QueryWrapper<Credential> wrapper = new QueryWrapper<Credential>().eq("name", name.trim());
        if (excludeId != null) {
            wrapper.ne("id", excludeId);
        }
        if (credentialMapper.selectCount(wrapper) > 0) {
            throw new BusinessException("钥匙名称已存在");
        }
    }

    private void validateRequest(CredentialDTO request, boolean isCreate) {
        if (request.getName() == null || request.getName().trim().isEmpty()) {
            throw new BusinessException("钥匙名称不能为空");
        }
        if (isCreate && (request.getCredentialType() == null || request.getCredentialType().trim().isEmpty())) {
            throw new BusinessException("凭证类型不能为空");
        }
        if (request.getCredentialType() != null && !request.getCredentialType().trim().isEmpty()
                && !CREDENTIAL_TYPES.contains(request.getCredentialType().trim())) {
            throw new BusinessException("不支持的凭证类型: " + request.getCredentialType().trim()
                    + "，可选值: " + String.join(" / ", CREDENTIAL_TYPES));
        }
        if (isCreate && (request.getConfigData() == null || request.getConfigData().isEmpty())) {
            throw new BusinessException("凭证配置不能为空");
        }
    }

    private void applyFields(Credential credential, CredentialDTO request, boolean isCreate) {
        if (request.getName() != null) {
            credential.setName(request.getName().trim());
        }
        if (request.getCredentialType() != null && !request.getCredentialType().trim().isEmpty()) {
            credential.setCredentialType(request.getCredentialType().trim());
        }
        if (request.getDescription() != null) {
            credential.setDescription(request.getDescription());
        }
        if (request.getConfigData() != null) {
            Map<String, String> merged = mergeConfig(credential.getConfigData(), request.getConfigData(), isCreate);
            try {
                credential.setConfigData(objectMapper.writeValueAsString(merged));
            } catch (Exception e) {
                throw new BusinessException("凭证配置格式错误");
            }
        } else if (isCreate) {
            throw new BusinessException("凭证配置不能为空");
        }
    }

    private Map<String, String> mergeConfig(String existingJson, Map<String, String> incoming, boolean isCreate) {
        Map<String, String> existing = parseConfig(existingJson);
        Map<String, String> result = new HashMap<>(existing);
        for (Map.Entry<String, String> entry : incoming.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();
            if (!isCreate && isSecretKey(key) && (value == null || value.trim().isEmpty() || MASK.equals(value))) {
                continue;
            }
            if (value != null) {
                result.put(key, value);
            }
        }
        if (isCreate && result.isEmpty()) {
            throw new BusinessException("凭证配置不能为空");
        }
        return result;
    }

    private Map<String, String> parseConfig(String configJson) {
        if (configJson == null || configJson.trim().isEmpty()) {
            return new HashMap<>();
        }
        try {
            return objectMapper.readValue(configJson, new TypeReference<Map<String, String>>() {});
        } catch (Exception e) {
            return new HashMap<>();
        }
    }

    private CredentialDTO toDto(Credential credential, boolean maskSecrets) {
        CredentialDTO dto = new CredentialDTO();
        BeanUtils.copyProperties(credential, dto, "configData");
        Map<String, String> config = parseConfig(credential.getConfigData());
        if (maskSecrets) {
            config = maskConfig(config);
        }
        dto.setConfigData(config);
        return dto;
    }

    private Map<String, String> maskConfig(Map<String, String> config) {
        Map<String, String> masked = new HashMap<>();
        for (Map.Entry<String, String> entry : config.entrySet()) {
            if (isSecretKey(entry.getKey()) && entry.getValue() != null && !entry.getValue().isEmpty()) {
                masked.put(entry.getKey(), MASK);
            } else {
                masked.put(entry.getKey(), entry.getValue());
            }
        }
        return masked;
    }

    private boolean isSecretKey(String key) {
        return SECRET_KEYS.contains(key);
    }
}
