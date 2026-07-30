package com.opsflow.service;

import java.util.Map;

/**
 * 系统配置读写（DB 持久化）
 */
public interface SystemConfigService {

    String get(String configType, String configKey);

    String get(String configType, String configKey, String defaultValue);

    boolean getBoolean(String configType, String configKey, boolean defaultValue);

    Map<String, String> getByType(String configType);

    void save(String configType, String configKey, String configValue, String description);

    void saveBatch(String configType, Map<String, String> values);
}
