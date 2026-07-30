package com.opsflow.web.controller;

import com.opsflow.api.dto.LicenseStatusDTO;
import com.opsflow.service.LicenseService;
import com.opsflow.web.security.RequiresPermission;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * License 导入与状态
 */
@RestController
@RequestMapping("/api/license")
public class LicenseController {

    @Autowired
    private LicenseService licenseService;

    /** 任意登录用户可查状态（用于前端隐藏未授权菜单） */
    @GetMapping("/status")
    public LicenseStatusDTO status() {
        return licenseService.getStatus();
    }

    @RequiresPermission("system:config")
    @PostMapping("/import")
    public LicenseStatusDTO importLicense(@RequestBody Map<String, String> body) {
        String raw = body != null ? body.get("license") : null;
        if (raw == null) {
            raw = body != null ? body.get("raw") : null;
        }
        return licenseService.importLicense(raw);
    }

    @RequiresPermission("system:config")
    @DeleteMapping
    public Map<String, Object> clear() {
        licenseService.clearLicense();
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        return result;
    }
}
