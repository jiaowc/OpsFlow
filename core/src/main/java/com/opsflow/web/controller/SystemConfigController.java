package com.opsflow.web.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.opsflow.api.dto.SystemConfigDTO;
import com.opsflow.common.exception.BusinessException;
import com.opsflow.dao.mapper.SystemConfigMapper;
import com.opsflow.dao.model.SystemConfig;
import com.opsflow.integration.ldap.LdapConfigService;
import com.opsflow.integration.ldap.LdapSettings;
import com.opsflow.service.BuildRetentionService;
import com.opsflow.service.SystemConfigService;
import com.opsflow.service.TwoFactorService;
import com.opsflow.spi.SystemConfigExtension;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import com.opsflow.web.security.RequiresPermission;

/**
 * 系统设置控制器
 */
@RequiresPermission("system:config")
@RestController
@RequestMapping("/api/system")
public class SystemConfigController {

    @Autowired
    private SystemConfigMapper systemConfigMapper;

    @Autowired
    private SystemConfigService systemConfigService;

    @Autowired
    private BuildRetentionService buildRetentionService;

    /** 扩展的配置类型校验、敏感项加密和脱敏、写入后处理和通知选项，社区版为空 */
    @Autowired(required = false)
    private List<SystemConfigExtension> extensions = new java.util.ArrayList<>();

    /**
     * 手动执行一次构建日志/记录/审计的保留清理；dryRun=true 只统计不删除
     */
    @PostMapping("/retention")
    public Map<String, Object> runRetention(@RequestParam(defaultValue = "true") boolean dryRun) {
        return buildRetentionService.runCleanup(dryRun);
    }

    /**
     * 创建或更新配置
     */
    @PostMapping("/config")
    public SystemConfigDTO saveConfig(@RequestBody SystemConfigDTO request) {
        rejectDedicatedConfigType(request.getConfigType());
        validateCoreValue(request.getConfigType(), request.getConfigKey(), request.getConfigValue());
        beforeSave(request.getConfigType());
        systemConfigService.save(
                request.getConfigType(),
                request.getConfigKey(),
                beforeStore(request.getConfigType(), request.getConfigKey(), request.getConfigValue()),
                request.getDescription()
        );
        afterSave(request.getConfigType());
        QueryWrapper<SystemConfig> wrapper = new QueryWrapper<>();
        wrapper.eq("config_type", request.getConfigType());
        wrapper.eq("config_key", request.getConfigKey());
        wrapper.last("LIMIT 1");
        SystemConfig config = systemConfigMapper.selectOne(wrapper);
        return config != null ? toMaskedDto(config) : new SystemConfigDTO();
    }

    /**
     * 批量保存某一类型配置
     * body: { "configType": "notify", "values": { "inboxEnabled": "true" } }
     */
    @PostMapping("/config/batch")
    public Map<String, Object> saveBatch(@RequestBody Map<String, Object> request) {
        String configType = request.get("configType") != null ? String.valueOf(request.get("configType")) : null;
        rejectDedicatedConfigType(configType);
        beforeSave(configType);
        Object valuesObj = request.get("values");
        Map<String, String> values = new HashMap<>();
        if (valuesObj instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> raw = (Map<String, Object>) valuesObj;
            for (Map.Entry<String, Object> entry : raw.entrySet()) {
                values.put(entry.getKey(), entry.getValue() != null ? String.valueOf(entry.getValue()) : "");
            }
        }
        values.forEach((key, value) -> validateCoreValue(configType, key, value));
        Map<String, String> stored = new HashMap<>();
        values.forEach((key, value) -> stored.put(key, beforeStore(configType, key, value)));
        systemConfigService.saveBatch(configType, stored);
        afterSave(configType);
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("configType", configType);
        result.put("count", values.size());
        return result;
    }

    /**
     * 通知渠道可用性与默认勾选（创建任务表单用）
     */
    @RequiresPermission({"system:config", "deploy:create", "deploy:view"})
    @GetMapping("/notify/options")
    public Map<String, Object> notifyOptions() {
        Map<String, Object> result = new HashMap<>();
        boolean inboxEnabled = systemConfigService.getBoolean("notify", "inboxEnabled", true);
        boolean defaultInbox = systemConfigService.getBoolean("notify", "defaultInbox", true);
        result.put("inboxEnabled", inboxEnabled);
        result.put("defaultInbox", inboxEnabled && defaultInbox);
        // 外部通道由扩展补充；社区版没有外部通道
        for (SystemConfigExtension extension : extensions) {
            extension.contributeNotifyOptions(result);
        }
        return result;
    }

    private void beforeSave(String configType) {
        if (configType == null) {
            return;
        }
        for (SystemConfigExtension extension : extensions) {
            extension.beforeSave(configType.trim());
        }
    }

    /** 依次交给扩展转换要存库的值（如加密敏感项、掩码回写时沿用原值） */
    private String beforeStore(String configType, String configKey, String value) {
        if (configType == null || configKey == null || extensions.isEmpty()) {
            return value;
        }
        String type = configType.trim();
        String key = configKey.trim();
        String storedValue = systemConfigService.get(type, key);
        String result = value;
        for (SystemConfigExtension extension : extensions) {
            result = extension.beforeStore(type, key, result, storedValue);
        }
        return result;
    }

    private void afterSave(String configType) {
        if (configType == null) {
            return;
        }
        for (SystemConfigExtension extension : extensions) {
            extension.afterSave(configType.trim());
        }
    }

    /**
     * 查询配置列表
     */
    @GetMapping("/config/list")
    public List<SystemConfigDTO> listConfigs(@RequestParam(required = false) String configType) {
        QueryWrapper<SystemConfig> wrapper = new QueryWrapper<>();
        if (configType != null && !configType.isEmpty()) {
            wrapper.eq("config_type", configType);
        }

        List<SystemConfig> configs = systemConfigMapper.selectList(wrapper);
        return configs.stream().map(this::toMaskedDto).collect(Collectors.toList());
    }

    /**
     * 查询指定类型的配置
     */
    @GetMapping("/config/{configType}")
    public List<SystemConfigDTO> getConfigsByType(@PathVariable String configType) {
        QueryWrapper<SystemConfig> wrapper = new QueryWrapper<>();
        wrapper.eq("config_type", configType);

        List<SystemConfig> configs = systemConfigMapper.selectList(wrapper);
        return configs.stream().map(this::toMaskedDto).collect(Collectors.toList());
    }

    /**
     * LDAP 配置有专用接口（/api/ldap/config）负责加密绑定密码，通用接口不允许写
     */
    private void rejectDedicatedConfigType(String configType) {
        if (configType != null && LdapSettings.CONFIG_TYPE.equalsIgnoreCase(configType.trim())) {
            throw new BusinessException("LDAP 配置请在「LDAP 登录」页面修改");
        }
    }

    /** 核心配置项的取值校验：两步验证策略只能是 off / optional / required */
    private void validateCoreValue(String configType, String configKey, String value) {
        if (configType != null && configKey != null
                && TwoFactorService.CONFIG_TYPE.equalsIgnoreCase(configType.trim())
                && TwoFactorService.POLICY_KEY.equals(configKey.trim())
                && TwoFactorService.Policy.fromCode(value) == null) {
            throw new BusinessException("两步验证策略只能是 off / optional / required");
        }
    }

    /** 通用读取接口：不返回 LDAP 绑定密码（即使是密文），其他敏感项由扩展脱敏 */
    private SystemConfigDTO toMaskedDto(SystemConfig config) {
        SystemConfigDTO dto = new SystemConfigDTO();
        BeanUtils.copyProperties(config, dto);
        if (config.getConfigType() != null && config.getConfigKey() != null) {
            String type = config.getConfigType().trim();
            String key = config.getConfigKey().trim();
            String value = dto.getConfigValue();
            for (SystemConfigExtension extension : extensions) {
                value = extension.maskForRead(type, key, value);
            }
            dto.setConfigValue(value);
        }
        if (LdapSettings.CONFIG_TYPE.equalsIgnoreCase(config.getConfigType())
                && "bindPassword".equals(config.getConfigKey())) {
            dto.setConfigValue(dto.getConfigValue() == null || dto.getConfigValue().isEmpty()
                    ? dto.getConfigValue() : LdapConfigService.PASSWORD_MASK);
        }
        return dto;
    }

    /**
     * 删除配置
     */
    @DeleteMapping("/config/{id}")
    public boolean deleteConfig(@PathVariable Long id) {
        return systemConfigMapper.deleteById(id) > 0;
    }
}
