package com.opsflow.web.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.opsflow.api.dto.ComponentDTO;
import com.opsflow.dao.mapper.ComponentMapper;
import com.opsflow.dao.model.Component;
import com.opsflow.service.ComponentAuthQueryService;
import com.opsflow.service.CredentialService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import com.opsflow.web.security.RequiresPermission;

/**
 * 组件管理控制器
 */
@RequiresPermission("credential:manage")
@RestController
@RequestMapping("/api/component")
public class ComponentController {

    @Autowired
    private ComponentMapper componentMapper;

    @Autowired
    private CredentialService credentialService;

    @Autowired
    private ComponentAuthQueryService componentAuthQueryService;
    
    private ObjectMapper objectMapper = new ObjectMapper();

    private static final int CONNECT_TIMEOUT_MS = 3000;
    private static final int READ_TIMEOUT_MS = 5000;
    private static final long TEST_CACHE_TTL_MS = 2 * 60 * 1000L;

    private final ConcurrentHashMap<Long, CachedTestResult> testResultCache = new ConcurrentHashMap<>();

    private static class CachedTestResult {
        final Map<String, Object> result;
        final long expiresAt;

        CachedTestResult(Map<String, Object> result, long expiresAt) {
            this.result = result;
            this.expiresAt = expiresAt;
        }

        boolean isValid() {
            return System.currentTimeMillis() < expiresAt;
        }
    }

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
            validateComponentAuth(request);
            
            Component component = new Component();
            BeanUtils.copyProperties(request, component, "authConfig", "authType", "credentialId");
            applyAuthFromRequest(component, request);
            
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

        validateComponentAuth(request);
        BeanUtils.copyProperties(request, component, "id", "createTime", "authConfig", "authType", "credentialId");
        
        try {
            applyAuthFromRequest(component, request);
        } catch (Exception e) {
            throw new RuntimeException("认证配置格式错误", e);
        }
        
        component.setUpdateTime(LocalDateTime.now());
        componentMapper.updateById(component);
        invalidateComponentTestCache(id);
        
        return getComponentDTO(component);
    }

    /**
     * 删除组件
     */
    @DeleteMapping("/{id}")
    public boolean deleteComponent(@PathVariable Long id) {
        boolean deleted = componentMapper.deleteById(id) > 0;
        if (deleted) {
            invalidateComponentTestCache(id);
        }
        return deleted;
    }

    /**
     * 批量测试组件连接（服务端并行检测，带短期缓存）
     */
    @PostMapping("/test/batch")
    public Map<String, Object> testComponentsBatch(@RequestBody List<Long> ids) {
        Map<String, Object> results = new ConcurrentHashMap<>();
        if (ids == null || ids.isEmpty()) {
            return results;
        }
        ids.parallelStream().forEach(id -> results.put(String.valueOf(id), runComponentTest(id, false)));
        return results;
    }

    /**
     * 测试组件连接
     */
    @PostMapping("/{id}/test")
    public Map<String, Object> testComponent(@PathVariable Long id,
            @RequestParam(defaultValue = "false") boolean force) {
        return runComponentTest(id, force);
    }

    private Map<String, Object> runComponentTest(Long id, boolean forceRefresh) {
        if (!forceRefresh) {
            CachedTestResult cached = testResultCache.get(id);
            if (cached != null) {
                if (cached.isValid()) {
                    return new HashMap<>(cached.result);
                }
                testResultCache.remove(id);
            }
        }

        Map<String, Object> result = executeComponentTest(id);
        testResultCache.put(id, new CachedTestResult(new HashMap<>(result), System.currentTimeMillis() + TEST_CACHE_TTL_MS));
        return result;
    }

    private void invalidateComponentTestCache(Long id) {
        if (id != null) {
            testResultCache.remove(id);
        }
    }

    private Map<String, Object> executeComponentTest(Long id) {
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

            if (!componentAuthQueryService.hasAuth(component)) {
                result.put("success", false);
                result.put("message", "组件认证配置不能为空，请关联钥匙串或填写认证信息");
                return result;
            }

            Map<String, String> authConfig = componentAuthQueryService.resolveAuthConfig(component);
            String authType = componentAuthQueryService.resolveAuthType(component);

            String componentType = component.getType() != null ? component.getType().toLowerCase() : "";

            switch (componentType) {
                case "harbor":
                    result = testHarborConnection(component.getUrl(), authType, authConfig);
                    break;
                case "nexus":
                    result = testNexusConnection(component.getUrl(), authType, authConfig);
                    break;
                case "gitlab":
                    result = testGitLabConnection(component.getUrl(), authType, authConfig);
                    break;
                case "github":
                    result = testGitHubConnection(component.getUrl(), authType, authConfig);
                    break;
                case "k8s":
                case "kubernetes":
                    result = testK8sConnection(component.getUrl(), authType, authConfig);
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

    private void applyConnectionTimeouts(HttpURLConnection connection) {
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
    }
    
    /**
     * 测试Harbor连接
     */
    private Map<String, Object> testHarborConnection(String url, String authType, Map<String, String> authConfig) {
        Map<String, Object> result = new HashMap<>();

        try {
            String username;
            String password;

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

            String baseUrl = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
            String apiUrl = baseUrl + "/api/v2.0/projects?page_size=1";

            HttpURLConnection connection = (HttpURLConnection) new URI(apiUrl).toURL().openConnection();
            applyConnectionTimeouts(connection);
            connection.setRequestMethod("GET");
            connection.setRequestProperty("Accept", "application/json");

            String credentials = username + ":" + password;
            String encoded = Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
            connection.setRequestProperty("Authorization", "Basic " + encoded);

            int statusCode = connection.getResponseCode();
            if (statusCode >= 200 && statusCode < 300) {
                result.put("success", true);
                result.put("message", "连接成功，Harbor 服务正常（HTTP " + statusCode + "）");
            } else if (statusCode == 401) {
                result.put("success", false);
                result.put("message", "认证失败，请检查用户名和密码/Token（HTTP 401）");
            } else {
                result.put("success", false);
                result.put("message", "连接失败，HTTP 状态码: " + statusCode);
            }
            connection.disconnect();
        } catch (Exception e) {
            result.put("success", false);
            result.put("message", "连接失败: " + e.getMessage());
        }

        return result;
    }

    /**
     * 测试 Nexus 连接（兼容 Nexus 3 / Nexus 2）
     */
    private Map<String, Object> testNexusConnection(String url, String authType, Map<String, String> authConfig) {
        Map<String, Object> result = new HashMap<>();

        try {
            String username;
            String password;

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

            String baseUrl = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
            String[] apiPaths = {
                "/service/rest/v1/repositories?pageSize=1",
                "/service/local/repo_groups"
            };

            int lastStatus = -1;
            for (String apiPath : apiPaths) {
                HttpURLConnection connection = (HttpURLConnection) new URI(baseUrl + apiPath).toURL().openConnection();
                applyConnectionTimeouts(connection);
                connection.setRequestMethod("GET");
                connection.setRequestProperty("Accept", "application/json");

                String credentials = username + ":" + password;
                String encoded = Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
                connection.setRequestProperty("Authorization", "Basic " + encoded);

                int statusCode = connection.getResponseCode();
                connection.disconnect();
                lastStatus = statusCode;

                if (statusCode >= 200 && statusCode < 300) {
                    result.put("success", true);
                    result.put("message", "连接成功，Nexus 服务正常（HTTP " + statusCode + "）");
                    return result;
                }
                if (statusCode == 401) {
                    result.put("success", false);
                    result.put("message", "认证失败，请检查用户名和密码/Token（HTTP 401）");
                    return result;
                }
            }

            result.put("success", false);
            result.put("message", "连接失败，HTTP 状态码: " + lastStatus);
        } catch (Exception e) {
            result.put("success", false);
            result.put("message", "连接失败: " + e.getMessage());
        }

        return result;
    }

    /**
     * 测试 GitLab 连接
     */
    private Map<String, Object> testGitLabConnection(String url, String authType, Map<String, String> authConfig) {
        Map<String, Object> result = new HashMap<>();

        try {
            String baseUrl = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
            String apiUrl = baseUrl + "/api/v4/user";

            HttpURLConnection connection = (HttpURLConnection) new URI(apiUrl).toURL().openConnection();
            applyConnectionTimeouts(connection);
            connection.setRequestMethod("GET");
            connection.setRequestProperty("Accept", "application/json");

            if ("username_password".equals(authType)) {
                String username = authConfig.get("username");
                String password = authConfig.get("password");
                if (username == null || password == null || username.isEmpty() || password.isEmpty()) {
                    result.put("success", false);
                    result.put("message", "用户名或密码/Token不能为空");
                    return result;
                }
                // GitLab 常用 Personal Access Token 作为密码
                if (password.startsWith("glpat-") || password.length() >= 20) {
                    connection.setRequestProperty("PRIVATE-TOKEN", password);
                } else {
                    String credentials = username + ":" + password;
                    String encoded = Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
                    connection.setRequestProperty("Authorization", "Basic " + encoded);
                }
            } else if ("token".equals(authType)) {
                String token = authConfig.get("token");
                if (token == null || token.isEmpty()) {
                    result.put("success", false);
                    result.put("message", "Token 不能为空");
                    return result;
                }
                connection.setRequestProperty("PRIVATE-TOKEN", token);
            } else if ("api_key".equals(authType)) {
                String apiKey = authConfig.get("apiKey");
                if (apiKey == null || apiKey.isEmpty()) {
                    result.put("success", false);
                    result.put("message", "API 密钥不能为空");
                    return result;
                }
                connection.setRequestProperty("PRIVATE-TOKEN", apiKey);
            } else if ("oauth".equals(authType)) {
                String token = authConfig.get("clientSecret");
                if (token == null || token.isEmpty()) {
                    token = authConfig.get("token");
                }
                if (token == null || token.isEmpty()) {
                    result.put("success", false);
                    result.put("message", "OAuth Token 不能为空");
                    return result;
                }
                connection.setRequestProperty("Authorization", "Bearer " + token);
            } else {
                result.put("success", false);
                result.put("message", "不支持的认证类型: " + authType);
                return result;
            }

            int statusCode = connection.getResponseCode();
            if (statusCode >= 200 && statusCode < 300) {
                result.put("success", true);
                result.put("message", "连接成功，GitLab 服务正常（HTTP " + statusCode + "）");
            } else if (statusCode == 401) {
                result.put("success", false);
                result.put("message", "认证失败，请检查 Token 或账户密码（HTTP 401）");
            } else if (statusCode == 404) {
                result.put("success", false);
                result.put("message", "GitLab API 不可用，请确认访问地址是否正确（HTTP 404）");
            } else {
                result.put("success", false);
                result.put("message", "连接失败，HTTP 状态码: " + statusCode);
            }
            connection.disconnect();
        } catch (Exception e) {
            result.put("success", false);
            result.put("message", "连接失败: " + e.getMessage());
        }

        return result;
    }

    /**
     * 测试 GitHub 连接
     */
    private Map<String, Object> testGitHubConnection(String url, String authType, Map<String, String> authConfig) {
        Map<String, Object> result = new HashMap<>();

        try {
            String baseUrl = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
            String apiUrl = resolveGitHubApiUserUrl(baseUrl);

            if ("username_password".equals(authType)) {
                return testGitHubWithPasswordStrategies(apiUrl, authConfig);
            }

            String authorization = buildGitHubAuthorization(authType, authConfig, result);
            if (authorization == null) {
                return result;
            }

            int statusCode = executeGitHubApiRequest(apiUrl, authorization);
            return buildGitHubTestResult(statusCode);
        } catch (Exception e) {
            result.put("success", false);
            result.put("message", "连接失败: " + e.getMessage());
            return result;
        }
    }

    private Map<String, Object> testGitHubWithPasswordStrategies(String apiUrl, Map<String, String> authConfig) {
        Map<String, Object> result = new HashMap<>();
        String username = authConfig.get("username");
        String password = authConfig.get("password");
        if (username == null || password == null || username.isEmpty() || password.isEmpty()) {
            result.put("success", false);
            result.put("message", "用户名或密码/Token不能为空");
            return result;
        }

        String[] authHeaders = new String[] {
            "Bearer " + password,
            "token " + password,
            "Basic " + Base64.getEncoder().encodeToString((username + ":" + password).getBytes(StandardCharsets.UTF_8))
        };

        int lastStatus = 401;
        try {
            for (String authorization : authHeaders) {
                lastStatus = executeGitHubApiRequest(apiUrl, authorization);
                if (lastStatus >= 200 && lastStatus < 300) {
                    return buildGitHubTestResult(lastStatus);
                }
                if (lastStatus != 401) {
                    return buildGitHubTestResult(lastStatus);
                }
            }
        } catch (Exception e) {
            result.put("success", false);
            result.put("message", "连接失败: " + e.getMessage());
            return result;
        }

        result.put("success", false);
        result.put("message", "认证失败（HTTP 401）。GitHub API 不支持网页登录密码，请使用 Personal Access Token (PAT)："
                + "在钥匙串中选择「Token」类型存放 PAT，或在「账户密码」的密码栏填写 PAT（非 GitHub 登录密码）。"
                + "创建路径：GitHub → Settings → Developer settings → Personal access tokens");
        return result;
    }

    private String buildGitHubAuthorization(String authType, Map<String, String> authConfig, Map<String, Object> result) {
        if ("token".equals(authType)) {
            String token = authConfig.get("token");
            if (token == null || token.isEmpty()) {
                result.put("success", false);
                result.put("message", "Token 不能为空");
                return null;
            }
            return "Bearer " + token;
        }
        if ("api_key".equals(authType)) {
            String apiKey = authConfig.get("apiKey");
            if (apiKey == null || apiKey.isEmpty()) {
                result.put("success", false);
                result.put("message", "API 密钥不能为空");
                return null;
            }
            return "Bearer " + apiKey;
        }
        if ("oauth".equals(authType)) {
            String token = authConfig.get("clientSecret");
            if (token == null || token.isEmpty()) {
                token = authConfig.get("token");
            }
            if (token == null || token.isEmpty()) {
                result.put("success", false);
                result.put("message", "OAuth Token 不能为空");
                return null;
            }
            return "Bearer " + token;
        }
        result.put("success", false);
        result.put("message", "不支持的认证类型: " + authType);
        return null;
    }

    private int executeGitHubApiRequest(String apiUrl, String authorization) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URI(apiUrl).toURL().openConnection();
        applyConnectionTimeouts(connection);
        connection.setRequestMethod("GET");
        connection.setRequestProperty("Accept", "application/vnd.github+json");
        connection.setRequestProperty("User-Agent", "OpsFlow");
        connection.setRequestProperty("Authorization", authorization);
        int statusCode = connection.getResponseCode();
        connection.disconnect();
        return statusCode;
    }

    private Map<String, Object> buildGitHubTestResult(int statusCode) {
        Map<String, Object> result = new HashMap<>();
        if (statusCode >= 200 && statusCode < 300) {
            result.put("success", true);
            result.put("message", "连接成功，GitHub 服务正常（HTTP " + statusCode + "）");
        } else if (statusCode == 401) {
            result.put("success", false);
            result.put("message", "认证失败，请检查 Personal Access Token 是否有效（HTTP 401）");
        } else if (statusCode == 403) {
            result.put("success", false);
            result.put("message", "访问被拒绝，请检查 Token 权限或 API 限流（HTTP 403）");
        } else if (statusCode == 404) {
            result.put("success", false);
            result.put("message", "GitHub API 不可用，请确认访问地址是否正确（HTTP 404）");
        } else {
            result.put("success", false);
            result.put("message", "连接失败，HTTP 状态码: " + statusCode);
        }
        return result;
    }

    private String resolveGitHubApiUserUrl(String baseUrl) {
        if ("https://github.com".equalsIgnoreCase(baseUrl)
                || "http://github.com".equalsIgnoreCase(baseUrl)) {
            return "https://api.github.com/user";
        }
        return baseUrl + "/api/v3/user";
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

        if (component.getCredentialId() != null) {
            com.opsflow.api.dto.CredentialDTO credential = credentialService.getById(component.getCredentialId());
            if (credential != null) {
                dto.setCredentialName(credential.getName());
            }
        }
        
        return dto;
    }

    private void validateComponentAuth(ComponentDTO request) {
        if (request.getCredentialId() != null) {
            com.opsflow.api.dto.CredentialDTO credential = credentialService.getById(request.getCredentialId());
            if (credential == null) {
                throw new IllegalArgumentException("关联的钥匙串不存在");
            }
            if (credential.getStatus() == null || credential.getStatus() != 1) {
                throw new IllegalArgumentException("关联的钥匙串未启用");
            }
            return;
        }
        if (request.getAuthType() == null || request.getAuthType().trim().isEmpty()) {
            throw new IllegalArgumentException("请选择钥匙串或填写认证类型");
        }
    }

    private void applyAuthFromRequest(Component component, ComponentDTO request) throws Exception {
        if (request.getCredentialId() != null) {
            com.opsflow.api.dto.CredentialDTO credential = credentialService.getById(request.getCredentialId());
            if (credential == null) {
                throw new IllegalArgumentException("关联的钥匙串不存在");
            }
            component.setCredentialId(request.getCredentialId());
            component.setAuthType(mapCredentialTypeToAuthType(credential.getCredentialType()));
            component.setAuthConfig(null);
            return;
        }

        component.setCredentialId(null);
        component.setAuthType(request.getAuthType());

        if (request.getAuthConfig() == null || request.getAuthConfig().isEmpty()) {
            return;
        }

        Map<String, String> newAuthConfig = new HashMap<>(request.getAuthConfig());
        if ("username_password".equals(request.getAuthType()) || "oauth".equals(request.getAuthType())) {
            Map<String, String> oldAuthConfig = parseAuthConfig(component.getAuthConfig());
            if ("username_password".equals(request.getAuthType())) {
                if ((newAuthConfig.get("password") == null || newAuthConfig.get("password").isEmpty())
                        && oldAuthConfig.containsKey("password")) {
                    newAuthConfig.put("password", oldAuthConfig.get("password"));
                }
            } else if ("oauth".equals(request.getAuthType())) {
                if ((newAuthConfig.get("clientSecret") == null || newAuthConfig.get("clientSecret").isEmpty())
                        && oldAuthConfig.containsKey("clientSecret")) {
                    newAuthConfig.put("clientSecret", oldAuthConfig.get("clientSecret"));
                }
            }
        }
        component.setAuthConfig(objectMapper.writeValueAsString(newAuthConfig));
    }

    private Map<String, String> parseAuthConfig(String authConfigJson) throws Exception {
        if (authConfigJson == null || authConfigJson.trim().isEmpty()) {
            return new HashMap<>();
        }
        return objectMapper.readValue(authConfigJson, new TypeReference<Map<String, String>>() {});
    }

    private String mapCredentialTypeToAuthType(String credentialType) {
        if (credentialType == null) {
            return "token";
        }
        switch (credentialType) {
            case "username_password":
            case "token":
            case "api_key":
            case "oauth":
            case "kubeconfig":
            case "ssh_key":
                return credentialType;
            case "ssh_password":
                return "username_password";
            default:
                return credentialType;
        }
    }
}

