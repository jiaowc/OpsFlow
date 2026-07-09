package com.opsflow.web.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.opsflow.api.dto.EnvDTO;
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
    @PostMapping("/create")
    public EnvDTO createEnv(@RequestBody EnvDTO request) {
        Env env = new Env();
        BeanUtils.copyProperties(request, env);
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
    @PutMapping("/{id}")
    public EnvDTO updateEnv(@PathVariable Long id, @RequestBody EnvDTO request) {
        Env env = envMapper.selectById(id);
        if (env == null) {
            return null;
        }
        
        BeanUtils.copyProperties(request, env, "id", "createTime");
        applyCluster(env, request.getClusterId());
        env.setUpdateTime(LocalDateTime.now());
        envMapper.updateById(env);
        
        return toDto(env);
    }

    /**
     * 删除环境
     */
    @DeleteMapping("/{id}")
    public boolean deleteEnv(@PathVariable Long id) {
        return envMapper.deleteById(id) > 0;
    }

    private void applyCluster(Env env, Long clusterId) {
        env.setClusterId(clusterId);
        if (clusterId == null) {
            env.setK8sCluster(null);
            return;
        }

        Cluster cluster = clusterMapper.selectById(clusterId);
        if (cluster != null) {
            env.setK8sCluster(cluster.getName());
        }
    }

    private EnvDTO toDto(Env env) {
        EnvDTO dto = new EnvDTO();
        BeanUtils.copyProperties(env, dto);
        if (env.getClusterId() != null) {
            Cluster cluster = clusterMapper.selectById(env.getClusterId());
            if (cluster != null) {
                dto.setClusterServer(cluster.getServer());
                if (dto.getK8sCluster() == null || dto.getK8sCluster().trim().isEmpty()) {
                    dto.setK8sCluster(cluster.getName());
                }
            }
        }
        return dto;
    }
}


