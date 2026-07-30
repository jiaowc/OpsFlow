package com.opsflow.web.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.opsflow.api.dto.SystemConfigDTO;
import com.opsflow.dao.mapper.SystemConfigMapper;
import com.opsflow.dao.model.SystemConfig;
import com.opsflow.integration.feishu.FeishuClient;
import com.opsflow.service.SystemConfigService;
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
    private FeishuClient feishuClient;

    /**
     * 创建或更新配置
     */
    @PostMapping("/config")
    public SystemConfigDTO saveConfig(@RequestBody SystemConfigDTO request) {
        systemConfigService.save(
                request.getConfigType(),
                request.getConfigKey(),
                request.getConfigValue(),
                request.getDescription()
        );
        if ("feishu".equalsIgnoreCase(request.getConfigType())
                || "feishu_notify".equalsIgnoreCase(request.getConfigType())) {
            feishuClient.invalidateToken();
        }
        QueryWrapper<SystemConfig> wrapper = new QueryWrapper<>();
        wrapper.eq("config_type", request.getConfigType());
        wrapper.eq("config_key", request.getConfigKey());
        wrapper.last("LIMIT 1");
        SystemConfig config = systemConfigMapper.selectOne(wrapper);
        SystemConfigDTO dto = new SystemConfigDTO();
        if (config != null) {
            BeanUtils.copyProperties(config, dto);
        }
        return dto;
    }

    /**
     * 批量保存某一类型配置
     * body: { "configType": "feishu", "values": { "appId": "...", "enabled": "true" } }
     */
    @PostMapping("/config/batch")
    public Map<String, Object> saveBatch(@RequestBody Map<String, Object> request) {
        String configType = request.get("configType") != null ? String.valueOf(request.get("configType")) : null;
        Object valuesObj = request.get("values");
        Map<String, String> values = new HashMap<>();
        if (valuesObj instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> raw = (Map<String, Object>) valuesObj;
            for (Map.Entry<String, Object> entry : raw.entrySet()) {
                values.put(entry.getKey(), entry.getValue() != null ? String.valueOf(entry.getValue()) : "");
            }
        }
        systemConfigService.saveBatch(configType, values);
        if ("feishu".equalsIgnoreCase(configType)
                || "feishu_notify".equalsIgnoreCase(configType)) {
            feishuClient.invalidateToken();
        }
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("configType", configType);
        result.put("count", values.size());
        return result;
    }

    /**
     * 通知渠道可用性与默认勾选（创建任务表单用）
     */
    @GetMapping("/notify/options")
    public Map<String, Object> notifyOptions() {
        Map<String, Object> result = new HashMap<>();
        boolean inboxEnabled = systemConfigService.getBoolean("notify", "inboxEnabled", true);
        boolean feishuEnabled = systemConfigService.getBoolean("feishu_notify", "approvalEnabled", false)
                || (systemConfigService.get("feishu_notify", "approvalEnabled") == null
                && systemConfigService.getBoolean("feishu", "approvalEnabled", false));
        boolean defaultInbox = systemConfigService.getBoolean("notify", "defaultInbox", true);
        boolean defaultFeishu = systemConfigService.getBoolean("notify", "defaultFeishu", false);
        result.put("inboxEnabled", inboxEnabled);
        result.put("feishuEnabled", feishuEnabled);
        result.put("defaultInbox", inboxEnabled && defaultInbox);
        result.put("defaultFeishu", feishuEnabled && defaultFeishu);
        return result;
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
        return configs.stream().map(config -> {
            SystemConfigDTO dto = new SystemConfigDTO();
            BeanUtils.copyProperties(config, dto);
            return dto;
        }).collect(Collectors.toList());
    }

    /**
     * 查询指定类型的配置
     */
    @GetMapping("/config/{configType}")
    public List<SystemConfigDTO> getConfigsByType(@PathVariable String configType) {
        QueryWrapper<SystemConfig> wrapper = new QueryWrapper<>();
        wrapper.eq("config_type", configType);

        List<SystemConfig> configs = systemConfigMapper.selectList(wrapper);
        return configs.stream().map(config -> {
            SystemConfigDTO dto = new SystemConfigDTO();
            BeanUtils.copyProperties(config, dto);
            return dto;
        }).collect(Collectors.toList());
    }

    /**
     * 删除配置
     */
    @DeleteMapping("/config/{id}")
    public boolean deleteConfig(@PathVariable Long id) {
        return systemConfigMapper.deleteById(id) > 0;
    }
}
