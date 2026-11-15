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
import java.time.temporal.ChronoUnit;
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

    /** 订阅过期后宽限天数：期内商业功能仍可用并提示续费 */
    public static final int GRACE_DAYS = 7;

    private static final List<String> ALL_KNOWN_FEATURES = Collections.unmodifiableList(Arrays.asList(
            LicenseFeatures.DEPLOY_APPROVAL,
            LicenseFeatures.AUDIT,
            LicenseFeatures.NOTIFY_INTEGRATION,
            LicenseFeatures.PROD_ROLLBACK,
            LicenseFeatures.BRANDING
    ));

    private static final DateTimeFormatter[] TIME_FORMATS = new DateTimeFormatter[]{
            DateTimeFormatter.ISO_LOCAL_DATE_TIME,
            DateTimeFormatter.ISO_OFFSET_DATE_TIME,
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd")
    };

    /**
     * License 强校验总开关。
     * <p>
     * 当前默认关闭（false）：不拦截功能，商业能力均可直接使用。
     * 对外交付改为 {@code opsflow.license.enforcement-enabled=true}。
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
        if (!enforcementEnabled) {
            return;
        }
        if (!isFeatureEnabled(feature)) {
            throw new BusinessException(featureDeniedMessage(feature));
        }
    }

    @Override
    public LicenseStatusDTO getStatus() {
        LicenseStatusDTO dto = new LicenseStatusDTO();
        dto.setEnforcementEnabled(enforcementEnabled);
        if (!enforcementEnabled) {
            dto.setPresent(false);
            dto.setValid(true);
            dto.setFeatures(new ArrayList<>(ALL_KNOWN_FEATURES));
            fillFeatureFlags(dto, true);
            dto.setMessage("License 强校验已关闭（配置 enforcement-enabled=false），所有功能开放");
            return dto;
        }
        CachedLicense cached = current();
        if (cached == null || !cached.present) {
            dto.setPresent(false);
            dto.setValid(false);
            fillFeatureFlags(dto, false);
            dto.setMessage("尚未导入 License（当前为 Community：核心 CI/CD 可用）");
            return dto;
        }
        dto.setPresent(true);
        dto.setValid(cached.valid);
        dto.setCustomer(cached.customer);
        dto.setLicenseId(cached.licenseId);
        dto.setPlan(cached.plan);
        dto.setIssuedAt(cached.issuedAt);
        dto.setExpiresAt(cached.expiresAt);
        dto.setInGracePeriod(cached.inGracePeriod);
        dto.setDaysRemaining(daysRemaining(cached.expiresAt));
        dto.setFeatures(new ArrayList<>(cached.features));
        fillFeatureFlags(dto, cached.valid);
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
            values.put("plan", nullToEmpty(verified.plan));
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
        values.put("plan", "");
        values.put("expiresAt", "");
        values.put("features", "");
        values.put("importedAt", "");
        systemConfigService.saveBatch(CONFIG_TYPE, values);
        cache.set(null);
    }

    @Override
    public List<String> enabledFeatures() {
        if (!enforcementEnabled) {
            return new ArrayList<>(ALL_KNOWN_FEATURES);
        }
        CachedLicense cached = current();
        if (cached == null || !cached.valid) {
            return Collections.emptyList();
        }
        return new ArrayList<>(cached.features);
    }

    private void fillFeatureFlags(LicenseStatusDTO dto, boolean enabledBase) {
        List<String> features = dto.getFeatures() != null ? dto.getFeatures() : Collections.emptyList();
        boolean on = enabledBase;
        dto.setDeployApprovalEnabled(on && features.contains(LicenseFeatures.DEPLOY_APPROVAL));
        dto.setAuditEnabled(on && features.contains(LicenseFeatures.AUDIT));
        dto.setNotifyIntegrationEnabled(on && features.contains(LicenseFeatures.NOTIFY_INTEGRATION));
        dto.setProdRollbackEnabled(on && features.contains(LicenseFeatures.PROD_ROLLBACK));
        dto.setBrandingEnabled(on && features.contains(LicenseFeatures.BRANDING));
    }

    private String featureDeniedMessage(String feature) {
        String code = feature == null ? "" : feature.trim().toLowerCase(Locale.ROOT);
        String label;
        if (LicenseFeatures.DEPLOY_APPROVAL.equals(code)) {
            label = "上线审批";
        } else if (LicenseFeatures.AUDIT.equals(code)) {
            label = "操作审计";
        } else if (LicenseFeatures.NOTIFY_INTEGRATION.equals(code)) {
            label = "集成与通知";
        } else if (LicenseFeatures.PROD_ROLLBACK.equals(code)) {
            label = "生产环境回滚";
        } else if (LicenseFeatures.BRANDING.equals(code)) {
            label = "企业品牌定制";
        } else {
            label = code;
        }
        return "未授权功能「" + label + "」（" + code + "），请导入有效订阅 License 后使用";
    }

    private CachedLicense current() {
        CachedLicense cached = cache.get();
        if (cached != null) {
            reevaluateExpiry(cached);
            return cached;
        }
        return refreshCache();
    }

    private void reevaluateExpiry(CachedLicense cached) {
        if (!cached.valid && !cached.inGracePeriod) {
            // 已硬失效则保持；若时钟回拨到宽限内，下面会重算
        }
        if (!StringUtils.hasText(cached.expiresAt)) {
            return;
        }
        LocalDateTime exp = parseTime(cached.expiresAt);
        if (exp == null) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        if (!now.isAfter(exp)) {
            // 未过期：若之前被标过期，需从存储重载；此处仅纠正宽限标记
            if (cached.inGracePeriod) {
                cached.inGracePeriod = false;
            }
            return;
        }
        LocalDateTime graceEnd = exp.plusDays(GRACE_DAYS);
        if (!now.isAfter(graceEnd)) {
            cached.valid = true;
            cached.inGracePeriod = true;
            long left = ChronoUnit.DAYS.between(now.toLocalDate(), graceEnd.toLocalDate());
            cached.message = "订阅已过期，宽限期剩余约 " + Math.max(left, 0) + " 天，请尽快续费导入新 License";
            return;
        }
        cached.valid = false;
        cached.inGracePeriod = false;
        cached.features = Collections.emptyList();
        cached.message = "订阅已过期且超过宽限期，商业功能已关闭（Community 核心仍可用）";
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
        result.plan = payload.getPlan();
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

        if (isHardExpired(payload.getExpiresAt())) {
            result.valid = false;
            result.inGracePeriod = false;
            result.features = Collections.emptyList();
            result.message = "订阅已过期且超过宽限期，商业功能已关闭（Community 核心仍可用）";
            return result;
        }
        if (isPastExpiry(payload.getExpiresAt())) {
            result.valid = true;
            result.inGracePeriod = true;
            result.message = "订阅已过期，处于宽限期内，请尽快续费导入新 License";
            return result;
        }

        result.valid = true;
        result.inGracePeriod = false;
        result.message = StringUtils.hasText(result.plan)
                ? ("License 有效（计划：" + result.plan + "）")
                : "License 有效";
        return result;
    }

    private Long daysRemaining(String expiresAt) {
        if (!StringUtils.hasText(expiresAt)) {
            return null;
        }
        LocalDateTime exp = parseTime(expiresAt);
        if (exp == null) {
            return null;
        }
        return ChronoUnit.DAYS.between(LocalDateTime.now().toLocalDate(), exp.toLocalDate());
    }

    /** 已过 expiresAt（可能仍在宽限内） */
    private boolean isPastExpiry(String expiresAt) {
        if (!StringUtils.hasText(expiresAt)) {
            return false;
        }
        LocalDateTime exp = parseTime(expiresAt);
        return exp != null && LocalDateTime.now().isAfter(exp);
    }

    /** 超过 expiresAt + 宽限期 */
    private boolean isHardExpired(String expiresAt) {
        if (!StringUtils.hasText(expiresAt)) {
            return false;
        }
        LocalDateTime exp = parseTime(expiresAt);
        return exp != null && LocalDateTime.now().isAfter(exp.plusDays(GRACE_DAYS));
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
        boolean inGracePeriod;
        String customer;
        String licenseId;
        String plan;
        String issuedAt;
        String expiresAt;
        String message;
        List<String> features = Collections.emptyList();
    }
}
