package com.opsflow.web.controller;

import com.opsflow.api.dto.CredentialDTO;
import com.opsflow.service.CredentialService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 钥匙串管理控制器
 */
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
