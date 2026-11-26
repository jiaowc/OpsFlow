package com.opsflow.common.security;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TotpTest {

    /** RFC 6238 附录 B 的种子 */
    private static final byte[] SEED_SHA1 = "12345678901234567890".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] SEED_SHA256 = "12345678901234567890123456789012".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] SEED_SHA512 =
            "1234567890123456789012345678901234567890123456789012345678901234".getBytes(StandardCharsets.US_ASCII);

    @Test
    void RFC6238测试向量_8位() {
        long[] times = {59L, 1111111109L, 1111111111L, 1234567890L, 2000000000L, 20000000000L};
        String[] sha1 = {"94287082", "07081804", "14050471", "89005924", "69279037", "65353130"};
        String[] sha256 = {"46119246", "68084774", "67062674", "91819424", "90698825", "77737706"};
        String[] sha512 = {"90693936", "25091201", "99943326", "93441116", "38618901", "47863826"};
        for (int i = 0; i < times.length; i++) {
            long step = Totp.stepAt(times[i] * 1000L);
            assertEquals(sha1[i], Totp.code(SEED_SHA1, step, 8, "HmacSHA1"), "SHA1 T=" + times[i]);
            assertEquals(sha256[i], Totp.code(SEED_SHA256, step, 8, "HmacSHA256"), "SHA256 T=" + times[i]);
            assertEquals(sha512[i], Totp.code(SEED_SHA512, step, 8, "HmacSHA512"), "SHA512 T=" + times[i]);
        }
    }

    @Test
    void 默认6位是8位结果的后6位() {
        assertEquals("287082", Totp.code(SEED_SHA1, Totp.stepAt(59_000L)));
        assertEquals("005924", Totp.code(SEED_SHA1, Totp.stepAt(1234567890_000L)));
    }

    @Test
    void 前后各容1个时间窗_超出不认() {
        String secret = Base32.encode(SEED_SHA1);
        long now = 1234567890_000L;
        long step = Totp.stepAt(now);
        assertEquals(step, Totp.verify(secret, Totp.code(SEED_SHA1, step), now, Long.MIN_VALUE));
        assertEquals(step - 1, Totp.verify(secret, Totp.code(SEED_SHA1, step - 1), now, Long.MIN_VALUE));
        assertEquals(step + 1, Totp.verify(secret, Totp.code(SEED_SHA1, step + 1), now, Long.MIN_VALUE));
        assertEquals(-1, Totp.verify(secret, Totp.code(SEED_SHA1, step - 2), now, Long.MIN_VALUE));
        assertEquals(-1, Totp.verify(secret, Totp.code(SEED_SHA1, step + 2), now, Long.MIN_VALUE));
    }

    @Test
    void 用过的时间窗不能再用() {
        String secret = Base32.encode(SEED_SHA1);
        long now = 1234567890_000L;
        long step = Totp.stepAt(now);
        String code = Totp.code(SEED_SHA1, step);
        assertEquals(-1, Totp.verify(secret, code, now, step));
        assertEquals(step, Totp.verify(secret, code, now, step - 1));
    }

    @Test
    void 格式不对直接拒绝() {
        String secret = Base32.encode(SEED_SHA1);
        long now = 59_000L;
        assertEquals(-1, Totp.verify(secret, null, now, Long.MIN_VALUE));
        assertEquals(-1, Totp.verify(secret, "12345", now, Long.MIN_VALUE));
        assertEquals(-1, Totp.verify(secret, "abcdef", now, Long.MIN_VALUE));
        assertEquals(-1, Totp.verify("!!!", "287082", now, Long.MIN_VALUE));
        assertEquals(Totp.stepAt(now), Totp.verify(secret, "287 082", now, Long.MIN_VALUE), "允许夹空格");
    }

    @Test
    void 新密钥是32位Base32_20字节() {
        String secret = Totp.newSecret();
        assertEquals(32, secret.length());
        assertEquals(20, Base32.decode(secret).length);
    }

    @Test
    void otpauth地址() {
        assertEquals("otpauth://totp/OpsFlow:alice%20w?secret=ABC&issuer=OpsFlow&algorithm=SHA1&digits=6&period=30",
                Totp.otpauthUri("OpsFlow", "alice w", "ABC"));
    }

    @Test
    void Base32_RFC4648测试向量() {
        String[][] vectors = {{"", ""}, {"f", "MY"}, {"fo", "MZXQ"}, {"foo", "MZXW6"}, {"foob", "MZXW6YQ"},
                {"fooba", "MZXW6YTB"}, {"foobar", "MZXW6YTBOI"}};
        for (String[] v : vectors) {
            byte[] raw = v[0].getBytes(StandardCharsets.US_ASCII);
            assertEquals(v[1], Base32.encode(raw));
            assertArrayEquals(raw, Base32.decode(v[1]));
        }
        assertArrayEquals("foobar".getBytes(StandardCharsets.US_ASCII), Base32.decode("mzxw 6ytb-oi======"),
                "忽略大小写、空格、连字符和填充");
        assertThrows(IllegalArgumentException.class, () -> Base32.decode("MZ1W"));
        assertTrue(Base32.encode(SEED_SHA1).matches("[A-Z2-7]+"));
    }
}
