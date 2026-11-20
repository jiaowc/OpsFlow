package com.opsflow.license;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.Iterator;
import java.util.Map;
import java.util.TreeMap;

/**
 * License RSA-SHA256 验签（产品侧仅公钥；签发在独立 License 项目）。
 */
public final class LicenseCrypto {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    private LicenseCrypto() {
    }

    public static String canonicalizePayload(LicensePayload payload) {
        try {
            JsonNode node = MAPPER.valueToTree(payload);
            return MAPPER.writeValueAsString(sortNode(node));
        } catch (Exception e) {
            throw new IllegalStateException("License 载荷序列化失败: " + e.getMessage(), e);
        }
    }

    private static JsonNode sortNode(JsonNode node) {
        if (node == null || !node.isObject()) {
            return node;
        }
        ObjectNode out = MAPPER.createObjectNode();
        TreeMap<String, JsonNode> sorted = new TreeMap<>();
        Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> e = fields.next();
            JsonNode child = e.getValue();
            sorted.put(e.getKey(), (child != null && child.isObject()) ? sortNode(child) : child);
        }
        for (Map.Entry<String, JsonNode> e : sorted.entrySet()) {
            out.set(e.getKey(), e.getValue());
        }
        return out;
    }

    public static boolean verify(LicensePayload payload, String signatureBase64, PublicKey publicKey) {
        try {
            if (payload == null || signatureBase64 == null || publicKey == null) {
                return false;
            }
            String canonical = canonicalizePayload(payload);
            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initVerify(publicKey);
            signature.update(canonical.getBytes(StandardCharsets.UTF_8));
            return signature.verify(Base64.getDecoder().decode(signatureBase64.trim()));
        } catch (Exception e) {
            return false;
        }
    }

    public static PublicKey loadPublicKeyPem(String pem) {
        try {
            byte[] der = decodePem(pem);
            return KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(der));
        } catch (Exception e) {
            throw new IllegalStateException("加载公钥失败: " + e.getMessage(), e);
        }
    }

    private static byte[] decodePem(String pem) {
        String normalized = pem
                .replaceAll("-----BEGIN [^-]+-----", "")
                .replaceAll("-----END [^-]+-----", "")
                .replaceAll("\\s", "");
        return Base64.getDecoder().decode(normalized);
    }
}
