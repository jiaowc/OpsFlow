package com.opsflow.integration.ssh;

import com.jcraft.jsch.HostKeyRepository;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class TofuHostKeyRepositoryTest {

    /** 构造一个形如 SSH 公钥 blob 的字节串：uint32 长度 + 类型名 + 随意数据 */
    private static byte[] blob(String type, int seed) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] t = type.getBytes(StandardCharsets.US_ASCII);
        out.write(0);
        out.write(0);
        out.write(0);
        out.write(t.length);
        out.write(t, 0, t.length);
        for (int i = 0; i < 32; i++) {
            out.write(seed + i);
        }
        return out.toByteArray();
    }

    @Test
    void firstConnectionTrustsAndExposesKey() {
        TofuHostKeyRepository repo = new TofuHostKeyRepository(null);
        byte[] key = blob("ssh-ed25519", 1);
        assertEquals(HostKeyRepository.OK, repo.check("h", key));
        String stored = repo.newlyTrustedHostKey();
        assertNotNull(stored);
        assertTrue(stored.startsWith("ssh-ed25519 "));
        assertTrue(repo.presentedFingerprint().startsWith("SHA256:"));
        assertEquals(repo.presentedFingerprint(), TofuHostKeyRepository.fingerprintOfStored(stored));
    }

    @Test
    void sameKeyAccepted() {
        byte[] key = blob("ssh-ed25519", 1);
        String stored = TofuHostKeyRepository.encode(key);
        TofuHostKeyRepository repo = new TofuHostKeyRepository(stored);
        assertEquals(HostKeyRepository.OK, repo.check("h", key));
        assertNull(repo.newlyTrustedHostKey());
        assertFalse(repo.isMismatch());
    }

    @Test
    void changedKeyRejected() {
        String stored = TofuHostKeyRepository.encode(blob("ssh-ed25519", 1));
        TofuHostKeyRepository repo = new TofuHostKeyRepository(stored);
        assertEquals(HostKeyRepository.CHANGED, repo.check("h", blob("ssh-ed25519", 2)));
        assertTrue(repo.isMismatch());
        assertNotEquals(repo.trustedFingerprint(), repo.presentedFingerprint());
        assertNull(repo.newlyTrustedHostKey());
    }
}
