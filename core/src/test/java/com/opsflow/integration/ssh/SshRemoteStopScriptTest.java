package com.opsflow.integration.ssh;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 远端「记录会话号 + 按会话终止」两段脚本：在本机模拟 sshd 的做法（setsid 新建会话再执行命令）跑一遍。
 * 需要 Linux 的 /proc 和 setsid，其他系统跳过（CI 的 Ubuntu runner 上会执行）。
 */
class SshRemoteStopScriptTest {

    @TempDir
    Path tempDir;

    private static void assumeLinuxWithSetsid() {
        assumeTrue(Files.isReadable(Paths.get("/proc/self/stat")), "需要 /proc");
        assumeTrue(new File("/usr/bin/setsid").canExecute() || new File("/bin/setsid").canExecute(), "需要 setsid");
    }

    private static int sh(String script) throws Exception {
        Process p = new ProcessBuilder("/bin/sh", "-c", script).redirectErrorStream(true).start();
        assertTrue(p.waitFor(30, TimeUnit.SECONDS), "脚本没有结束");
        return p.exitValue();
    }

    @Test
    void stopScriptKillsEveryProcessInTheCommandsSession() throws Exception {
        assumeLinuxWithSetsid();
        String pidFile = SshClient.remotePidFile();
        Path marker = tempDir.resolve("still-running");
        Path started = tempDir.resolve("started");
        // 和远端一样：会话首进程先记下 $$，再跑带子进程的命令；子 shell 另起进程组，仍在同一会话里
        String command = SshClient.recordSessionId(pidFile)
                + "touch '" + started + "'; "
                + "sh -c 'set -m 2>/dev/null; sh -c \"sleep 3; touch " + marker + "\" & wait'; true";
        Process session = new ProcessBuilder("setsid", "sh", "-c", command).start();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!Files.exists(started) || !Files.exists(Paths.get(pidFile))) {
            assertTrue(System.nanoTime() < deadline, "命令一直没有启动");
            Thread.sleep(20);
        }

        assertEquals(0, sh(SshClient.stopScript(pidFile)));

        assertTrue(session.waitFor(5, TimeUnit.SECONDS), "会话首进程应已被终止");
        assertFalse(Files.exists(Paths.get(pidFile)), "终止后应删除进程号文件");
        Thread.sleep(3500);
        assertFalse(Files.exists(marker), "会话里的子进程没有被终止，仍在运行");
    }

    @Test
    void finishedCommandRemovesItsPidFile() throws Exception {
        assumeLinuxWithSetsid();
        String pidFile = SshClient.remotePidFile();
        assertEquals(0, sh(SshClient.recordSessionId(pidFile) + "echo ok >/dev/null"));
        assertFalse(Files.exists(Paths.get(pidFile)));
    }

    @Test
    void stopScriptReportsWhenThereIsNoPidFile() throws Exception {
        assumeTrue(new File("/bin/sh").canExecute());
        assertEquals(SshClient.STOP_NO_PID_EXIT_CODE, sh(SshClient.stopScript(SshClient.remotePidFile())));
    }

    @Test
    void stopScriptIgnoresAPidFileThatIsNotANumber() throws Exception {
        assumeTrue(new File("/bin/sh").canExecute());
        String pidFile = SshClient.remotePidFile();
        Files.write(Paths.get(pidFile), "1; touch /tmp/should-not-run".getBytes(StandardCharsets.UTF_8));
        try {
            assertEquals(SshClient.STOP_NO_PID_EXIT_CODE, sh(SshClient.stopScript(pidFile)));
            assertFalse(Files.exists(Paths.get(pidFile)));
        } finally {
            Files.deleteIfExists(Paths.get(pidFile));
        }
    }

    @Test
    void pidFileNamesAreRandomAndSafeToQuote() {
        String a = SshClient.remotePidFile();
        assertTrue(a.matches("/tmp/\\.opsflow-cmd-[0-9a-f-]{36}\\.pid"), a);
        assertFalse(a.equals(SshClient.remotePidFile()));
    }
}
