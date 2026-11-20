package com.opsflow.integration.ssh;

import com.jcraft.jsch.HostKey;
import com.jcraft.jsch.HostKeyRepository;
import com.jcraft.jsch.UserInfo;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;

/**
 * 单个节点的主机公钥仓库，实现「首次信任（TOFU）」：
 * <ul>
 *   <li>未记录过公钥：接受本次公钥，并暴露给调用方在连接成功后持久化；</li>
 *   <li>已记录且一致：通过；</li>
 *   <li>已记录但不一致：返回 CHANGED，JSch 在 StrictHostKeyChecking=yes 下拒绝连接（防中间人）。</li>
 * </ul>
 * 每次连接 new 一个实例，非线程共享。
 */
public class TofuHostKeyRepository implements HostKeyRepository {

    private static final String REPO_ID = "opsflow-build-node";

    /** 库中记录的公钥 blob（为空表示首次连接） */
    private final byte[] trustedKey;

    /** 本次握手看到的公钥 blob */
    private byte[] presentedKey;

    private boolean mismatch;

    /**
     * @param storedHostKey 库中保存的 "类型 Base64" 或纯 Base64；为空表示尚未记录
     */
    public TofuHostKeyRepository(String storedHostKey) {
        this.trustedKey = decodeStored(storedHostKey);
    }

    @Override
    public int check(String host, byte[] key) {
        presentedKey = key != null ? key.clone() : null;
        if (trustedKey == null) {
            return OK;
        }
        if (key != null && MessageDigest.isEqual(trustedKey, key)) {
            return OK;
        }
        mismatch = true;
        return CHANGED;
    }

    /** 首次连接时本次需要持久化的公钥（"类型 Base64"），已信任或未握手时返回 null */
    public String newlyTrustedHostKey() {
        if (trustedKey != null || presentedKey == null) {
            return null;
        }
        return encode(presentedKey);
    }

    public boolean isMismatch() {
        return mismatch;
    }

    public String trustedFingerprint() {
        return trustedKey != null ? fingerprint(trustedKey) : null;
    }

    public String presentedFingerprint() {
        return presentedKey != null ? fingerprint(presentedKey) : null;
    }

    @Override
    public void add(HostKey hostkey, UserInfo ui) {
        // 由 SshClient 在连接成功后统一持久化，这里不做处理
    }

    @Override
    public void remove(String host, String type) {
    }

    @Override
    public void remove(String host, String type, byte[] key) {
    }

    @Override
    public String getKnownHostsRepositoryID() {
        return REPO_ID;
    }

    @Override
    public HostKey[] getHostKey() {
        return getHostKey(null, null);
    }

    /**
     * JSch 用它来优先协商「已知的主机公钥类型」，保证后续连接拿到同一类型的公钥做比对
     */
    @Override
    public HostKey[] getHostKey(String host, String type) {
        if (trustedKey == null) {
            return new HostKey[0];
        }
        try {
            HostKey hk = new HostKey(host != null ? host : "", HostKey.GUESS, trustedKey);
            if (type != null && !type.equals(hk.getType())) {
                return new HostKey[0];
            }
            return new HostKey[]{hk};
        } catch (Exception e) {
            return new HostKey[0];
        }
    }

    /** OpenSSH 风格指纹：SHA256:Base64（无填充） */
    public static String fingerprint(byte[] key) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(key);
            return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(digest);
        } catch (Exception e) {
            throw new IllegalStateException("计算主机指纹失败", e);
        }
    }

    /** 由存储值计算指纹，存储值无效时返回 null */
    public static String fingerprintOfStored(String storedHostKey) {
        byte[] key = decodeStored(storedHostKey);
        return key != null ? fingerprint(key) : null;
    }

    static String encode(byte[] key) {
        return keyType(key) + " " + Base64.getEncoder().encodeToString(key);
    }

    static byte[] decodeStored(String stored) {
        if (stored == null || stored.trim().isEmpty()) {
            return null;
        }
        String s = stored.trim();
        int space = s.lastIndexOf(' ');
        String b64 = space >= 0 ? s.substring(space + 1) : s;
        try {
            byte[] key = Base64.getDecoder().decode(b64);
            return key.length > 0 ? key : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** SSH 公钥 blob 开头是 uint32 长度 + 类型名 */
    private static String keyType(byte[] key) {
        if (key.length >= 4) {
            int len = ((key[0] & 0xff) << 24) | ((key[1] & 0xff) << 16) | ((key[2] & 0xff) << 8) | (key[3] & 0xff);
            if (len > 0 && len <= 64 && 4 + len <= key.length) {
                return new String(Arrays.copyOfRange(key, 4, 4 + len), StandardCharsets.US_ASCII);
            }
        }
        return "unknown";
    }
}
