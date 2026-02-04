package com.opsflow.web.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.opsflow.api.dto.JenkinsNodeDTO;
import com.opsflow.dao.mapper.JenkinsNodeMapper;
import com.opsflow.dao.model.JenkinsNode;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 节点管理控制器
 */
@RestController
@RequestMapping("/api/node")
public class NodeController {

    @Autowired
    private JenkinsNodeMapper jenkinsNodeMapper;

    /**
     * 创建节点
     */
    @PostMapping("/create")
    public JenkinsNodeDTO createNode(@RequestBody JenkinsNodeDTO request) {
        JenkinsNode node = new JenkinsNode();
        BeanUtils.copyProperties(request, node);
        node.setCreateTime(LocalDateTime.now());
        node.setUpdateTime(LocalDateTime.now());
        
        jenkinsNodeMapper.insert(node);
        
        JenkinsNodeDTO dto = new JenkinsNodeDTO();
        BeanUtils.copyProperties(node, dto);
        return dto;
    }

    /**
     * 查询节点列表
     */
    @GetMapping("/list")
    public List<JenkinsNodeDTO> listNodes(@RequestParam(required = false) String nodeType) {
        QueryWrapper<JenkinsNode> wrapper = new QueryWrapper<>();
        if (nodeType != null && !nodeType.isEmpty()) {
            wrapper.eq("node_type", nodeType);
        }
        
        List<JenkinsNode> nodes = jenkinsNodeMapper.selectList(wrapper);
        return nodes.stream().map(node -> {
            JenkinsNodeDTO dto = new JenkinsNodeDTO();
            BeanUtils.copyProperties(node, dto);
            return dto;
        }).collect(Collectors.toList());
    }

    /**
     * 查询节点详情
     */
    @GetMapping("/{id}")
    public JenkinsNodeDTO getNode(@PathVariable Long id) {
        JenkinsNode node = jenkinsNodeMapper.selectById(id);
        if (node == null) {
            return null;
        }
        JenkinsNodeDTO dto = new JenkinsNodeDTO();
        BeanUtils.copyProperties(node, dto);
        return dto;
    }

    /**
     * 更新节点
     */
    @PutMapping("/{id}")
    public JenkinsNodeDTO updateNode(@PathVariable Long id, @RequestBody JenkinsNodeDTO request) {
        JenkinsNode node = jenkinsNodeMapper.selectById(id);
        if (node == null) {
            return null;
        }
        
        BeanUtils.copyProperties(request, node, "id", "createTime");
        node.setUpdateTime(LocalDateTime.now());
        jenkinsNodeMapper.updateById(node);
        
        JenkinsNodeDTO dto = new JenkinsNodeDTO();
        BeanUtils.copyProperties(node, dto);
        return dto;
    }

    /**
     * 删除节点
     */
    @DeleteMapping("/{id}")
    public boolean deleteNode(@PathVariable Long id) {
        return jenkinsNodeMapper.deleteById(id) > 0;
    }
}


