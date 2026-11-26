package com.opsflow.common.security;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;

/**
 * 基于时间的动态码（RFC 6238，HMAC-SHA1）：6 位、30 秒一个时间窗，校验时前后各容 1 个时间窗。
 * <p>时间窗序号 {@code step = floor(秒级时间戳 / 30)}，调用方记下最后一次用过的序号用来防重放。</p>
 */
public final class Totp {

    public static final int DIGITS = 6;
    public static final int PERIOD_SECONDS = 30;
    /** 前后各容几个时间窗 */
    public static final int WINDOW = 1;
    /** 密钥长度（字节），对应 32 位 Base32 */
    public static final int SECRET_BYTES = 20;

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int[] POW10 = {1, 10, 100, 1000, 10000, 100000, 1000000, 10000000, 100000000};

    private Totp() {
    }

    /** 新的随机密钥，Base32 编码 */
    public static String newSecret() {
        byte[] key = new byte[SECRET_BYTES];
        RANDOM.nextBytes(key);
        return Base32.encode(key);
    }

    public static long stepAt(long epochMillis) {
        return Math.floorDiv(epochMillis / 1000L, PERIOD_SECONDS);
    }

    /** 指定时间窗的动态码（RFC 4226 动态截断），默认 6 位 */
    public static String code(byte[] key, long step) {
        return code(key, step, DIGITS, "HmacSHA1");
    }

    /** 供测试向量使用：可指定位数和算法（HmacSHA1 / HmacSHA256 / HmacSHA512） */
    static String code(byte[] key, long step, int digits, String algorithm) {
        try {
            Mac mac = Mac.getInstance(algorithm);
            mac.init(new SecretKeySpec(key, algorithm));
            byte[] hash = mac.doFinal(ByteBuffer.allocate(8).putLong(step).array());
            int offset = hash[hash.length - 1] & 0x0f;
            int binary = ((hash[offset] & 0x7f) << 24)
                    | ((hash[offset + 1] & 0xff) << 16)
                    | ((hash[offset + 2] & 0xff) << 8)
                    | (hash[offset + 3] & 0xff);
            String s = Integer.toString(binary % POW10[digits]);
            StringBuilder padded = new StringBuilder(digits);
            for (int i = s.length(); i < digits; i++) {
                padded.append('0');
            }
            return padded.append(s).toString();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("动态码计算失败", e);
        }
    }

    /**
     * 校验动态码，返回匹配的时间窗序号；不匹配返回 -1。
     *
     * @param secretBase32 Base32 密钥
     * @param code         用户输入，允许夹空格
     * @param epochMillis  当前时间
     * @param afterStep    只接受大于这个序号的时间窗（防重放）；没有用过时传 {@link Long#MIN_VALUE}
     */
    public static long verify(String secretBase32, String code, long epochMillis, long afterStep) {
        if (secretBase32 == null || code == null) {
            return -1;
        }
        String input = code.replace(" ", "");
        if (input.length() != DIGITS || !input.chars().allMatch(Character::isDigit)) {
            return -1;
        }
        byte[] key;
        try {
            key = Base32.decode(secretBase32);
        } catch (IllegalArgumentException e) {
            return -1;
        }
        long now = stepAt(epochMillis);
        long matched = -1;
        byte[] given = input.getBytes(StandardCharsets.US_ASCII);
        // 三个时间窗都算一遍，避免按匹配位置泄露耗时差
        for (long step = now - WINDOW; step <= now + WINDOW; step++) {
            boolean equal = MessageDigest.isEqual(code(key, step).getBytes(StandardCharsets.US_ASCII), given);
            if (equal && step > afterStep && matched < 0) {
                matched = step;
            }
        }
        return matched;
    }

    /** 验证器 App 扫码用的地址：{@code otpauth://totp/<发行方>:<账号>?secret=...&issuer=...} */
    public static String otpauthUri(String issuer, String account, String secretBase32) {
        String label = encode(issuer) + ":" + encode(account);
        return "otpauth://totp/" + label + "?secret=" + secretBase32 + "&issuer=" + encode(issuer)
                + "&algorithm=SHA1&digits=" + DIGITS + "&period=" + PERIOD_SECONDS;
    }

    /** otpauth 地址里的百分号编码：空格编码为 %20，不用 + */
    private static String encode(String s) {
        try {
            return java.net.URLEncoder.encode(s, StandardCharsets.UTF_8.name()).replace("+", "%20");
        } catch (java.io.UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }
}
