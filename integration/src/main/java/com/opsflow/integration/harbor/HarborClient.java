package com.opsflow.integration.harbor;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.opsflow.dao.mapper.ComponentMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.util.Base64Utils;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;

/**
 * Harbor客户端
 * 用于与Harbor镜像仓库交互
 */
@Slf4j
@Component
public class HarborClient {

    @Autowired
    private ComponentMapper componentMapper;
    
    private ObjectMapper objectMapper = new ObjectMapper();
    
    private String harborUrl;
    private String harborUsername;
    private String harborPassword;
    
    private RestTemplate restTemplate;
    private HttpHeaders headers;
    
    /**
     * 初始化或获取HttpHeaders（懒加载）
     */
    private HttpHeaders getHeaders() {
        if (headers == null) {
            synchronized (this) {
                if (headers == null) {
                    init();
                }
            }
        }
        return headers;
    }
    
    /**
     * 从组件管理加载配置并初始化
     */
    private void init() {
        try {
            QueryWrapper<com.opsflow.dao.model.Component> wrapper = new QueryWrapper<>();
            wrapper.eq("type", "harbor");
            wrapper.eq("status", 1);
            wrapper.orderByDesc("create_time");
            wrapper.last("LIMIT 1");
            
            com.opsflow.dao.model.Component component = componentMapper.selectOne(wrapper);
            if (component == null) {
                throw new RuntimeException("未找到启用的Harbor组件配置，请在系统管理->组件管理中配置");
            }
            
            if (component.getUrl() == null || component.getUrl().trim().isEmpty()) {
                throw new RuntimeException("Harbor组件配置中URL不能为空");
            }
            
            if (component.getAuthConfig() == null || component.getAuthConfig().trim().isEmpty()) {
                throw new RuntimeException("Harbor组件配置中认证信息不能为空");
            }
            
            harborUrl = component.getUrl();
            
            // 解析authConfig JSON
            java.util.Map<String, String> authConfig = null;
            try {
                authConfig = objectMapper.readValue(
                    component.getAuthConfig(),
                    new TypeReference<java.util.Map<String, String>>() {}
                );
            } catch (Exception e) {
                throw new RuntimeException("Harbor组件认证配置格式错误", e);
            }
            
            // 根据认证类型获取用户名和密码
            if ("username_password".equals(component.getAuthType())) {
                harborUsername = authConfig.get("username");
                harborPassword = authConfig.get("password");
            } else if ("token".equals(component.getAuthType())) {
                // Token认证时，username可以是任意值，password是token
                harborUsername = authConfig.getOrDefault("username", "admin");
                harborPassword = authConfig.get("token");
            } else if ("api_key".equals(component.getAuthType())) {
                // API Key认证时，username可以是任意值，password是apiKey
                harborUsername = authConfig.getOrDefault("username", "admin");
                harborPassword = authConfig.get("apiKey");
            } else {
                throw new RuntimeException("Harbor组件不支持的认证类型: " + component.getAuthType());
            }
            
            if (harborUsername == null || harborPassword == null) {
                throw new RuntimeException("Harbor组件配置中用户名或密码/Token不能为空");
            }
            
        restTemplate = new RestTemplate();
        
        // 设置认证头
        headers = new HttpHeaders();
        String auth = harborUsername + ":" + harborPassword;
        String encodedAuth = Base64Utils.encodeToString(auth.getBytes(StandardCharsets.UTF_8));
        headers.set("Authorization", "Basic " + encodedAuth);
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(Collections.singletonList(MediaType.APPLICATION_JSON));
        
        log.info("Harbor客户端初始化成功: {}", harborUrl);
        } catch (Exception e) {
            log.error("Harbor客户端初始化失败", e);
            throw new RuntimeException("Harbor客户端初始化失败: " + e.getMessage(), e);
        }
    }
    
    /**
     * 验证镜像是否存在
     * @param projectName 项目名称
     * @param repositoryName 仓库名称（镜像名称）
     * @param tag 镜像标签
     * @return 是否存在
     */
    public boolean imageExists(String projectName, String repositoryName, String tag) {
        try {
            getHeaders(); // 确保已初始化
            String url = String.format("%s/api/v2.0/projects/%s/repositories/%s/artifacts/%s",
                    harborUrl, projectName, repositoryName, tag);
            
            HttpEntity<String> entity = new HttpEntity<>(getHeaders());
            ResponseEntity<ArtifactInfo> response = restTemplate.exchange(
                    url, HttpMethod.GET, entity, ArtifactInfo.class);
            
            return response.getStatusCode().is2xxSuccessful();
        } catch (Exception e) {
            log.debug("镜像不存在或查询失败: {}/{}/{}", projectName, repositoryName, tag, e);
            return false;
        }
    }
    
    /**
     * 获取镜像信息
     * @param projectName 项目名称
     * @param repositoryName 仓库名称
     * @param tag 镜像标签
     * @return 镜像信息
     */
    public ArtifactInfo getImageInfo(String projectName, String repositoryName, String tag) {
        try {
            getHeaders(); // 确保已初始化
            String url = String.format("%s/api/v2.0/projects/%s/repositories/%s/artifacts/%s",
                    harborUrl, projectName, repositoryName, tag);
            
            HttpEntity<String> entity = new HttpEntity<>(getHeaders());
            ResponseEntity<ArtifactInfo> response = restTemplate.exchange(
                    url, HttpMethod.GET, entity, ArtifactInfo.class);
            
            if (response.getStatusCode().is2xxSuccessful()) {
                return response.getBody();
            }
            return null;
        } catch (Exception e) {
            log.error("获取镜像信息失败: {}/{}/{}", projectName, repositoryName, tag, e);
            return null;
        }
    }
    
    /**
     * 获取仓库下的所有镜像标签
     * @param projectName 项目名称
     * @param repositoryName 仓库名称
     * @return 标签列表
     */
    public List<String> getImageTags(String projectName, String repositoryName) {
        try {
            getHeaders(); // 确保已初始化
            String url = String.format("%s/api/v2.0/projects/%s/repositories/%s/artifacts",
                    harborUrl, projectName, repositoryName);
            
            HttpEntity<String> entity = new HttpEntity<>(getHeaders());
            ResponseEntity<ArtifactInfo[]> response = restTemplate.exchange(
                    url, HttpMethod.GET, entity, ArtifactInfo[].class);
            
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                // 提取所有标签
                return java.util.Arrays.stream(response.getBody())
                        .flatMap(artifact -> artifact.getTags() != null ? 
                                artifact.getTags().stream().map(TagInfo::getName) : 
                                java.util.stream.Stream.empty())
                        .collect(java.util.stream.Collectors.toList());
            }
            return Collections.emptyList();
        } catch (Exception e) {
            log.error("获取镜像标签列表失败: {}/{}", projectName, repositoryName, e);
            return Collections.emptyList();
        }
    }
    
    /**
     * 从完整镜像名称解析项目、仓库和标签
     * 格式: harbor.example.com/project/repository:tag
     * @param imageFullName 完整镜像名称
     * @return 解析结果
     */
    public ImageInfo parseImageName(String imageFullName) {
        ImageInfo info = new ImageInfo();
        
        // 移除协议前缀（如果有）
        String image = imageFullName;
        if (image.contains("://")) {
            image = image.substring(image.indexOf("://") + 3);
        }
        
        // 分离主机和路径
        int firstSlash = image.indexOf('/');
        if (firstSlash > 0) {
            info.setRegistry(image.substring(0, firstSlash));
            String path = image.substring(firstSlash + 1);
            
            // 分离标签
            int colonIndex = path.lastIndexOf(':');
            if (colonIndex > 0) {
                info.setTag(path.substring(colonIndex + 1));
                path = path.substring(0, colonIndex);
            } else {
                info.setTag("latest");
            }
            
            // 分离项目和仓库
            int secondSlash = path.indexOf('/');
            if (secondSlash > 0) {
                info.setProject(path.substring(0, secondSlash));
                info.setRepository(path.substring(secondSlash + 1));
            } else {
                // 如果没有项目分隔符，使用默认项目
                info.setProject("library");
                info.setRepository(path);
            }
        }
        
        return info;
    }
    
    /**
     * 验证镜像是否已推送到Harbor
     * @param imageFullName 完整镜像名称
     * @return 是否已推送
     */
    public boolean verifyImagePushed(String imageFullName) {
        ImageInfo imageInfo = parseImageName(imageFullName);
        return imageExists(imageInfo.getProject(), imageInfo.getRepository(), imageInfo.getTag());
    }
    
    /**
     * 获取Harbor项目列表
     * @return 项目名称列表
     */
    public List<String> listProjects() {
        try {
            getHeaders(); // 确保已初始化
            String url = harborUrl + "/api/v2.0/projects?page_size=100";
            HttpEntity<String> entity = new HttpEntity<>(getHeaders());
            ResponseEntity<ProjectResponse> response = restTemplate.exchange(
                    url, HttpMethod.GET, entity, ProjectResponse.class);
            
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                return response.getBody().getProjects().stream()
                        .map(ProjectInfo::getName)
                        .collect(java.util.stream.Collectors.toList());
            }
            return Collections.emptyList();
        } catch (Exception e) {
            log.error("获取Harbor项目列表失败", e);
            return Collections.emptyList();
        }
    }
    
    /**
     * 获取指定项目的镜像列表
     * @param projectName 项目名称
     * @return 完整镜像名称列表（格式：harbor.example.com/project/repository:tag）
     */
    public List<String> listImages(String projectName) {
        try {
            getHeaders(); // 确保已初始化
            String url = harborUrl + "/api/v2.0/projects/" + projectName + "/repositories?page_size=100";
            HttpEntity<String> entity = new HttpEntity<>(getHeaders());
            ResponseEntity<RepositoryResponse> response = restTemplate.exchange(
                    url, HttpMethod.GET, entity, RepositoryResponse.class);
            
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                getHeaders(); // 确保已初始化
                List<String> images = new java.util.ArrayList<>();
                for (RepositoryInfo repo : response.getBody().getRepositories()) {
                    // 获取每个仓库的标签
                    List<String> tags = getImageTags(projectName, repo.getName());
                    String registry = harborUrl.replace("https://", "").replace("http://", "");
                    for (String tag : tags) {
                        images.add(registry + "/" + projectName + "/" + repo.getName() + ":" + tag);
                    }
                }
                return images;
            }
            return Collections.emptyList();
        } catch (Exception e) {
            log.error("获取Harbor镜像列表失败: {}", projectName, e);
            return Collections.emptyList();
        }
    }
    
    /**
     * 获取指定项目的镜像标签列表（重载方法，兼容原有接口）
     */
    public List<String> listImageTags(String projectName, String repositoryName) {
        return getImageTags(projectName, repositoryName);
    }
    
    /**
     * 镜像信息
     */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ArtifactInfo {
        @JsonProperty("digest")
        private String digest;
        
        @JsonProperty("tags")
        private List<TagInfo> tags;
        
        @JsonProperty("push_time")
        private String pushTime;
        
        @JsonProperty("pull_time")
        private String pullTime;
    }
    
    /**
     * 标签信息
     */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class TagInfo {
        @JsonProperty("name")
        private String name;
        
        @JsonProperty("push_time")
        private String pushTime;
    }
    
    /**
     * 镜像解析信息
     */
    @Data
    public static class ImageInfo {
        private String registry;
        private String project;
        private String repository;
        private String tag;
    }
    
    /**
     * 项目列表响应
     */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ProjectResponse {
        @JsonProperty("projects")
        private List<ProjectInfo> projects;
    }
    
    /**
     * 项目信息
     */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ProjectInfo {
        @JsonProperty("name")
        private String name;
    }
    
    /**
     * 仓库列表响应
     */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class RepositoryResponse {
        @JsonProperty("repositories")
        private List<RepositoryInfo> repositories;
    }
    
    /**
     * 仓库信息
     */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class RepositoryInfo {
        @JsonProperty("name")
        private String name;
    }
}


