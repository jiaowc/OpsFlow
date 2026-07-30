package com.opsflow.integration.feishu;

import org.springframework.util.StringUtils;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

/**
 * 飞书回调校验与解密工具
 */
public final class FeishuCallbackSecurity {

    private FeishuCallbackSecurity() {
    }

    /**
     * 校验消息卡片请求签名：sha1(timestamp + nonce + token + body)
     */
    public static boolean verifySignature(String timestamp, String nonce, String token,
                                          String body, String signature) {
        if (!StringUtils.hasText(signature) || !StringUtils.hasText(token)) {
            return false;
        }
        String content = nullToEmpty(timestamp) + nullToEmpty(nonce) + token + nullToEmpty(body);
        String actual = sha1Hex(content);
        return signature.equalsIgnoreCase(actual);
    }

    public static String sha1Hex(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            byte[] hash = digest.digest(content.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(b & 0xff);
                if (hex.length() == 1) {
                    sb.append('0');
                }
                sb.append(hex);
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-1 计算失败", e);
        }
    }

    /**
     * 解密事件订阅加密 body（Encrypt Key）
     */
    public static String decrypt(String encrypt, String encryptKey) {
        if (!StringUtils.hasText(encrypt) || !StringUtils.hasText(encryptKey)) {
            return null;
        }
        try {
            byte[] keyBs = MessageDigest.getInstance("SHA-256")
                    .digest(encryptKey.getBytes(StandardCharsets.UTF_8));
            byte[] decode = Base64.getDecoder().decode(encrypt);
            Cipher cipher = Cipher.getInstance("AES/CBC/NOPADDING");
            byte[] iv = new byte[16];
            System.arraycopy(decode, 0, iv, 0, 16);
            byte[] data = new byte[decode.length - 16];
            System.arraycopy(decode, 16, data, 0, data.length);
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(keyBs, "AES"), new IvParameterSpec(iv));
            byte[] decrypted = cipher.doFinal(data);
            int pad = decrypted[decrypted.length - 1];
            if (pad < 1 || pad > 16) {
                return new String(decrypted, StandardCharsets.UTF_8).trim();
            }
            return new String(decrypted, 0, decrypted.length - pad, StandardCharsets.UTF_8).trim();
        } catch (Exception e) {
            throw new IllegalStateException("飞书事件解密失败", e);
        }
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
