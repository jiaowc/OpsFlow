package com.opsflow.web.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.opsflow.api.dto.SystemConfigDTO;
import com.opsflow.dao.mapper.SystemConfigMapper;
import com.opsflow.dao.model.SystemConfig;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 系统设置控制器
 */
@RestController
@RequestMapping("/api/system")
public class SystemConfigController {

    @Autowired
    private SystemConfigMapper systemConfigMapper;

    /**
     * 创建或更新配置
     */
    @PostMapping("/config")
    public SystemConfigDTO saveConfig(@RequestBody SystemConfigDTO request) {
        // 检查是否已存在
        QueryWrapper<SystemConfig> wrapper = new QueryWrapper<>();
        wrapper.eq("config_type", request.getConfigType());
        wrapper.eq("config_key", request.getConfigKey());
        
        SystemConfig config = systemConfigMapper.selectOne(wrapper);
        
        if (config == null) {
            // 创建新配置
            config = new SystemConfig();
            BeanUtils.copyProperties(request, config);
            config.setCreateTime(LocalDateTime.now());
            config.setUpdateTime(LocalDateTime.now());
            systemConfigMapper.insert(config);
        } else {
            // 更新配置
            config.setConfigValue(request.getConfigValue());
            config.setDescription(request.getDescription());
            config.setUpdateTime(LocalDateTime.now());
            systemConfigMapper.updateById(config);
        }
        
        SystemConfigDTO dto = new SystemConfigDTO();
        BeanUtils.copyProperties(config, dto);
        return dto;
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


