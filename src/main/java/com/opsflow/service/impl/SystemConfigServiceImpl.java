package com.opsflow.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.opsflow.dao.mapper.SystemConfigMapper;
import com.opsflow.dao.model.SystemConfig;
import com.opsflow.service.SystemConfigService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class SystemConfigServiceImpl implements SystemConfigService {

    @Autowired
    private SystemConfigMapper systemConfigMapper;

    @Override
    public String get(String configType, String configKey) {
        return get(configType, configKey, null);
    }

    @Override
    public String get(String configType, String configKey, String defaultValue) {
        if (!StringUtils.hasText(configType) || !StringUtils.hasText(configKey)) {
            return defaultValue;
        }
        QueryWrapper<SystemConfig> wrapper = new QueryWrapper<>();
        wrapper.eq("config_type", configType.trim());
        wrapper.eq("config_key", configKey.trim());
        wrapper.last("LIMIT 1");
        SystemConfig config = systemConfigMapper.selectOne(wrapper);
        if (config == null || config.getConfigValue() == null) {
            return defaultValue;
        }
        String value = config.getConfigValue().trim();
        return value.isEmpty() ? defaultValue : value;
    }

    @Override
    public boolean getBoolean(String configType, String configKey, boolean defaultValue) {
        String value = get(configType, configKey);
        if (value == null) {
            return defaultValue;
        }
        return "true".equalsIgnoreCase(value) || "1".equals(value) || "yes".equalsIgnoreCase(value);
    }

    @Override
    public Map<String, String> getByType(String configType) {
        Map<String, String> map = new HashMap<>();
        if (!StringUtils.hasText(configType)) {
            return map;
        }
        List<SystemConfig> list = systemConfigMapper.selectList(
                new QueryWrapper<SystemConfig>().eq("config_type", configType.trim()));
        if (list == null) {
            return map;
        }
        for (SystemConfig config : list) {
            if (config.getConfigKey() != null) {
                map.put(config.getConfigKey(), config.getConfigValue());
            }
        }
        return map;
    }

    @Override
    public void save(String configType, String configKey, String configValue, String description) {
        if (!StringUtils.hasText(configType) || !StringUtils.hasText(configKey)) {
            return;
        }
        QueryWrapper<SystemConfig> wrapper = new QueryWrapper<>();
        wrapper.eq("config_type", configType.trim());
        wrapper.eq("config_key", configKey.trim());
        wrapper.last("LIMIT 1");
        SystemConfig config = systemConfigMapper.selectOne(wrapper);
        LocalDateTime now = LocalDateTime.now();
        if (config == null) {
            config = new SystemConfig();
            config.setConfigType(configType.trim());
            config.setConfigKey(configKey.trim());
            config.setConfigValue(configValue);
            config.setDescription(description);
            config.setCreateTime(now);
            config.setUpdateTime(now);
            systemConfigMapper.insert(config);
        } else {
            config.setConfigValue(configValue);
            if (description != null) {
                config.setDescription(description);
            }
            config.setUpdateTime(now);
            systemConfigMapper.updateById(config);
        }
    }

    @Override
    public void saveBatch(String configType, Map<String, String> values) {
        if (values == null || values.isEmpty()) {
            return;
        }
        for (Map.Entry<String, String> entry : values.entrySet()) {
            save(configType, entry.getKey(), entry.getValue(), null);
        }
    }
}
