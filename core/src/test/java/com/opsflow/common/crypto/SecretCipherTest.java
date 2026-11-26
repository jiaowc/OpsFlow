package com.opsflow.common.crypto;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class SecretCipherTest {

    @BeforeEach
    void setUp() {
        byte[] key = new byte[32];
        Arrays.fill(key, (byte) 7);
        SecretCipher.useKeyForTest(key);
    }

    @AfterEach
    void tearDown() {
        SecretCipher.useKeyForTest(null);
    }

    @Test
    void roundTrip() {
        String plain = "{\"username\":\"admin\",\"password\":\"p@ss:word/中文\"}";
        String enc = SecretCipher.encrypt(plain);
        assertTrue(enc.startsWith(SecretCipher.PREFIX));
        assertFalse(enc.contains("p@ss"));
        assertEquals(plain, SecretCipher.decrypt(enc));
    }

    @Test
    void randomIvEachTime() {
        assertNotEquals(SecretCipher.encrypt("same"), SecretCipher.encrypt("same"));
    }

    @Test
    void legacyPlaintextPassesThrough() {
        assertEquals("old-plain", SecretCipher.decrypt("old-plain"));
        assertNull(SecretCipher.decrypt(null));
        assertEquals("", SecretCipher.encrypt(""));
        assertNull(SecretCipher.encrypt(null));
    }

    @Test
    void alreadyEncryptedNotDoubleEncrypted() {
        String enc = SecretCipher.encrypt("x");
        assertEquals(enc, SecretCipher.encrypt(enc));
    }

    @Test
    void tamperedCiphertextRejected() {
        String enc = SecretCipher.encrypt("secret");
        // 直接翻转密文字节（改 Base64 末位字符可能只动到填充位，解码结果不变）
        byte[] raw = java.util.Base64.getDecoder().decode(enc.substring(SecretCipher.PREFIX.length()));
        raw[raw.length - 1] ^= 0x01;
        String tampered = SecretCipher.PREFIX + java.util.Base64.getEncoder().encodeToString(raw);
        assertThrows(SecretCipher.SecretDecryptException.class, () -> SecretCipher.decrypt(tampered));
    }

    @Test
    void wrongKeyRejected() {
        String enc = SecretCipher.encrypt("secret");
        byte[] other = new byte[32];
        Arrays.fill(other, (byte) 9);
        SecretCipher.useKeyForTest(other);
        assertThrows(SecretCipher.SecretDecryptException.class, () -> SecretCipher.decrypt(enc));
    }
}
