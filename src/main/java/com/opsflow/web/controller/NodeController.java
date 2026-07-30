package com.opsflow.web.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.opsflow.api.dto.BuildNodeDTO;
import com.opsflow.api.dto.NodeEnvCheckResultDTO;
import com.opsflow.dao.mapper.BuildNodeMapper;
import com.opsflow.dao.model.BuildNode;
import com.opsflow.service.NodeService;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;
import com.opsflow.web.security.RequiresPermission;

/**
 * 节点管理控制器
 */
@RestController
@RequestMapping("/api/node")
public class NodeController {

    public static final String DEFAULT_NODE_WORK_DIR = "~/opsflow";

    @Autowired
    private BuildNodeMapper buildNodeMapper;

    @Autowired
    private NodeService nodeService;

    @RequiresPermission("node:manage")
    @PostMapping("/create")
    public BuildNodeDTO createNode(@RequestBody BuildNodeDTO request) {
        BuildNode node = new BuildNode();
        BeanUtils.copyProperties(request, node);
        if (node.getWorkDir() == null || node.getWorkDir().trim().isEmpty()) {
            node.setWorkDir(DEFAULT_NODE_WORK_DIR);
        } else {
            node.setWorkDir(normalizeWorkDir(node.getWorkDir()));
        }
        node.setCreateTime(LocalDateTime.now());
        node.setUpdateTime(LocalDateTime.now());

        buildNodeMapper.insert(node);
        return toDto(node);
    }

    @RequiresPermission({"node:view", "node:manage", "pipeline:view", "pipeline:run"})
    @GetMapping("/list")
    public List<BuildNodeDTO> listNodes(@RequestParam(required = false) String nodeType) {
        QueryWrapper<BuildNode> wrapper = new QueryWrapper<>();
        if (nodeType != null && !nodeType.isEmpty()) {
            wrapper.eq("node_type", nodeType);
        }

        return buildNodeMapper.selectList(wrapper).stream()
            .map(this::toDto)
            .collect(Collectors.toList());
    }

    @RequiresPermission({"node:view", "node:manage"})
    @GetMapping("/{id}")
    public BuildNodeDTO getNode(@PathVariable Long id) {
        BuildNode node = buildNodeMapper.selectById(id);
        if (node == null) {
            return null;
        }
        return toDto(node);
    }

    @RequiresPermission("node:manage")
    @PutMapping("/{id}")
    public BuildNodeDTO updateNode(@PathVariable Long id, @RequestBody BuildNodeDTO request) {
        BuildNode node = buildNodeMapper.selectById(id);
        if (node == null) {
            return null;
        }

        BeanUtils.copyProperties(request, node, "id", "createTime");
        node.setWorkDir(normalizeWorkDir(node.getWorkDir()));
        node.setUpdateTime(LocalDateTime.now());
        buildNodeMapper.updateById(node);

        return toDto(node);
    }

    @RequiresPermission("node:manage")
    @DeleteMapping("/{id}")
    public boolean deleteNode(@PathVariable Long id) {
        return buildNodeMapper.deleteById(id) > 0;
    }

    @RequiresPermission("node:manage")
    @PostMapping("/{id}/check-env")
    public NodeEnvCheckResultDTO checkNodeEnvironment(@PathVariable Long id) {
        return nodeService.checkEnvironment(id);
    }

    private BuildNodeDTO toDto(BuildNode node) {
        BuildNodeDTO dto = new BuildNodeDTO();
        BeanUtils.copyProperties(node, dto);
        dto.setWorkDir(normalizeWorkDir(dto.getWorkDir()));
        return dto;
    }

    private String normalizeWorkDir(String workDir) {
        if (workDir == null || workDir.trim().isEmpty()
            || "/tmp/opsflow-workspace".equals(workDir.trim())) {
            return DEFAULT_NODE_WORK_DIR;
        }
        return workDir.trim();
    }
}
