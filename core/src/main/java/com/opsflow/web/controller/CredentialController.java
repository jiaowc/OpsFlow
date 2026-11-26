package com.opsflow.web.controller;

import com.opsflow.api.dto.CredentialDTO;
import com.opsflow.service.CredentialService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import com.opsflow.web.security.RequiresPermission;

/**
 * 钥匙串管理控制器
 */
@RequiresPermission("credential:manage")
@RestController
@RequestMapping("/api/credential")
public class CredentialController {

    @Autowired
    private CredentialService credentialService;

    @PostMapping("/create")
    public CredentialDTO create(@RequestBody CredentialDTO request) {
        return credentialService.create(request);
    }

    @GetMapping("/list")
    public List<CredentialDTO> list(@RequestParam(required = false) String type) {
        return credentialService.list(type);
    }

    /**
     * 下拉选项：仅返回 id / 名称 / 类型 / 状态，不含凭据内容（集群表单选择 kubeconfig 用）
     */
    @RequiresPermission({"credential:manage", "cluster:manage"})
    @GetMapping("/options")
    public List<java.util.Map<String, Object>> options(@RequestParam(required = false) String type) {
        return credentialService.list(type).stream().map(c -> {
            java.util.Map<String, Object> item = new java.util.LinkedHashMap<>();
            item.put("id", c.getId());
            item.put("name", c.getName());
            item.put("credentialType", c.getCredentialType());
            item.put("status", c.getStatus());
            return item;
        }).collect(java.util.stream.Collectors.toList());
    }

    @GetMapping("/{id}")
    public CredentialDTO get(@PathVariable Long id) {
        return credentialService.getById(id);
    }

    @PutMapping("/{id}")
    public CredentialDTO update(@PathVariable Long id, @RequestBody CredentialDTO request) {
        return credentialService.update(id, request);
    }

    @DeleteMapping("/{id}")
    public boolean delete(@PathVariable Long id) {
        return credentialService.delete(id);
    }
}
