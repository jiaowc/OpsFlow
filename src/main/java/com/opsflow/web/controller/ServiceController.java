package com.opsflow.web.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.opsflow.api.dto.GitRefBatchRequest;
import com.opsflow.api.dto.ServiceDTO;
import com.opsflow.common.exception.BusinessException;
import com.opsflow.dao.mapper.ComponentMapper;
import com.opsflow.dao.mapper.ServiceMapper;
import com.opsflow.dao.model.Component;
import com.opsflow.dao.model.Service;
import com.opsflow.service.ServiceGitRefService;
import com.opsflow.service.util.GitRepoPathResolver;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import com.opsflow.web.security.RequiresPermission;

/**
 * 服务管理控制器
 */
@RestController
@RequestMapping("/api/service")
public class ServiceController {

    @Autowired
    private ServiceMapper serviceMapper;

    @Autowired
    private ComponentMapper componentMapper;

    @Autowired
    private ServiceGitRefService serviceGitRefService;

    /**
     * 创建服务
     */
    @RequiresPermission("service:manage")
    @PostMapping("/create")
    public ServiceDTO createService(@RequestBody ServiceDTO request) {
        Service service = new Service();
        applyServiceFields(service, request, true);
        service.setStatus(request.getStatus() != null ? request.getStatus() : 1);
        service.setCreateTime(LocalDateTime.now());
        service.setUpdateTime(LocalDateTime.now());

        serviceMapper.insert(service);

        return toDto(service);
    }

    /**
     * 查询服务列表
     */
    @RequiresPermission({"service:view", "service:manage", "deploy:create", "pipeline:view"})
    @GetMapping("/list")
    public List<ServiceDTO> listServices() {
        List<Service> services = serviceMapper.selectList(new QueryWrapper<>());
        return services.stream().map(this::toDto).collect(Collectors.toList());
    }

    /**
     * 查询服务详情
     */
    @RequiresPermission({"service:view", "service:manage"})
    @GetMapping("/{id}")
    public ServiceDTO getService(@PathVariable Long id) {
        Service service = serviceMapper.selectById(id);
        if (service == null) {
            return null;
        }
        return toDto(service);
    }

    /**
     * 更新服务
     */
    @RequiresPermission("service:manage")
    @PutMapping("/{id}")
    public ServiceDTO updateService(@PathVariable Long id, @RequestBody ServiceDTO request) {
        Service service = serviceMapper.selectById(id);
        if (service == null) {
            return null;
        }

        applyServiceFields(service, request, false);
        if (request.getStatus() != null) {
            service.setStatus(request.getStatus());
        }
        service.setUpdateTime(LocalDateTime.now());
        serviceMapper.updateById(service);

        return toDto(service);
    }

    /**
     * 根据 Git 仓库地址获取分支或 Tag 列表
     */
    @RequiresPermission({"service:view", "pipeline:view", "pipeline:run"})
    @GetMapping("/git/refs")
    public List<String> listGitRefs(@RequestParam String gitRepo,
                                    @RequestParam(defaultValue = "branch") String type,
                                    @RequestParam(required = false) Long componentId) {
        return serviceGitRefService.listRefs(gitRepo, type, componentId);
    }

    /**
     * 批量获取 Git 引用（同仓库去重后由服务端缓存加速）
     */
    @RequiresPermission({"service:view", "pipeline:view", "pipeline:run"})
    @PostMapping("/git/refs/batch")
    public Map<String, List<String>> listGitRefsBatch(@RequestBody GitRefBatchRequest request) {
        if (request == null || request.getQueries() == null) {
            return new HashMap<>();
        }
        return serviceGitRefService.listRefsBatch(request.getQueries());
    }

    /**
     * 解析组件地址 + 仓库路径为完整 Git 仓库地址
     */
    @RequiresPermission({"service:view", "service:manage"})
    @GetMapping("/resolve-git-repo")
    public Map<String, Object> resolveGitRepo(@RequestParam Long componentId, @RequestParam String repoPath) {
        Map<String, Object> result = new HashMap<>();
        Component component = componentMapper.selectById(componentId);
        if (component == null) {
            result.put("success", false);
            result.put("message", "关联的 Git 组件不存在");
            return result;
        }
        if (!GitRepoPathResolver.isGitComponentType(component.getType())) {
            result.put("success", false);
            result.put("message", "仅支持关联 GitLab 或 GitHub 组件");
            return result;
        }
        GitRepoPathResolver.ResolveResult resolved = GitRepoPathResolver.resolve(component.getUrl(), repoPath);
        result.put("success", resolved.isSuccess());
        if (resolved.isSuccess()) {
            result.put("gitRepo", resolved.getGitRepo());
        } else {
            result.put("message", resolved.getErrorMessage());
        }
        return result;
    }

    /**
     * 删除服务
     */
    @RequiresPermission("service:manage")
    @DeleteMapping("/{id}")
    public boolean deleteService(@PathVariable Long id) {
        return serviceMapper.deleteById(id) > 0;
    }

    private void applyServiceFields(Service service, ServiceDTO request, boolean isCreate) {
        if (request.getName() != null) {
            service.setName(request.getName().trim());
        }
        if (isCreate) {
            service.setCode(resolveUniqueServiceCode(service.getName()));
        }

        applyGitRepoFields(service, request);

        if (isCreate) {
            service.setGitType("branch");
        }
        if (request.getServiceType() != null && !request.getServiceType().trim().isEmpty()) {
            service.setServiceType(request.getServiceType().trim());
        } else if (isCreate) {
            service.setServiceType("backend");
        }

        if (request.getServicePort() != null) {
            validateServicePort(request.getServicePort());
            service.setServicePort(request.getServicePort());
        } else if (isCreate) {
            throw new BusinessException("请填写对外提供服务端口");
        }
    }

    private void validateServicePort(Integer port) {
        if (port == null || port < 1 || port > 65535) {
            throw new BusinessException("对外提供服务端口须为 1-65535 之间的整数");
        }
    }

    private void applyGitRepoFields(Service service, ServiceDTO request) {
        if (request.getComponentId() != null) {
            Component component = componentMapper.selectById(request.getComponentId());
            if (component == null) {
                throw new BusinessException("关联的 Git 组件不存在");
            }
            if (!GitRepoPathResolver.isGitComponentType(component.getType())) {
                throw new BusinessException("仅支持关联 GitLab 或 GitHub 组件");
            }
            String repoPath = request.getGitRepoPath();
            if (repoPath == null || repoPath.trim().isEmpty()) {
                throw new BusinessException("请填写仓库路径");
            }
            GitRepoPathResolver.ResolveResult resolved = GitRepoPathResolver.resolve(component.getUrl(), repoPath.trim());
            if (!resolved.isSuccess()) {
                throw new BusinessException(resolved.getErrorMessage());
            }
            service.setComponentId(request.getComponentId());
            service.setGitRepoPath(repoPath.trim());
            service.setGitRepo(resolved.getGitRepo());
            return;
        }

        if (request.getGitRepo() != null) {
            service.setGitRepo(request.getGitRepo().trim());
            service.setComponentId(null);
            service.setGitRepoPath(request.getGitRepoPath());
        }
    }

    private ServiceDTO toDto(Service service) {
        ServiceDTO dto = new ServiceDTO();
        BeanUtils.copyProperties(service, dto);
        if (service.getComponentId() != null) {
            Component component = componentMapper.selectById(service.getComponentId());
            if (component != null) {
                dto.setComponentName(component.getName());
            }
        }
        return dto;
    }

    private String resolveUniqueServiceCode(String name) {
        String base = generateServiceCode(name);
        String code = base;
        int suffix = 1;
        while (codeExists(code)) {
            suffix++;
            code = base + "-" + suffix;
        }
        return code;
    }

    private boolean codeExists(String code) {
        QueryWrapper<Service> wrapper = new QueryWrapper<>();
        wrapper.eq("code", code);
        return serviceMapper.selectCount(wrapper) > 0;
    }

    private String generateServiceCode(String name) {
        if (name == null || name.trim().isEmpty()) {
            return "service";
        }
        String slug = name.trim().toLowerCase()
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
        if (!slug.isEmpty()) {
            return slug;
        }
        return "svc-" + Integer.toHexString(name.trim().hashCode()).replace("-", "");
    }
}
