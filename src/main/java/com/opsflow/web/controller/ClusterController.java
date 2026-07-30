package com.opsflow.web.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.opsflow.api.dto.ClusterCheckRequest;
import com.opsflow.api.dto.ClusterCheckResultDTO;
import com.opsflow.api.dto.ClusterDTO;
import com.opsflow.dao.mapper.ClusterMapper;
import com.opsflow.dao.mapper.CredentialMapper;
import com.opsflow.dao.mapper.EnvMapper;
import com.opsflow.dao.model.Cluster;
import com.opsflow.dao.model.Credential;
import com.opsflow.dao.model.Env;
import com.opsflow.service.ClusterConnectivityService;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;
import com.opsflow.web.security.RequiresPermission;

/**
 * 集群管理控制器
 */
@RestController
@RequestMapping("/api/cluster")
public class ClusterController {

    @Autowired
    private ClusterMapper clusterMapper;

    @Autowired
    private EnvMapper envMapper;

    @Autowired
    private CredentialMapper credentialMapper;

    @Autowired
    private ClusterConnectivityService clusterConnectivityService;

    @RequiresPermission("cluster:manage")
    @PostMapping("/create")
    public ClusterDTO createCluster(@RequestBody ClusterDTO request) {
        Cluster cluster = new Cluster();
        BeanUtils.copyProperties(request, cluster);
        applyCredential(cluster, request.getCredentialId());
        cluster.setStatus(cluster.getStatus() == null ? 1 : cluster.getStatus());
        cluster.setCreateTime(LocalDateTime.now());
        cluster.setUpdateTime(LocalDateTime.now());
        clusterMapper.insert(cluster);
        return toDto(cluster);
    }

    @RequiresPermission({"cluster:view", "cluster:manage", "deploy:create", "env:manage"})
    @GetMapping("/list")
    public List<ClusterDTO> listClusters() {
        return clusterMapper.selectList(new QueryWrapper<Cluster>().orderByAsc("name"))
            .stream()
            .map(this::toDto)
            .collect(Collectors.toList());
    }

    @RequiresPermission({"cluster:view", "cluster:manage"})
    @GetMapping("/{id}")
    public ClusterDTO getCluster(@PathVariable Long id) {
        Cluster cluster = clusterMapper.selectById(id);
        return cluster == null ? null : toDto(cluster);
    }

    @RequiresPermission("cluster:manage")
    @PutMapping("/{id}")
    public ClusterDTO updateCluster(@PathVariable Long id, @RequestBody ClusterDTO request) {
        Cluster cluster = clusterMapper.selectById(id);
        if (cluster == null) {
            return null;
        }

        String oldName = cluster.getName();
        BeanUtils.copyProperties(request, cluster, "id", "createTime", "credentialId");
        applyCredential(cluster, request.getCredentialId());
        cluster.setUpdateTime(LocalDateTime.now());
        clusterMapper.updateById(cluster);

        if (oldName != null && cluster.getName() != null && !oldName.equals(cluster.getName())) {
            List<Env> envs = envMapper.selectList(new QueryWrapper<Env>().eq("cluster_id", id));
            for (Env env : envs) {
                env.setK8sCluster(cluster.getName());
                env.setUpdateTime(LocalDateTime.now());
                envMapper.updateById(env);
            }
        }

        return toDto(cluster);
    }

    @RequiresPermission("cluster:manage")
    @DeleteMapping("/{id}")
    public boolean deleteCluster(@PathVariable Long id) {
        Long count = envMapper.selectCount(new QueryWrapper<Env>().eq("cluster_id", id));
        if (count != null && count > 0) {
            throw new IllegalStateException("该集群已被环境引用，无法删除");
        }
        return clusterMapper.deleteById(id) > 0;
    }

    /**
     * 集群连通性检测。可指定代理节点，不传则本机执行。
     */
    @RequiresPermission("cluster:manage")
    @PostMapping("/{id}/test")
    public ClusterCheckResultDTO testCluster(@PathVariable Long id,
                                               @RequestBody(required = false) ClusterCheckRequest request) {
        Long nodeId = request != null ? request.getNodeId() : null;
        return clusterConnectivityService.check(id, nodeId);
    }

    private void applyCredential(Cluster cluster, Long credentialId) {
        cluster.setCredentialId(credentialId);
        if (credentialId == null) {
            return;
        }
        Credential credential = credentialMapper.selectById(credentialId);
        if (credential == null) {
            throw new IllegalArgumentException("关联的钥匙串不存在");
        }
        if (credential.getStatus() != null && credential.getStatus() != 1) {
            throw new IllegalArgumentException("关联的钥匙串已禁用");
        }
        if (!"kubeconfig".equals(credential.getCredentialType())) {
            throw new IllegalArgumentException("集群只能关联类型为「K8s 集群」的钥匙串");
        }
    }

    private ClusterDTO toDto(Cluster cluster) {
        ClusterDTO dto = new ClusterDTO();
        BeanUtils.copyProperties(cluster, dto);
        if (cluster.getCredentialId() != null) {
            Credential credential = credentialMapper.selectById(cluster.getCredentialId());
            if (credential != null) {
                dto.setCredentialName(credential.getName());
            }
        }
        return dto;
    }
}
