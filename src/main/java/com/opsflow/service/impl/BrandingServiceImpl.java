package com.opsflow.service.impl;

import com.opsflow.common.exception.BusinessException;
import com.opsflow.license.LicenseFeatures;
import com.opsflow.service.BrandingService;
import com.opsflow.service.LicenseService;
import com.opsflow.service.SystemConfigService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

@Slf4j
@Service
public class BrandingServiceImpl implements BrandingService {

    public static final String CONFIG_TYPE = "branding";
    public static final String KEY_FILE = "logoFile";
    public static final String KEY_CONTENT_TYPE = "logoContentType";
    public static final String KEY_UPDATED_AT = "updatedAt";
    public static final String KEY_TITLE = "title";

    private static final long MAX_BYTES = 512 * 1024L;

    @Value("${opsflow.branding.dir:./data/branding}")
    private String brandingDir;

    @Autowired
    private LicenseService licenseService;

    @Autowired
    private SystemConfigService systemConfigService;

    @Override
    public Map<String, Object> publicBranding() {
        Map<String, Object> result = new HashMap<>();
        boolean licensed = licenseService.isFeatureEnabled(LicenseFeatures.BRANDING);
        boolean hasFile = licensed && resolveLogoFile() != null;
        result.put("licensed", licensed);
        result.put("custom", hasFile);
        result.put("logoUrl", hasFile ? "/api/branding/logo?v=" + logoVersion() : null);
        String title = systemConfigService.get(CONFIG_TYPE, KEY_TITLE, "");
        result.put("title", StringUtils.hasText(title) ? title.trim() : "OPSFLOW");
        return result;
    }

    @Override
    public Map<String, Object> adminStatus() {
        Map<String, Object> result = publicBranding();
        result.put("updatedAt", systemConfigService.get(CONFIG_TYPE, KEY_UPDATED_AT, ""));
        result.put("contentType", systemConfigService.get(CONFIG_TYPE, KEY_CONTENT_TYPE, ""));
        result.put("fileName", systemConfigService.get(CONFIG_TYPE, KEY_FILE, ""));
        return result;
    }

    @Override
    public Map<String, Object> uploadLogo(MultipartFile file) {
        licenseService.requireFeature(LicenseFeatures.BRANDING);
        if (file == null || file.isEmpty()) {
            throw new BusinessException("请选择 Logo 图片文件");
        }
        if (file.getSize() > MAX_BYTES) {
            throw new BusinessException("Logo 文件不能超过 512KB");
        }
        String contentType = normalizeContentType(file.getContentType(), file.getOriginalFilename());
        if (contentType == null) {
            throw new BusinessException("仅支持 PNG / JPG / WEBP / SVG 格式");
        }
        String ext = extensionFor(contentType);
        Path dir = Paths.get(brandingDir).toAbsolutePath().normalize();
        try {
            Files.createDirectories(dir);
            // 清理旧文件
            clearLogoFiles(dir);
            Path target = dir.resolve("logo" + ext);
            Files.copy(file.getInputStream(), target, StandardCopyOption.REPLACE_EXISTING);

            Map<String, String> values = new HashMap<>();
            values.put(KEY_FILE, target.getFileName().toString());
            values.put(KEY_CONTENT_TYPE, contentType);
            values.put(KEY_UPDATED_AT, LocalDateTime.now().toString());
            systemConfigService.saveBatch(CONFIG_TYPE, values);
            log.info("企业 Logo 已更新: {}", target);
            return adminStatus();
        } catch (BusinessException e) {
            throw e;
        } catch (IOException e) {
            throw new BusinessException("保存 Logo 失败: " + e.getMessage());
        }
    }

    @Override
    public void clearLogo() {
        licenseService.requireFeature(LicenseFeatures.BRANDING);
        Path dir = Paths.get(brandingDir).toAbsolutePath().normalize();
        clearLogoFiles(dir);
        Map<String, String> values = new HashMap<>();
        values.put(KEY_FILE, "");
        values.put(KEY_CONTENT_TYPE, "");
        values.put(KEY_UPDATED_AT, "");
        systemConfigService.saveBatch(CONFIG_TYPE, values);
    }

    @Override
    public Path resolveLogoFile() {
        String fileName = systemConfigService.get(CONFIG_TYPE, KEY_FILE, "");
        if (!StringUtils.hasText(fileName)) {
            return null;
        }
        // 防路径穿越：只允许简单文件名
        if (fileName.contains("..") || fileName.contains("/") || fileName.contains("\\")) {
            return null;
        }
        Path path = Paths.get(brandingDir).toAbsolutePath().normalize().resolve(fileName);
        if (!Files.isRegularFile(path)) {
            return null;
        }
        return path;
    }

    @Override
    public String resolveLogoContentType() {
        String ct = systemConfigService.get(CONFIG_TYPE, KEY_CONTENT_TYPE, "");
        return StringUtils.hasText(ct) ? ct : "image/png";
    }

    private String logoVersion() {
        String updated = systemConfigService.get(CONFIG_TYPE, KEY_UPDATED_AT, "");
        return StringUtils.hasText(updated) ? String.valueOf(updated.hashCode()) : "1";
    }

    private void clearLogoFiles(Path dir) {
        if (!Files.isDirectory(dir)) {
            return;
        }
        try {
            Files.list(dir).filter(p -> {
                String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
                return n.startsWith("logo.");
            }).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    log.warn("删除旧 Logo 失败: {}", p, e);
                }
            });
        } catch (IOException e) {
            log.warn("遍历品牌目录失败: {}", dir, e);
        }
    }

    private String normalizeContentType(String contentType, String originalName) {
        String ct = contentType == null ? "" : contentType.trim().toLowerCase(Locale.ROOT);
        if ("image/png".equals(ct) || "image/jpeg".equals(ct) || "image/jpg".equals(ct)
                || "image/webp".equals(ct) || "image/svg+xml".equals(ct)) {
            return "image/jpg".equals(ct) ? "image/jpeg" : ct;
        }
        String name = originalName == null ? "" : originalName.toLowerCase(Locale.ROOT);
        if (name.endsWith(".png")) return "image/png";
        if (name.endsWith(".jpg") || name.endsWith(".jpeg")) return "image/jpeg";
        if (name.endsWith(".webp")) return "image/webp";
        if (name.endsWith(".svg")) return "image/svg+xml";
        return null;
    }

    private String extensionFor(String contentType) {
        if ("image/png".equals(contentType)) return ".png";
        if ("image/jpeg".equals(contentType)) return ".jpg";
        if ("image/webp".equals(contentType)) return ".webp";
        if ("image/svg+xml".equals(contentType)) return ".svg";
        return ".png";
    }
}
