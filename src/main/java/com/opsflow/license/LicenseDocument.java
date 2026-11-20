package com.opsflow.license;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;

/**
 * 导入的 License 文档：payload + signature
 */
@Data
public class LicenseDocument {

    private LicensePayload payload;

    private String signature;

    public static LicenseDocument parse(String raw, ObjectMapper mapper) throws Exception {
        String text = raw == null ? "" : raw.trim();
        if (text.isEmpty()) {
            throw new IllegalArgumentException("License 内容为空");
        }
        // 允许整段 Base64 包裹
        if (!text.startsWith("{")) {
            try {
                text = new String(java.util.Base64.getDecoder().decode(text.replaceAll("\\s", "")),
                        java.nio.charset.StandardCharsets.UTF_8).trim();
            } catch (Exception e) {
                throw new IllegalArgumentException("License 格式无效");
            }
        }
        LicenseDocument doc = mapper.readValue(text, LicenseDocument.class);
        if (doc.getPayload() == null || doc.getSignature() == null || doc.getSignature().trim().isEmpty()) {
            throw new IllegalArgumentException("License 缺少 payload 或 signature");
        }
        return doc;
    }
}
