package com.opsflow.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opsflow.api.dto.LicenseStatusDTO;
import com.opsflow.common.exception.BusinessException;
import com.opsflow.license.LicenseCrypto;
import com.opsflow.license.LicenseDocument;
import com.opsflow.license.LicenseFeatures;
import com.opsflow.license.LicensePayload;
import com.opsflow.service.LicenseService;
import com.opsflow.service.SystemConfigService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.util.StreamUtils;
import org.springframework.util.StringUtils;

import javax.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.security.PublicKey;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
@Service
public class LicenseServiceImpl implements LicenseService {

    public static final String CONFIG_TYPE = "license";
    public static final String KEY_RAW = "raw";

    private static final DateTimeFormatter[] TIME_FORMATS = new DateTimeFormatter[]{
            DateTimeFormatter.ISO_LOCAL_DATE_TIME,
            DateTimeFormatter.ISO_OFFSET_DATE_TIME,
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd")
    };

    /**
     * License 强校验总开关。
     * <p>
     * 当前默认关闭（false）：不拦截功能，上线审批等均可直接使用。
     * 后期需要重新启用时：将配置 {@code opsflow.license.enforcement-enabled} 改为 {@code true} 并重启。
     * </p>
     */
    @Value("${opsflow.license.enforcement-enabled:false}")
    private boolean enforcementEnabled;

    @Autowired
    private SystemConfigService systemConfigService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private PublicKey publicKey;

    private final AtomicReference<CachedLicense> cache = new AtomicReference<>();

    @PostConstruct
    public void init() {
        if (!enforcementEnabled) {
            log.warn("License 强校验已关闭（opsflow.license.enforcement-enabled=false），所有功能开放；后期改为 true 可重新启用");
        }
        try {
            ClassPathResource resource = new ClassPathResource("license/public.pem");
            String pem = StreamUtils.copyToString(resource.getInputStream(), StandardCharsets.UTF_8);
            publicKey = LicenseCrypto.loadPublicKeyPem(pem);
            log.info("License 公钥已加载");
        } catch (Exception e) {
            log.error("加载 License 公钥失败，功能授权将不可用: {}", e.getMessage());
            publicKey = null;
        }
        refreshCache();
    }

    @Override
    public boolean isFeatureEnabled(String feature) {
        // 关闭强校验时：视为全部功能已授权
        if (!enforcementEnabled) {
            return true;
        }
        if (!StringUtils.hasText(feature)) {
            return true;
        }
        CachedLicense cached = current();
        return cached != null && cached.valid && cached.features.contains(feature.trim().toLowerCase(Locale.ROOT));
    }

    @Override
    public void requireFeature(String feature) {
        // 关闭强校验时：跳过拦截
        if (!enforcementEnabled) {
            return;
        }
        if (!isFeatureEnabled(feature)) {
            throw new BusinessException("未授权功能「" + feature + "」，请导入有效 License 后使用上线审批相关能力");
        }
    }

    @Override
    public LicenseStatusDTO getStatus() {
        LicenseStatusDTO dto = new LicenseStatusDTO();
        dto.setEnforcementEnabled(enforcementEnabled);
        if (!enforcementEnabled) {
            dto.setPresent(false);
            dto.setValid(true);
            dto.setDeployApprovalEnabled(true);
            dto.setFeatures(new ArrayList<>(Arrays.asList(LicenseFeatures.DEPLOY_APPROVAL)));
            dto.setMessage("License 强校验已关闭（配置 enforcement-enabled=false），所有功能开放");
            return dto;
        }
        CachedLicense cached = current();
        if (cached == null || !cached.present) {
            dto.setPresent(false);
            dto.setValid(false);
            dto.setDeployApprovalEnabled(false);
            dto.setMessage("尚未导入 License");
            return dto;
        }
        dto.setPresent(true);
        dto.setValid(cached.valid);
        dto.setCustomer(cached.customer);
        dto.setLicenseId(cached.licenseId);
        dto.setIssuedAt(cached.issuedAt);
        dto.setExpiresAt(cached.expiresAt);
        dto.setFeatures(new ArrayList<>(cached.features));
        dto.setDeployApprovalEnabled(cached.valid && cached.features.contains(LicenseFeatures.DEPLOY_APPROVAL));
        dto.setMessage(cached.message);
        return dto;
    }

    @Override
    public LicenseStatusDTO importLicense(String rawLicense) {
        if (!enforcementEnabled) {
            throw new BusinessException("当前已关闭 License 强校验，无需导入；如需启用请将 opsflow.license.enforcement-enabled 设为 true");
        }
        if (publicKey == null) {
            throw new BusinessException("系统未配置 License 公钥，无法验签");
        }
        LicenseDocument doc;
        try {
            doc = LicenseDocument.parse(rawLicense, objectMapper);
        } catch (Exception e) {
            throw new BusinessException("License 解析失败: " + e.getMessage());
        }
        CachedLicense verified = verifyDocument(doc);
        if (!verified.valid) {
            throw new BusinessException(verified.message != null ? verified.message : "License 无效");
        }
        try {
            String normalized = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(doc);
            Map<String, String> values = new HashMap<>();
            values.put(KEY_RAW, normalized);
            values.put("customer", nullToEmpty(verified.customer));
            values.put("licenseId", nullToEmpty(verified.licenseId));
            values.put("expiresAt", nullToEmpty(verified.expiresAt));
            values.put("features", String.join(",", verified.features));
            values.put("importedAt", LocalDateTime.now().toString());
            systemConfigService.saveBatch(CONFIG_TYPE, values);
        } catch (Exception e) {
            throw new BusinessException("保存 License 失败: " + e.getMessage());
        }
        cache.set(verified);
        return getStatus();
    }

    @Override
    public void clearLicense() {
        if (!enforcementEnabled) {
            throw new BusinessException("当前已关闭 License 强校验，无需清除；如需启用请将 opsflow.license.enforcement-enabled 设为 true");
        }
        Map<String, String> values = new HashMap<>();
        values.put(KEY_RAW, "");
        values.put("customer", "");
        values.put("licenseId", "");
        values.put("expiresAt", "");
        values.put("features", "");
        values.put("importedAt", "");
        systemConfigService.saveBatch(CONFIG_TYPE, values);
        cache.set(null);
    }

    @Override
    public List<String> enabledFeatures() {
        if (!enforcementEnabled) {
            return new ArrayList<>(Arrays.asList(LicenseFeatures.DEPLOY_APPROVAL));
        }
        CachedLicense cached = current();
        if (cached == null || !cached.valid) {
            return Collections.emptyList();
        }
        return new ArrayList<>(cached.features);
    }

    private CachedLicense current() {
        CachedLicense cached = cache.get();
        if (cached != null) {
            // 过期重验
            if (cached.valid && isExpired(cached.expiresAt)) {
                cached.valid = false;
                cached.message = "License 已过期";
                cached.features = Collections.emptyList();
            }
            return cached;
        }
        return refreshCache();
    }

    private CachedLicense refreshCache() {
        String raw = systemConfigService.get(CONFIG_TYPE, KEY_RAW);
        if (!StringUtils.hasText(raw)) {
            cache.set(null);
            return null;
        }
        try {
            LicenseDocument doc = LicenseDocument.parse(raw, objectMapper);
            CachedLicense verified = verifyDocument(doc);
            cache.set(verified);
            return verified;
        } catch (Exception e) {
            log.warn("已存储 License 无效: {}", e.getMessage());
            CachedLicense bad = new CachedLicense();
            bad.present = true;
            bad.valid = false;
            bad.message = "已存储 License 无效: " + e.getMessage();
            bad.features = Collections.emptyList();
            cache.set(bad);
            return bad;
        }
    }

    private CachedLicense verifyDocument(LicenseDocument doc) {
        CachedLicense result = new CachedLicense();
        result.present = true;
        LicensePayload payload = doc.getPayload();
        if (payload == null) {
            result.valid = false;
            result.message = "License payload 为空";
            result.features = Collections.emptyList();
            return result;
        }
        result.customer = payload.getCustomer();
        result.licenseId = payload.getLicenseId();
        result.issuedAt = payload.getIssuedAt();
        result.expiresAt = payload.getExpiresAt();

        if (publicKey == null) {
            result.valid = false;
            result.message = "公钥未加载";
            result.features = Collections.emptyList();
            return result;
        }
        if (!LicenseCrypto.verify(payload, doc.getSignature(), publicKey)) {
            result.valid = false;
            result.message = "License 签名校验失败";
            result.features = Collections.emptyList();
            return result;
        }
        if (StringUtils.hasText(payload.getProduct())
                && !"OpsFlow".equalsIgnoreCase(payload.getProduct().trim())) {
            result.valid = false;
            result.message = "License 产品不匹配";
            result.features = Collections.emptyList();
            return result;
        }
        if (isExpired(payload.getExpiresAt())) {
            result.valid = false;
            result.message = "License 已过期";
            result.features = Collections.emptyList();
            return result;
        }
        if (isNotYetValid(payload.getIssuedAt())) {
            result.valid = false;
            result.message = "License 尚未生效";
            result.features = Collections.emptyList();
            return result;
        }
        List<String> features = new ArrayList<>();
        if (payload.getFeatures() != null) {
            for (String f : payload.getFeatures()) {
                if (StringUtils.hasText(f)) {
                    features.add(f.trim().toLowerCase(Locale.ROOT));
                }
            }
        }
        result.features = features;
        result.valid = true;
        result.message = "License 有效";
        return result;
    }

    private boolean isExpired(String expiresAt) {
        if (!StringUtils.hasText(expiresAt)) {
            return false;
        }
        LocalDateTime exp = parseTime(expiresAt);
        return exp != null && LocalDateTime.now().isAfter(exp);
    }

    private boolean isNotYetValid(String issuedAt) {
        if (!StringUtils.hasText(issuedAt)) {
            return false;
        }
        LocalDateTime issued = parseTime(issuedAt);
        return issued != null && LocalDateTime.now().isBefore(issued.minusMinutes(5));
    }

    private LocalDateTime parseTime(String value) {
        String v = value.trim();
        if (v.length() == 10) {
            v = v + "T00:00:00";
        }
        for (DateTimeFormatter fmt : TIME_FORMATS) {
            try {
                if (fmt == DateTimeFormatter.ISO_OFFSET_DATE_TIME) {
                    return java.time.OffsetDateTime.parse(value.trim(), fmt).toLocalDateTime();
                }
                return LocalDateTime.parse(v.replace(' ', 'T'),
                        fmt == DateTimeFormatter.ofPattern("yyyy-MM-dd")
                                ? DateTimeFormatter.ISO_LOCAL_DATE_TIME : fmt);
            } catch (DateTimeParseException ignored) {
                // try next
            }
        }
        try {
            return LocalDateTime.parse(v.replace(' ', 'T'));
        } catch (Exception e) {
            log.warn("无法解析 License 时间: {}", value);
            return null;
        }
    }

    private String nullToEmpty(String v) {
        return v == null ? "" : v;
    }

    private static class CachedLicense {
        boolean present;
        boolean valid;
        String customer;
        String licenseId;
        String issuedAt;
        String expiresAt;
        String message;
        List<String> features = Collections.emptyList();
    }
}
