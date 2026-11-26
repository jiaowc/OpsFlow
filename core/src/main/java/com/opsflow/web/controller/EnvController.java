package com.opsflow.web.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.opsflow.api.dto.EnvDTO;
import com.opsflow.dao.mapper.ClusterMapper;
import com.opsflow.dao.mapper.EnvMapper;
import com.opsflow.dao.model.Cluster;
import com.opsflow.dao.model.Env;
import com.opsflow.common.exception.BusinessException;
import com.opsflow.common.util.RequiredFields;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;
import com.opsflow.web.security.RequiresPermission;

/**
 * 环境管理控制器
 */
@RestController
@RequestMapping("/api/env")
public class EnvController {

    @Autowired
    private EnvMapper envMapper;

    @Autowired
    private ClusterMapper clusterMapper;

    /**
     * 创建环境
     */
    @RequiresPermission("env:manage")
    @PostMapping("/create")
    public EnvDTO createEnv(@RequestBody EnvDTO request) {
        // 没带按名称推断（与以前一致）；显式传了空值拒绝
        RequiredFields.optionalText(request.getEnvType(), "环境类型");
        Env env = new Env();
        BeanUtils.copyProperties(request, env);
        normalizeEnvType(env);
        validateEnv(env, null);
        applyCluster(env, request.getClusterId());
        env.setStatus(1);
        env.setCreateTime(LocalDateTime.now());
        env.setUpdateTime(LocalDateTime.now());
        
        envMapper.insert(env);
        
        return toDto(env);
    }

    /**
     * 查询环境列表
     */
    @RequiresPermission({"env:view", "env:manage", "pipeline:view", "deploy:view", "deploy:create"})
    @GetMapping("/list")
    public List<EnvDTO> listEnvs() {
        List<Env> envs = envMapper.selectList(new QueryWrapper<>());
        return envs.stream().map(env -> {
            return toDto(env);
        }).collect(Collectors.toList());
    }

    /**
     * 查询环境详情
     */
    @RequiresPermission({"env:view", "env:manage"})
    @GetMapping("/{id}")
    public EnvDTO getEnv(@PathVariable Long id) {
        Env env = envMapper.selectById(id);
        if (env == null) {
            return null;
        }
        return toDto(env);
    }

    /**
     * 更新环境
     */
    @RequiresPermission("env:manage")
    @PutMapping("/{id}")
    public EnvDTO updateEnv(@PathVariable Long id, @RequestBody EnvDTO request) {
        Env env = envMapper.selectById(id);
        if (env == null) {
            return null;
        }
        
        // 环境类型没带表示不改（以前会被置空后按名称重新推断），带了空值拒绝
        String envType = RequiredFields.optionalText(request.getEnvType(), "环境类型");
        BeanUtils.copyProperties(request, env, "id", "createTime", "envType");
        if (envType != null) {
            env.setEnvType(envType);
        }
        normalizeEnvType(env);
        validateEnv(env, id);
        applyCluster(env, request.getClusterId());
        env.setUpdateTime(LocalDateTime.now());
        envMapper.updateById(env);
        
        return toDto(env);
    }

    /**
     * 删除环境
     */
    @RequiresPermission("env:manage")
    @DeleteMapping("/{id}")
    public boolean deleteEnv(@PathVariable Long id) {
        return envMapper.deleteById(id) > 0;
    }

    private void applyCluster(Env env, Long clusterId) {
        if (clusterId != null && clusterMapper.selectById(clusterId) == null) {
            throw new BusinessException("关联的集群不存在");
        }
        env.setClusterId(clusterId);
    }

    private void normalizeEnvType(Env env) {
        String envType = env.getEnvType();
        if (envType == null || envType.trim().isEmpty()) {
            env.setEnvType("prod".equalsIgnoreCase(env.getName()) ? Env.TYPE_PROD : Env.TYPE_NON_PROD);
            return;
        }
        String normalized = envType.trim().toLowerCase();
        if (Env.TYPE_PROD.equals(normalized)) {
            env.setEnvType(Env.TYPE_PROD);
            return;
        }
        if (!Env.TYPE_NON_PROD.equals(normalized)) {
            // 以前会把任何未知值静默当成非生产，写错的 prod 变体会绕过生产环境保护
            throw new BusinessException("环境类型只能是 prod 或 nonprod");
        }
        env.setEnvType(Env.TYPE_NON_PROD);
    }

    /** K8s 命名空间：RFC 1123 label，小写字母数字和 -，首尾为字母数字，最长 63 */
    private static final java.util.regex.Pattern K8S_NAMESPACE =
            java.util.regex.Pattern.compile("[a-z0-9]([-a-z0-9]{0,61}[a-z0-9])?");

    private void validateEnv(Env env, Long excludeId) {
        if (env.getName() == null || env.getName().trim().isEmpty()) {
            throw new BusinessException("环境名称不能为空");
        }
        env.setName(env.getName().trim());
        QueryWrapper<Env> wrapper = new QueryWrapper<Env>().eq("name", env.getName().trim());
        if (excludeId != null) {
            wrapper.ne("id", excludeId);
        }
        if (envMapper.selectCount(wrapper) > 0) {
            throw new BusinessException("环境名称已存在");
        }
        String namespace = env.getK8sNamespace();
        if (namespace != null && !namespace.trim().isEmpty()) {
            if (!K8S_NAMESPACE.matcher(namespace.trim()).matches()) {
                throw new BusinessException("K8s 命名空间不合法：只能包含小写字母、数字和 -，以字母或数字开头和结尾，最长 63 个字符");
            }
            env.setK8sNamespace(namespace.trim());
        }
    }

    private EnvDTO toDto(Env env) {
        EnvDTO dto = new EnvDTO();
        BeanUtils.copyProperties(env, dto);
        if (env.getClusterId() != null) {
            Cluster cluster = clusterMapper.selectById(env.getClusterId());
            if (cluster != null) {
                dto.setClusterServer(cluster.getServer());
                dto.setK8sCluster(cluster.getName());
            }
        }
        return dto;
    }
}


