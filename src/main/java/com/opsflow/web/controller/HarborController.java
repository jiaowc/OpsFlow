package com.opsflow.web.controller;

import com.opsflow.common.exception.BusinessException;
import com.opsflow.integration.harbor.HarborClient;
import com.opsflow.integration.harbor.HarborCredentialService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import com.opsflow.web.security.RequiresPermission;

/**
 * Harbor 镜像仓库 REST 接口。
 * <p>
 * 为上线任务创建页提供 Registry 地址、项目/仓库/标签浏览及服务版本查询；
 * {@link com.opsflow.web.controller.DeployTaskController} 创建任务时依赖此处返回的
 * Registry 主机名将模块规范化为完整镜像地址。
 * </p>
 */
@RestController
@RequestMapping("/api/harbor")
public class HarborController {

    @Autowired(required = false)
    private HarborClient harborClient;

    @Autowired
    private HarborCredentialService harborCredentialService;

    /**
     * 获取 Harbor Registry 主机名，用于前端拼接完整镜像地址。
     *
     * @return 包含 registry 字段的映射
     * @throws BusinessException Harbor 凭据未配置时
     */
    @RequiresPermission({"deploy:view", "deploy:create", "pipeline:view"})
    @GetMapping("/registry")
    public Map<String, String> getRegistry() {
        String registry = harborCredentialService.getRegistryHost(null);
        if (registry == null || registry.trim().isEmpty()) {
            throw new BusinessException("无法解析 Harbor Registry，请在「凭据与组件」中配置 Harbor");
        }
        Map<String, String> result = new HashMap<>();
        result.put("registry", registry.trim());
        return result;
    }

    /**
     * 获取 Harbor 项目列表。
     *
     * @return 项目名称列表
     */
    @RequiresPermission({"deploy:view", "deploy:create"})
    @GetMapping("/projects")
    public List<String> getProjects() {
        requireHarborClient();
        try {
            return harborClient.listProjects();
        } catch (Exception e) {
            throw new BusinessException("获取Harbor项目失败: " + e.getMessage());
        }
    }

    /**
     * 获取指定项目下的仓库（镜像名）列表。
     *
     * @param project Harbor 项目名
     * @return 仓库名称列表
     */
    @RequiresPermission({"deploy:view", "deploy:create"})
    @GetMapping("/projects/{project}/repositories")
    public List<String> getRepositories(@PathVariable String project) {
        requireHarborClient();
        try {
            return harborClient.listRepositories(project);
        } catch (Exception e) {
            throw new BusinessException("获取Harbor仓库失败: " + e.getMessage());
        }
    }

    /**
     * 获取指定项目与仓库的镜像标签列表。
     *
     * @param project Harbor 项目名
     * @param repository 仓库名（可含子路径）
     * @return 标签列表
     */
    @RequiresPermission({"deploy:view", "deploy:create"})
    @GetMapping("/projects/{project}/repositories/{repository}/tags")
    public List<String> getRepositoryTags(@PathVariable String project,
                                          @PathVariable String repository) {
        requireHarborClient();
        try {
            return harborClient.listImageTags(project, repository);
        } catch (Exception e) {
            throw new BusinessException("获取Harbor标签失败: " + e.getMessage());
        }
    }

    /**
     * 获取镜像列表；指定项目时仅返回该项目，否则遍历全部项目。
     *
     * @param project 可选，Harbor 项目名
     * @return 镜像全名列表
     */
    @RequiresPermission({"deploy:view", "deploy:create", "pipeline:view"})
    @GetMapping("/images")
    public List<String> getImages(@RequestParam(required = false) String project) {
        requireHarborClient();
        try {
            if (project != null && !project.isEmpty()) {
                return harborClient.listImages(project);
            }
            List<String> allImages = new ArrayList<>();
            for (String proj : harborClient.listProjects()) {
                allImages.addAll(harborClient.listImages(proj));
            }
            return allImages;
        } catch (Exception e) {
            throw new BusinessException("获取Harbor镜像失败: " + e.getMessage());
        }
    }

    /**
     * 获取指定镜像的标签列表。
     *
     * @param imageName 镜像/仓库名
     * @param project 可选，Harbor 项目名
     * @return 标签列表
     */
    @RequiresPermission({"deploy:view", "deploy:create", "pipeline:view", "pipeline:rollback"})
    @GetMapping("/images/{imageName}/tags")
    public List<String> getImageTags(@PathVariable String imageName,
                                     @RequestParam(required = false) String project) {
        requireHarborClient();
        try {
            return harborClient.listImageTags(project, imageName);
        } catch (Exception e) {
            throw new BusinessException("获取Harbor标签失败: " + e.getMessage());
        }
    }

    /**
     * 根据服务名查询可用版本（镜像 tag）列表。
     * <p>推荐指定 project 以缩小查找范围；未指定时遍历所有项目。</p>
     *
     * @param serviceName 服务 code 或仓库名
     * @param project 可选，Harbor 项目名
     * @return 去重后按推送时间倒序的版本列表
     */
    @RequiresPermission({"deploy:view", "deploy:create"})
    @GetMapping("/service/{serviceName}/versions")
    public List<String> getServiceVersions(@PathVariable String serviceName,
                                           @RequestParam(required = false) String project) {
        requireHarborClient();
        try {
            if (project != null && !project.trim().isEmpty()) {
                return resolveVersionsInProject(project.trim(), serviceName);
            }
            List<String> allVersions = new ArrayList<>();
            for (String proj : harborClient.listProjects()) {
                allVersions.addAll(resolveVersionsInProject(proj, serviceName));
            }
            return allVersions.stream().distinct().collect(Collectors.toList());
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException("获取服务版本失败: " + e.getMessage());
        }
    }

    /**
     * 在指定项目内按服务名匹配仓库并返回其标签列表。
     *
     * @param project Harbor 项目名
     * @param serviceName 服务 code 或仓库名
     * @return 标签列表；未匹配仓库时以 serviceName 作为仓库名尝试
     */
    private List<String> resolveVersionsInProject(String project, String serviceName) {
        List<String> repositories = harborClient.listRepositories(project);
        String matchedRepo = null;
        for (String repo : repositories) {
            if (serviceName.equals(repo) || repo.endsWith("/" + serviceName)) {
                matchedRepo = repo;
                break;
            }
        }
        if (matchedRepo == null) {
            // 兼容：仓库名直接等于服务名
            matchedRepo = serviceName;
        }
        return harborClient.listImageTags(project, matchedRepo);
    }

    /**
     * 校验 Harbor 客户端已初始化（组件与凭据已配置）。
     *
     * @throws BusinessException 客户端未注入时
     */
    private void requireHarborClient() {
        if (harborClient == null) {
            throw new BusinessException("Harbor 客户端未初始化，请在「组件管理」中配置 Harbor 并关联钥匙串");
        }
    }
}
