package com.opsflow.web.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.opsflow.api.dto.BuildNodeDTO;
import com.opsflow.api.dto.NodeEnvCheckResultDTO;
import com.opsflow.common.exception.BusinessException;
import com.opsflow.common.util.RequiredFields;
import com.opsflow.dao.mapper.BuildNodeMapper;
import com.opsflow.dao.model.BuildNode;
import com.opsflow.service.NodeService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import com.opsflow.web.security.RequiresPermission;

/**
 * 节点管理控制器
 */
@Slf4j
@RestController
@RequestMapping("/api/node")
public class NodeController {

    public static final String DEFAULT_NODE_WORK_DIR = "~/opsflow";

    /** 只读字段：不允许从请求写入 */
    private static final String[] READ_ONLY_FIELDS = {"hostKeyFingerprint", "hasPassword", "hasPrivateKey"};

    private static final String[] UPDATE_IGNORED_FIELDS = {"id", "createTime",
            "hostKeyFingerprint", "hasPassword", "hasPrivateKey",
            "name", "nodeType", "host", "authType", "username"};

    public static final String AUTH_PASSWORD = "password";
    public static final String AUTH_PRIVATE_KEY = "private_key";

    @Autowired
    private BuildNodeMapper buildNodeMapper;

    @Autowired
    private NodeService nodeService;

    @RequiresPermission("node:manage")
    @PostMapping("/create")
    public BuildNodeDTO createNode(@RequestBody BuildNodeDTO request) {
        if (request.getName() == null || request.getName().trim().isEmpty()) {
            throw new BusinessException("节点名称不能为空");
        }
        if (buildNodeMapper.selectCount(new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<BuildNode>()
                .eq("name", request.getName().trim())) > 0) {
            throw new BusinessException("节点名称已存在");
        }
        BuildNode node = new BuildNode();
        BeanUtils.copyProperties(request, node, READ_ONLY_FIELDS);
        node.setName(request.getName().trim());
        node.setNodeType(RequiredFields.requireText(request.getNodeType(), "节点类型"));
        node.setHost(RequiredFields.requireText(request.getHost(), "主机地址"));
        node.setAuthType(RequiredFields.requireText(request.getAuthType(), "认证方式"));
        node.setUsername(RequiredFields.requireText(request.getUsername(), "用户名"));
        validateNodeCredential(node);
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

        // 密码/私钥留空表示不修改；Jackson 缺省字段为 null，BeanUtils 会误覆盖成空
        String oldPassword = node.getPassword();
        String oldPrivateKey = node.getPrivateKey();
        String oldPassphrase = node.getPrivateKeyPassphrase();
        String oldHost = node.getHost();
        int oldPort = effectivePort(node.getPort());
        // 必填项没带表示不改，带了空值拒绝（以前没带时会被置成 null，host 变 null 还会误清主机指纹）
        String name = RequiredFields.optionalText(request.getName(), "节点名称");
        String nodeType = RequiredFields.optionalText(request.getNodeType(), "节点类型");
        String host = RequiredFields.optionalText(request.getHost(), "主机地址");
        String authType = RequiredFields.optionalText(request.getAuthType(), "认证方式");
        String username = RequiredFields.optionalText(request.getUsername(), "用户名");
        BeanUtils.copyProperties(request, node, UPDATE_IGNORED_FIELDS);
        if (name != null) {
            node.setName(name);
        }
        if (nodeType != null) {
            node.setNodeType(nodeType);
        }
        if (host != null) {
            node.setHost(host);
        }
        if (authType != null) {
            node.setAuthType(authType);
        }
        if (username != null) {
            node.setUsername(username);
        }
        if (request.getPassword() == null || request.getPassword().trim().isEmpty()) {
            node.setPassword(oldPassword);
        }
        if (request.getPrivateKey() == null || request.getPrivateKey().trim().isEmpty()) {
            node.setPrivateKey(oldPrivateKey);
        }
        if (request.getPrivateKeyPassphrase() == null || request.getPrivateKeyPassphrase().trim().isEmpty()) {
            node.setPrivateKeyPassphrase(oldPassphrase);
        }
        // 按合并后的值校验：密码 / 私钥留空表示沿用旧值
        validateNodeCredential(node);
        node.setWorkDir(normalizeWorkDir(node.getWorkDir()));
        node.setUpdateTime(LocalDateTime.now());
        buildNodeMapper.updateById(node);

        // 地址或端口变了，就是另一台机器：清空旧指纹，下次连接重新首次信任
        boolean addressChanged = !Objects.equals(trimToNull(oldHost), trimToNull(node.getHost()))
                || oldPort != effectivePort(node.getPort());
        if (addressChanged) {
            clearHostKey(id);
            node.setHostKey(null);
            node.setHostKeyFingerprint(null);
        }

        return toDto(node);
    }

    /** 密码认证要有密码，私钥认证要有私钥 */
    static void validateNodeCredential(BuildNode node) {
        if (AUTH_PASSWORD.equals(node.getAuthType())) {
            RequiredFields.requireSecret(node.getPassword(), "密码");
        } else if (AUTH_PRIVATE_KEY.equals(node.getAuthType())) {
            RequiredFields.requireSecret(node.getPrivateKey(), "私钥");
        }
    }

    /**
     * 重置 SSH 主机指纹：节点重装系统或更换主机后使用，下次连接时重新记录
     */
    @RequiresPermission("node:manage")
    @PostMapping("/{id}/reset-host-key")
    public BuildNodeDTO resetHostKey(@PathVariable Long id) {
        BuildNode node = buildNodeMapper.selectById(id);
        if (node == null) {
            throw new BusinessException("节点不存在");
        }
        clearHostKey(id);
        log.warn("节点 SSH 主机指纹已重置: id={} name={} 旧指纹={}", id, node.getName(), node.getHostKeyFingerprint());
        node.setHostKey(null);
        node.setHostKeyFingerprint(null);
        return toDto(node);
    }

    private void clearHostKey(Long id) {
        UpdateWrapper<BuildNode> wrapper = new UpdateWrapper<>();
        wrapper.eq("id", id).set("host_key", null).set("host_key_fingerprint", null);
        buildNodeMapper.update(null, wrapper);
    }

    private static int effectivePort(Integer port) {
        return port != null && port > 0 ? port : 22;
    }

    private static String trimToNull(String s) {
        return s == null || s.trim().isEmpty() ? null : s.trim();
    }

    @RequiresPermission("node:manage")
    @DeleteMapping("/{id}")
    public boolean deleteNode(@PathVariable Long id) {
        if (buildNodeMapper.selectById(id) == null) {
            throw new BusinessException("节点不存在");
        }
        return buildNodeMapper.deleteById(id) > 0;
    }

    @RequiresPermission("node:manage")
    @PostMapping("/{id}/check-env")
    public NodeEnvCheckResultDTO checkNodeEnvironment(@PathVariable Long id) {
        return nodeService.checkEnvironment(id);
    }

    private BuildNodeDTO toDto(BuildNode node) {
        BuildNodeDTO dto = new BuildNodeDTO();
        // 密码 / 私钥 / 私钥口令一律不回传前端（列表接口 pipeline:view 也能访问）
        BeanUtils.copyProperties(node, dto, "password", "privateKey", "privateKeyPassphrase");
        dto.setHasPassword(node.getPassword() != null && !node.getPassword().isEmpty());
        dto.setHasPrivateKey(node.getPrivateKey() != null && !node.getPrivateKey().trim().isEmpty());
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
