package com.opsflow.web.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.opsflow.api.dto.ClusterDTO;
import com.opsflow.dao.mapper.ClusterMapper;
import com.opsflow.dao.mapper.EnvMapper;
import com.opsflow.dao.model.Cluster;
import com.opsflow.dao.model.Env;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

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

    @PostMapping("/create")
    public ClusterDTO createCluster(@RequestBody ClusterDTO request) {
        Cluster cluster = new Cluster();
        BeanUtils.copyProperties(request, cluster);
        cluster.setStatus(cluster.getStatus() == null ? 1 : cluster.getStatus());
        cluster.setCreateTime(LocalDateTime.now());
        cluster.setUpdateTime(LocalDateTime.now());
        clusterMapper.insert(cluster);
        return toDto(cluster);
    }

    @GetMapping("/list")
    public List<ClusterDTO> listClusters() {
        return clusterMapper.selectList(new QueryWrapper<Cluster>().orderByAsc("name"))
            .stream()
            .map(this::toDto)
            .collect(Collectors.toList());
    }

    @GetMapping("/{id}")
    public ClusterDTO getCluster(@PathVariable Long id) {
        Cluster cluster = clusterMapper.selectById(id);
        return cluster == null ? null : toDto(cluster);
    }

    @PutMapping("/{id}")
    public ClusterDTO updateCluster(@PathVariable Long id, @RequestBody ClusterDTO request) {
        Cluster cluster = clusterMapper.selectById(id);
        if (cluster == null) {
            return null;
        }

        String oldName = cluster.getName();
        BeanUtils.copyProperties(request, cluster, "id", "createTime");
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

    @DeleteMapping("/{id}")
    public boolean deleteCluster(@PathVariable Long id) {
        Long count = envMapper.selectCount(new QueryWrapper<Env>().eq("cluster_id", id));
        if (count != null && count > 0) {
            throw new IllegalStateException("该集群已被环境引用，无法删除");
        }
        return clusterMapper.deleteById(id) > 0;
    }

    private ClusterDTO toDto(Cluster cluster) {
        ClusterDTO dto = new ClusterDTO();
        BeanUtils.copyProperties(cluster, dto);
        return dto;
    }
}
