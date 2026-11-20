package com.opsflow.web.controller;

import com.opsflow.license.LicenseFeatures;
import com.opsflow.service.AuditLogService;
import com.opsflow.service.LicenseService;
import com.opsflow.web.security.RequiresPermission;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/audit")
@RequiresPermission("audit:view")
public class AuditLogController {

    @Autowired
    private AuditLogService auditLogService;

    @Autowired
    private LicenseService licenseService;

    @GetMapping("/list")
    public Map<String, Object> list(@RequestParam(required = false) String username,
                                    @RequestParam(required = false) String module,
                                    @RequestParam(required = false) String keyword,
                                    @RequestParam(required = false) Boolean success,
                                    @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime start,
                                    @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime end,
                                    @RequestParam(defaultValue = "1") int page,
                                    @RequestParam(defaultValue = "20") int size) {
        licenseService.requireFeature(LicenseFeatures.AUDIT);
        return auditLogService.page(username, module, keyword, success, start, end, page, size);
    }

    @GetMapping("/modules")
    public List<String> modules() {
        licenseService.requireFeature(LicenseFeatures.AUDIT);
        return auditLogService.listModules();
    }
}
