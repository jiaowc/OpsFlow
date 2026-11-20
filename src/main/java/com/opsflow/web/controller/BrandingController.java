package com.opsflow.web.controller;

import com.opsflow.common.exception.BusinessException;
import com.opsflow.license.LicenseFeatures;
import com.opsflow.service.BrandingService;
import com.opsflow.service.LicenseService;
import com.opsflow.service.SystemConfigService;
import com.opsflow.service.impl.BrandingServiceImpl;
import com.opsflow.web.security.RequiresPermission;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * 企业品牌：公开读取 Logo；管理端上传需 branding License。
 */
@RestController
@RequestMapping("/api/branding")
public class BrandingController {

    @Autowired
    private BrandingService brandingService;

    @Autowired
    private LicenseService licenseService;

    @Autowired
    private SystemConfigService systemConfigService;

    /** 登录页可匿名调用 */
    @GetMapping("/public")
    public Map<String, Object> publicBranding() {
        return brandingService.publicBranding();
    }

    /** 登录页 / 顶栏图片；无 License 或不存在时 404 */
    @GetMapping("/logo")
    public ResponseEntity<Resource> logo() {
        if (!licenseService.isFeatureEnabled(LicenseFeatures.BRANDING)) {
            return ResponseEntity.notFound().build();
        }
        Path path = brandingService.resolveLogoFile();
        if (path == null) {
            return ResponseEntity.notFound().build();
        }
        String contentType = brandingService.resolveLogoContentType();
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "public, max-age=3600")
                .contentType(MediaType.parseMediaType(contentType))
                .body(new FileSystemResource(path));
    }

    @RequiresPermission("system:config")
    @GetMapping
    public Map<String, Object> status() {
        return brandingService.adminStatus();
    }

    @RequiresPermission("system:config")
    @PostMapping("/logo")
    public Map<String, Object> upload(@RequestParam("file") MultipartFile file) {
        return brandingService.uploadLogo(file);
    }

    @RequiresPermission("system:config")
    @PostMapping("/title")
    public Map<String, Object> saveTitle(@RequestBody Map<String, String> body) {
        licenseService.requireFeature(LicenseFeatures.BRANDING);
        String title = body != null ? body.get("title") : null;
        if (title != null) {
            title = title.trim();
            if (title.length() > 64) {
                throw new BusinessException("品牌名称不能超过 64 个字符");
            }
        }
        Map<String, String> values = new HashMap<>();
        values.put(BrandingServiceImpl.KEY_TITLE, title == null ? "" : title);
        systemConfigService.saveBatch(BrandingServiceImpl.CONFIG_TYPE, values);
        return brandingService.adminStatus();
    }

    @RequiresPermission("system:config")
    @DeleteMapping("/logo")
    public Map<String, Object> clear() {
        brandingService.clearLogo();
        Map<String, Object> result = brandingService.adminStatus();
        result.put("cleared", true);
        return result;
    }
}
