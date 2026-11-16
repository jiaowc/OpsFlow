package com.opsflow.common.crypto;

import lombok.extern.slf4j.Slf4j;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/**
 * 凭据字段加解密（AES-256-GCM）。
 *
 * <p>密文格式：{@code enc:v1:<Base64(iv[12] | ciphertext | tag[16])>}。
 * 解密时没有该前缀的值视为历史明文原样返回，便于平滑升级。</p>
 *
 * <p>主密钥来源（按优先级）：</p>
 * <ol>
 *   <li>环境变量 / 系统属性 {@code OPSFLOW_SECRET_KEY}：Base64 编码的 32 字节；</li>
 *   <li>密钥文件 {@code OPSFLOW_SECRET_KEY_FILE}（默认 {@code ./data/secret.key}），不存在时首次自动生成（权限 600）。</li>
 * </ol>
 * <p>MyBatis TypeHandler 不由 Spring 管理，因此这里用静态懒加载，不依赖容器启动顺序。</p>
 */
@Slf4j
public final class SecretCipher {

    public static final String PREFIX = "enc:v1:";

    static final String ENV_KEY = "OPSFLOW_SECRET_KEY";
    static final String ENV_KEY_FILE = "OPSFLOW_SECRET_KEY_FILE";
    static final String DEFAULT_KEY_FILE = "./data/secret.key";

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int KEY_BYTES = 32;
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private static final SecureRandom RANDOM = new SecureRandom();

    private static volatile SecretKey key;

    private SecretCipher() {
    }

    public static boolean isEncrypted(String value) {
        return value != null && value.startsWith(PREFIX);
    }

    /**
     * 加密；null / 空串 / 已加密的值原样返回
     */
    public static String encrypt(String plaintext) {
        if (plaintext == null || plaintext.isEmpty() || isEncrypted(plaintext)) {
            return plaintext;
        }
        try {
            byte[] iv = new byte[IV_BYTES];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key(), new GCMParameterSpec(TAG_BITS, iv));
            byte[] ct = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[IV_BYTES + ct.length];
            System.arraycopy(iv, 0, out, 0, IV_BYTES);
            System.arraycopy(ct, 0, out, IV_BYTES, ct.length);
            return PREFIX + Base64.getEncoder().encodeToString(out);
        } catch (Exception e) {
            throw new IllegalStateException("凭据加密失败", e);
        }
    }

    /**
     * 解密；无前缀的值视为历史明文原样返回
     *
     * @throws SecretDecryptException 密钥不匹配或密文被篡改
     */
    public static String decrypt(String value) {
        if (!isEncrypted(value)) {
            return value;
        }
        try {
            byte[] raw = Base64.getDecoder().decode(value.substring(PREFIX.length()));
            if (raw.length <= IV_BYTES) {
                throw new SecretDecryptException("密文长度无效");
            }
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key(),
                    new GCMParameterSpec(TAG_BITS, Arrays.copyOfRange(raw, 0, IV_BYTES)));
            byte[] pt = cipher.doFinal(raw, IV_BYTES, raw.length - IV_BYTES);
            return new String(pt, StandardCharsets.UTF_8);
        } catch (SecretDecryptException e) {
            throw e;
        } catch (Exception e) {
            throw new SecretDecryptException("凭据解密失败：主密钥与加密时不一致，或密文已损坏", e);
        }
    }

    /** 启动时调用，尽早加载 / 生成密钥并在日志中提示来源 */
    public static void init() {
        key();
    }

    /** 仅供测试：注入固定密钥 */
    static void useKeyForTest(byte[] raw) {
        key = raw != null ? new SecretKeySpec(raw, "AES") : null;
    }

    private static SecretKey key() {
        SecretKey k = key;
        if (k == null) {
            synchronized (SecretCipher.class) {
                k = key;
                if (k == null) {
                    k = new SecretKeySpec(loadKeyBytes(), "AES");
                    key = k;
                }
            }
        }
        return k;
    }

    private static byte[] loadKeyBytes() {
        String configured = setting(ENV_KEY);
        if (configured != null) {
            byte[] raw = decodeKey(configured, ENV_KEY);
            log.info("凭据加密主密钥来源: 环境变量 {}", ENV_KEY);
            return raw;
        }
        String fileSetting = setting(ENV_KEY_FILE);
        Path file = Paths.get(fileSetting != null ? fileSetting : DEFAULT_KEY_FILE).toAbsolutePath().normalize();
        try {
            if (Files.exists(file)) {
                byte[] raw = decodeKey(new String(Files.readAllBytes(file), StandardCharsets.US_ASCII).trim(), file.toString());
                log.info("凭据加密主密钥来源: 密钥文件 {}", file);
                return raw;
            }
            return generateKeyFile(file);
        } catch (IOException e) {
            throw new IllegalStateException("读取凭据主密钥文件失败: " + file + "（可设置环境变量 " + ENV_KEY + "）", e);
        }
    }

    private static byte[] generateKeyFile(Path file) throws IOException {
        byte[] raw = new byte[KEY_BYTES];
        RANDOM.nextBytes(raw);
        if (file.getParent() != null) {
            Files.createDirectories(file.getParent());
        }
        try {
            try {
                Files.createFile(file, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            } catch (UnsupportedOperationException e) {
                Files.createFile(file);
            }
        } catch (FileAlreadyExistsException e) {
            // 并发启动时另一进程已生成，读取它
            return decodeKey(new String(Files.readAllBytes(file), StandardCharsets.US_ASCII).trim(), file.toString());
        }
        Files.write(file, (Base64.getEncoder().encodeToString(raw) + "\n").getBytes(StandardCharsets.US_ASCII));
        log.warn("未配置 {}，已生成凭据加密主密钥文件: {}。请务必备份该文件：丢失后已保存的凭据将无法解密，只能重新录入", ENV_KEY, file);
        return raw;
    }

    private static byte[] decodeKey(String base64, String source) {
        byte[] raw;
        try {
            raw = Base64.getDecoder().decode(base64.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("凭据主密钥不是合法的 Base64: " + source);
        }
        if (raw.length != KEY_BYTES) {
            throw new IllegalStateException("凭据主密钥长度必须为 32 字节（Base64 后 44 个字符）: " + source
                    + "，可用 `openssl rand -base64 32` 生成");
        }
        return raw;
    }

    private static String setting(String name) {
        String v = System.getProperty(name);
        if (v == null || v.trim().isEmpty()) {
            v = System.getenv(name);
        }
        return v == null || v.trim().isEmpty() ? null : v.trim();
    }

    /** 解密失败（密钥不匹配 / 密文损坏） */
    public static class SecretDecryptException extends RuntimeException {
        public SecretDecryptException(String message) {
            super(message);
        }

        public SecretDecryptException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
