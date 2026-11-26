package com.opsflow.common.security;

import java.io.ByteArrayOutputStream;
import java.util.Locale;

/**
 * RFC 4648 Base32（字母表 A–Z、2–7），用于动态码密钥的展示和录入。
 * <p>编码不输出填充符 {@code =}；解码忽略大小写、空格、连字符和末尾的填充符。</p>
 */
public final class Base32 {

    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

    private Base32() {
    }

    public static String encode(byte[] data) {
        StringBuilder out = new StringBuilder((data.length * 8 + 4) / 5);
        int buffer = 0;
        int bits = 0;
        for (byte b : data) {
            buffer = (buffer << 8) | (b & 0xff);
            bits += 8;
            while (bits >= 5) {
                out.append(ALPHABET.charAt((buffer >> (bits - 5)) & 0x1f));
                bits -= 5;
            }
        }
        if (bits > 0) {
            out.append(ALPHABET.charAt((buffer << (5 - bits)) & 0x1f));
        }
        return out.toString();
    }

    /** @throws IllegalArgumentException 含有字母表以外的字符 */
    public static byte[] decode(String text) {
        String s = text == null ? "" : text.replace(" ", "").replace("-", "").toUpperCase(Locale.ROOT);
        int end = s.length();
        while (end > 0 && s.charAt(end - 1) == '=') {
            end--;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(end * 5 / 8);
        int buffer = 0;
        int bits = 0;
        for (int i = 0; i < end; i++) {
            int v = ALPHABET.indexOf(s.charAt(i));
            if (v < 0) {
                throw new IllegalArgumentException("Base32 含非法字符");
            }
            buffer = (buffer << 5) | v;
            bits += 5;
            if (bits >= 8) {
                out.write((buffer >> (bits - 8)) & 0xff);
                bits -= 8;
            }
        }
        return out.toByteArray();
    }
}
