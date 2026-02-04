package com.opsflow.web.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.opsflow.api.dto.ComponentDTO;
import com.opsflow.dao.mapper.ComponentMapper;
import com.opsflow.dao.model.Component;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.offbytwo.jenkins.JenkinsServer;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 组件管理控制器
 */
@RestController
@RequestMapping("/api/component")
public class ComponentController {

    @Autowired
    private ComponentMapper componentMapper;
    
    private ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 创建组件
     */
    @PostMapping("/create")
    public ComponentDTO createComponent(@RequestBody ComponentDTO request) {
        try {
            // 参数验证
            if (request.getName() == null || request.getName().trim().isEmpty()) {
                throw new IllegalArgumentException("组件名称不能为空");
            }
            if (request.getType() == null || request.getType().trim().isEmpty()) {
                throw new IllegalArgumentException("组件类型不能为空");
            }
            if (request.getUrl() == null || request.getUrl().trim().isEmpty()) {
                throw new IllegalArgumentException("访问地址不能为空");
            }
            if (request.getAuthType() == null || request.getAuthType().trim().isEmpty()) {
                throw new IllegalArgumentException("认证类型不能为空");
            }
            
            Component component = new Component();
            BeanUtils.copyProperties(request, component, "authConfig");
            
            // 将authConfig转换为JSON字符串
            if (request.getAuthConfig() != null && !request.getAuthConfig().isEmpty()) {
                component.setAuthConfig(objectMapper.writeValueAsString(request.getAuthConfig()));
            }
            
            component.setStatus(1);
            component.setCreateTime(LocalDateTime.now());
            component.setUpdateTime(LocalDateTime.now());
            
            componentMapper.insert(component);
            
            return getComponentDTO(component);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("创建组件失败: " + e.getMessage(), e);
        }
    }

    /**
     * 查询组件列表
     */
    @GetMapping("/list")
    public List<ComponentDTO> listComponents(@RequestParam(required = false) String type) {
        QueryWrapper<Component> wrapper = new QueryWrapper<>();
        if (type != null && !type.isEmpty()) {
            wrapper.eq("type", type);
        }
        wrapper.orderByDesc("create_time");
        
        List<Component> components = componentMapper.selectList(wrapper);
        return components.stream().map(this::getComponentDTO).collect(Collectors.toList());
    }

    /**
     * 查询组件详情
     */
    @GetMapping("/{id}")
    public ComponentDTO getComponent(@PathVariable Long id) {
        Component component = componentMapper.selectById(id);
        if (component == null) {
            return null;
        }
        return getComponentDTO(component);
    }

    /**
     * 更新组件
     */
    @PutMapping("/{id}")
    public ComponentDTO updateComponent(@PathVariable Long id, @RequestBody ComponentDTO request) {
        Component component = componentMapper.selectById(id);
        if (component == null) {
            return null;
        }
        
        BeanUtils.copyProperties(request, component, "id", "createTime", "authConfig");
        
        // 更新authConfig
        try {
            if (request.getAuthConfig() != null) {
                // 如果是账户密码或OAuth类型，且密码/Secret为空，则保留原有值
                if ("username_password".equals(request.getAuthType()) || "oauth".equals(request.getAuthType())) {
                    Map<String, String> oldAuthConfig = null;
                    if (component.getAuthConfig() != null && !component.getAuthConfig().isEmpty()) {
                        oldAuthConfig = objectMapper.readValue(
                            component.getAuthConfig(),
                            new TypeReference<Map<String, String>>() {}
                        );
                    }
                    
                    Map<String, String> newAuthConfig = request.getAuthConfig();
                    if ("username_password".equals(request.getAuthType())) {
                        // 如果密码为空，保留原有密码
                        if ((newAuthConfig.get("password") == null || newAuthConfig.get("password").isEmpty()) 
                            && oldAuthConfig != null && oldAuthConfig.containsKey("password")) {
                            newAuthConfig.put("password", oldAuthConfig.get("password"));
                        }
                    } else if ("oauth".equals(request.getAuthType())) {
                        // 如果Client Secret为空，保留原有值
                        if ((newAuthConfig.get("clientSecret") == null || newAuthConfig.get("clientSecret").isEmpty()) 
                            && oldAuthConfig != null && oldAuthConfig.containsKey("clientSecret")) {
                            newAuthConfig.put("clientSecret", oldAuthConfig.get("clientSecret"));
                        }
                    }
                    
                    component.setAuthConfig(objectMapper.writeValueAsString(newAuthConfig));
                } else {
                    component.setAuthConfig(objectMapper.writeValueAsString(request.getAuthConfig()));
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("认证配置格式错误", e);
        }
        
        component.setUpdateTime(LocalDateTime.now());
        componentMapper.updateById(component);
        
        return getComponentDTO(component);
    }

    /**
     * 删除组件
     */
    @DeleteMapping("/{id}")
    public boolean deleteComponent(@PathVariable Long id) {
        return componentMapper.deleteById(id) > 0;
    }

    /**
     * 测试组件连接
     */
    @PostMapping("/{id}/test")
    public Map<String, Object> testComponent(@PathVariable Long id) {
        Map<String, Object> result = new HashMap<>();
        
        try {
            Component component = componentMapper.selectById(id);
            if (component == null) {
                result.put("success", false);
                result.put("message", "组件不存在");
                return result;
            }
            
            if (component.getUrl() == null || component.getUrl().trim().isEmpty()) {
                result.put("success", false);
                result.put("message", "组件URL不能为空");
                return result;
            }
            
            if (component.getAuthConfig() == null || component.getAuthConfig().trim().isEmpty()) {
                result.put("success", false);
                result.put("message", "组件认证配置不能为空");
                return result;
            }
            
            // 解析authConfig
            Map<String, String> authConfig = null;
            try {
                authConfig = objectMapper.readValue(
                    component.getAuthConfig(),
                    new TypeReference<Map<String, String>>() {}
                );
            } catch (Exception e) {
                result.put("success", false);
                result.put("message", "认证配置格式错误: " + e.getMessage());
                return result;
            }
            
            // 根据组件类型进行测试
            String componentType = component.getType() != null ? component.getType().toLowerCase() : "";
            
            switch (componentType) {
                case "jenkins":
                    result = testJenkinsConnection(component.getUrl(), component.getAuthType(), authConfig);
                    break;
                case "harbor":
                    result = testHarborConnection(component.getUrl(), component.getAuthType(), authConfig);
                    break;
                case "k8s":
                case "kubernetes":
                    result = testK8sConnection(component.getUrl(), component.getAuthType(), authConfig);
                    break;
                default:
                    result.put("success", false);
                    result.put("message", "暂不支持该组件类型的连接测试: " + componentType);
            }
            
        } catch (Exception e) {
            result.put("success", false);
            result.put("message", "测试连接时发生错误: " + e.getMessage());
        }
        
        return result;
    }
    
    /**
     * 测试Jenkins连接
     */
    private Map<String, Object> testJenkinsConnection(String url, String authType, Map<String, String> authConfig) {
        Map<String, Object> result = new HashMap<>();
        
        try {
            String username;
            String password;
            
            // 根据认证类型获取用户名和密码
            if ("username_password".equals(authType)) {
                username = authConfig.get("username");
                password = authConfig.get("password");
            } else if ("token".equals(authType)) {
                username = authConfig.getOrDefault("username", "admin");
                password = authConfig.get("token");
            } else if ("api_key".equals(authType)) {
                username = authConfig.getOrDefault("username", "admin");
                password = authConfig.get("apiKey");
            } else {
                result.put("success", false);
                result.put("message", "不支持的认证类型: " + authType);
                return result;
            }
            
            if (username == null || password == null || username.isEmpty() || password.isEmpty()) {
                result.put("success", false);
                result.put("message", "用户名或密码/Token不能为空");
                return result;
            }
            
            // 创建JenkinsServer实例并测试连接
            // 注意：JenkinsServer 使用 HTTP 连接，不需要显式关闭
            JenkinsServer jenkinsServer = new JenkinsServer(new URI(url), username, password);
            
            // 尝试获取Jenkins作业列表来验证连接
            Map<String, com.offbytwo.jenkins.model.Job> jobs = jenkinsServer.getJobs();
            
            result.put("success", true);
            result.put("message", "连接成功Jenkins服务器正常共找到 " + (jobs != null ? jobs.size() : 0) + " 个作业");
            
        } catch (Exception e) {
            result.put("success", false);
            result.put("message", "连接失败: " + e.getMessage());
        }
        
        return result;
    }
    
    /**
     * 测试Harbor连接
     */
    private Map<String, Object> testHarborConnection(String url, String authType, Map<String, String> authConfig) {
        Map<String, Object> result = new HashMap<>();
        
        try {
            // TODO: 实现Harbor连接测试
            // 这里需要根据Harbor的API进行测试
            result.put("success", false);
            result.put("message", "Harbor连接测试功能待实现");
        } catch (Exception e) {
            result.put("success", false);
            result.put("message", "连接失败: " + e.getMessage());
        }
        
        return result;
    }
    
    /**
     * 测试K8s连接
     */
    private Map<String, Object> testK8sConnection(String url, String authType, Map<String, String> authConfig) {
        Map<String, Object> result = new HashMap<>();
        
        try {
            // TODO: 实现K8s连接测试
            // 这里需要根据K8s的配置进行测试
            result.put("success", false);
            result.put("message", "K8s连接测试功能待实现");
        } catch (Exception e) {
            result.put("success", false);
            result.put("message", "连接失败: " + e.getMessage());
        }
        
        return result;
    }

    private ComponentDTO getComponentDTO(Component component) {
        ComponentDTO dto = new ComponentDTO();
        BeanUtils.copyProperties(component, dto, "authConfig");
        
        // 解析authConfig JSON
        try {
            if (component.getAuthConfig() != null && !component.getAuthConfig().isEmpty()) {
                Map<String, String> authConfig = objectMapper.readValue(
                    component.getAuthConfig(),
                    new TypeReference<Map<String, String>>() {}
                );
                dto.setAuthConfig(authConfig);
            }
        } catch (Exception e) {
            // 忽略解析错误
        }
        
        return dto;
    }
}

