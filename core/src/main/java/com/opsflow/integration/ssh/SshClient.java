package com.opsflow.integration.ssh;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.Session;
import com.opsflow.dao.mapper.BuildNodeMapper;
import com.opsflow.dao.model.BuildNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * SSH 远程命令执行客户端
 */
@Slf4j
@Component
public class SshClient {

    private static final int CONNECT_TIMEOUT_MS = 15000;
    private static final int DEFAULT_COMMAND_TIMEOUT_MS = 60000;

    /**
     * 远端 timeout 发出 TERM 后，再等待多久发送 KILL
     */
    private static final int KILL_AFTER_SECONDS = 10;

    /**
     * 单行超过该长度（如无换行的进度输出）时强制切分，防止缓冲无限增长
     */
    private static final int MAX_LINE_BYTES = 64 * 1024;

    /**
     * 等待命令结束时检查通道状态的间隔
     */
    private static final int WAIT_POLL_MS = 80;

    /**
     * 保活探测间隔：长任务期间连接上可能长时间没有数据，定期探测可防止被中间网络设备当作空闲连接断开
     */
    private static final int SERVER_ALIVE_INTERVAL_MS = 30000;

    /**
     * 连续多少次保活探测无应答后判定连接已断
     */
    private static final int SERVER_ALIVE_COUNT_MAX = 4;

    /**
     * GNU timeout 超时退出码
     */
    public static final int TIMEOUT_EXIT_CODE = 124;

    /**
     * 终止远端命令时，等终止脚本跑完的最长时间（脚本本身先 TERM，最多等 3 秒再 KILL）
     */
    static final int STOP_REMOTE_TIMEOUT_MS = 10000;

    /** 终止脚本退出码：没找到记录的进程号（命令还没来得及写，或远端 /tmp 不可写） */
    static final int STOP_NO_PID_EXIT_CODE = 3;

    @Autowired(required = false)
    private BuildNodeMapper buildNodeMapper;

    public SshCommandResult execute(BuildNode node, String command) {
        return execute(node, command, DEFAULT_COMMAND_TIMEOUT_MS, null);
    }

    public SshCommandResult execute(BuildNode node, String command, int commandTimeoutMs) {
        return execute(node, command, commandTimeoutMs, null);
    }

    /**
     * @param lineListener 按行回调 stdout/stderr（可为 null）
     */
    public SshCommandResult execute(BuildNode node, String command, int commandTimeoutMs,
                                    Consumer<String> lineListener) {
        return execute(node, command, commandTimeoutMs, lineListener,
            BoundedOutputBuffer.DEFAULT_HEAD_BYTES, BoundedOutputBuffer.DEFAULT_TAIL_BYTES);
    }

    /**
     * @param lineListener    按行回调 stdout/stderr（可为 null）
     * @param keepHeadBytes   返回结果里保留的输出开头字节数
     * @param keepTailBytes   返回结果里保留的输出结尾字节数；调用方已通过 lineListener 逐行落盘时可以给得很小
     */
    public SshCommandResult execute(BuildNode node, String command, int commandTimeoutMs,
                                    Consumer<String> lineListener, int keepHeadBytes, int keepTailBytes) {
        Session session = null;
        ChannelExec channel = null;
        CommandOutputSink outSink = null;
        CommandOutputSink errSink = null;
        TofuHostKeyRepository hostKeys = null;
        String pidFile = null;
        boolean interrupted = false;
        try {
            String validationError = validateNodeCredentials(node);
            if (validationError != null) {
                return SshCommandResult.fail(validationError);
            }

            JSch jsch = new JSch();
            String authType = node.getAuthType() != null ? node.getAuthType() : "password";

            if ("private_key".equals(authType)) {
                byte[] passphrase = null;
                if (node.getPrivateKeyPassphrase() != null && !node.getPrivateKeyPassphrase().isEmpty()) {
                    passphrase = node.getPrivateKeyPassphrase().getBytes(StandardCharsets.UTF_8);
                }
                jsch.addIdentity(
                        "node-key-" + (node.getId() != null ? node.getId() : node.getHost()),
                        node.getPrivateKey().getBytes(StandardCharsets.UTF_8),
                        null,
                        passphrase
                );
            }

            int port = node.getPort() != null && node.getPort() > 0 ? node.getPort() : 22;
            session = jsch.getSession(node.getUsername(), node.getHost(), port);

            if ("password".equals(authType)) {
                session.setPassword(node.getPassword());
            }

            // 主机指纹首次信任（TOFU）：首次连接记录公钥，之后不一致即拒绝，防止中间人截获节点密码
            hostKeys = new TofuHostKeyRepository(node.getHostKey());
            session.setHostKeyRepository(hostKeys);

            Properties config = new Properties();
            config.put("StrictHostKeyChecking", "yes");
            // 新版 JSch 默认不再启用 SHA1 的 ssh-rsa；放在末位兜底，兼容 OpenSSH 7.2 以前的老节点
            config.put("server_host_key", JSch.getConfig("server_host_key") + ",ssh-rsa");
            config.put("PubkeyAcceptedAlgorithms", JSch.getConfig("PubkeyAcceptedAlgorithms") + ",ssh-rsa");
            session.setConfig(config);
            session.setServerAliveInterval(SERVER_ALIVE_INTERVAL_MS);
            session.setServerAliveCountMax(SERVER_ALIVE_COUNT_MAX);
            session.connect(CONNECT_TIMEOUT_MS);
            rememberHostKey(node, hostKeys);

            channel = (ChannelExec) session.openChannel("exec");
            pidFile = remotePidFile();
            channel.setCommand(recordSessionId(pidFile) + "export LANG=C.UTF-8 2>/dev/null; " + command);
            // 输出留在内存里的部分有上限，失控的大输出只保留开头和结尾
            BoundedOutputBuffer outBuf = new BoundedOutputBuffer(keepHeadBytes, keepTailBytes);
            BoundedOutputBuffer errBuf = new BoundedOutputBuffer(keepHeadBytes, keepTailBytes);
            // 输出由 SSH 连接的读线程直接写进接收端。不用 channel.getInputStream()：它是 32KB 的管道，
            // 轮询读取时写入方每次写满都要等 1 秒才继续，吞吐被限制在 32KB/s
            Object outputLock = new Object();
            AtomicReference<RuntimeException> listenerFailure = new AtomicReference<>();
            outSink = new CommandOutputSink(
                outputLock, outBuf, lineListener, MAX_LINE_BYTES, listenerFailure);
            errSink = new CommandOutputSink(
                outputLock, errBuf, lineListener, MAX_LINE_BYTES, listenerFailure);
            channel.setInputStream(null);
            channel.setOutputStream(outSink, true);
            channel.setErrStream(errSink, true);
            channel.connect(CONNECT_TIMEOUT_MS);

            int waitTimeoutMs = commandTimeoutMs > 0 ? commandTimeoutMs : DEFAULT_COMMAND_TIMEOUT_MS;
            long deadline = System.currentTimeMillis() + waitTimeoutMs;
            try {
                while (!channel.isClosed() && listenerFailure.get() == null
                        && System.currentTimeMillis() < deadline) {
                    Thread.sleep(WAIT_POLL_MS);
                }
            } catch (InterruptedException e) {
                // 步骤超时或任务被停止：线程被中断。远端命令也要停，返回前恢复中断标记
                interrupted = true;
            }

            boolean closed = channel.isClosed();
            String stopNote = null;
            if (!closed) {
                // 不再等它了：先在节点上终止整个会话里的进程（断开 exec 通道不会让远端命令停下），再断开
                stopNote = stopRemoteCommand(session, pidFile, node);
                channel.disconnect();
            }
            outSink.finish();
            errSink.finish();
            RuntimeException failure = listenerFailure.get();
            if (failure != null) {
                throw failure;
            }

            if (!closed) {
                String partial = combineOutput(outBuf, errBuf);
                String reason = interrupted ? "命令执行被中断" : "命令执行超时（" + (waitTimeoutMs / 1000) + "秒）";
                SshCommandResult stopped = SshCommandResult.fail(reason + "，" + stopNote
                        + (partial.isEmpty() ? "" : "\n--- 已捕获的输出 ---\n" + partial));
                stopped.setOutput(partial);
                return stopped;
            }

            int exitCode = channel.getExitStatus();
            if (exitCode == TIMEOUT_EXIT_CODE) {
                String partial = combineOutput(outBuf, errBuf);
                SshCommandResult timeout = SshCommandResult.of(exitCode,
                    "命令执行超时，已在节点上终止" + (partial.isEmpty() ? "" : "\n--- 超时前已捕获输出 ---\n" + partial));
                timeout.setOutput(partial);
                return timeout;
            }
            String combined = combineOutput(outBuf, errBuf);

            if (exitCode != 0) {
                return SshCommandResult.of(exitCode, combined.isEmpty() ? "命令执行失败" : combined);
            }
            return SshCommandResult.ok(combined);
        } catch (Exception e) {
            if (hostKeys != null && hostKeys.isMismatch()) {
                log.error("SSH 主机指纹不匹配，已拒绝连接: node={} {}:{} 记录={} 实际={}", node.getName(), node.getHost(),
                        node.getPort(), hostKeys.trustedFingerprint(), hostKeys.presentedFingerprint());
                return SshCommandResult.fail("SSH 主机指纹已变化，已拒绝连接（可能存在中间人攻击）。记录的指纹: "
                        + hostKeys.trustedFingerprint() + "，当前指纹: " + hostKeys.presentedFingerprint()
                        + "。如确认节点已重装或更换，请在「节点管理」中重置指纹");
            }
            log.warn("SSH 命令执行失败: {}@{} - {}", node.getUsername(), node.getHost(), e.getMessage());
            return SshCommandResult.fail("SSH 执行失败: " + e.getMessage());
        } finally {
            if (channel != null && channel.isConnected()) {
                channel.disconnect();
            }
            if (session != null && session.isConnected()) {
                session.disconnect();
            }
            // 异常或被中断退出时也要收口：连接断开前已在路上的数据不再回调
            if (outSink != null) {
                outSink.discard();
            }
            if (errSink != null) {
                errSink.discard();
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** 远端记录会话号的文件；名字随机，别人猜不到，不会被预先放置的符号链接利用 */
    static String remotePidFile() {
        return "/tmp/.opsflow-cmd-" + UUID.randomUUID() + ".pid";
    }

    /**
     * 命令最前面把当前 shell 的 PID 写进 {@code pidFile}。sshd 执行 exec 请求时会为命令新建会话，
     * 这个 shell 就是会话首进程，PID 即会话号；命令派生的进程（包括 GNU timeout 另建的进程组）都在这个会话里。
     * 命令正常结束时删除文件。
     */
    static String recordSessionId(String pidFile) {
        String q = "'" + pidFile + "'";
        return "(umask 077; echo $$ > " + q + ") 2>/dev/null; trap \"rm -f " + q + "\" EXIT; ";
    }

    /**
     * 终止脚本：按记录的会话号扫 /proc，先 TERM 会话里的所有进程，最多等 3 秒仍有存活的再 KILL。
     * 只用 POSIX sh 内置命令和 /proc（节点上不一定有 pkill / ps；dash 的 kill 不支持按进程组发信号）。
     * 退出码 {@link #STOP_NO_PID_EXIT_CODE} 表示没找到进程号。
     */
    static String stopScript(String pidFile) {
        String q = "'" + pidFile + "'";
        return "P=$(cat " + q + " 2>/dev/null); rm -f " + q + "\n"
            + "case \"$P\" in ''|*[!0-9]*) exit " + STOP_NO_PID_EXIT_CODE + ";; esac\n"
            + "s() { n=0; for d in /proc/[0-9]*; do read -r st < \"$d/stat\" 2>/dev/null || continue; "
            + "set -- ${st##*) }; [ \"$4\" = \"$P\" ] && [ \"$1\" != Z ] || continue; n=$((n+1)); "
            + "[ \"$SIG\" = 0 ] || kill -s \"$SIG\" \"${d#/proc/}\" 2>/dev/null; done; [ \"$n\" -gt 0 ]; }\n"
            + "SIG=TERM; s || exit 0\n"
            + "i=0; while [ \"$i\" -lt 6 ]; do sleep 0.5 2>/dev/null || sleep 1; SIG=0; s || exit 0; i=$((i+1)); done\n"
            + "SIG=KILL; s; exit 0\n";
    }

    /**
     * 另开一个 exec 通道终止远端命令，返回给用户看的说明。失败只记日志、不抛异常，不能让步骤卡在这里。
     */
    private String stopRemoteCommand(Session session, String pidFile, BuildNode node) {
        ChannelExec stop = null;
        try {
            if (session == null || !session.isConnected()) {
                return "与节点的连接已断开，节点上的命令可能仍在运行";
            }
            stop = (ChannelExec) session.openChannel("exec");
            stop.setCommand(stopScript(pidFile));
            stop.setInputStream(null);
            stop.connect(CONNECT_TIMEOUT_MS);
            long deadline = System.currentTimeMillis() + STOP_REMOTE_TIMEOUT_MS;
            while (!stop.isClosed() && System.currentTimeMillis() < deadline) {
                Thread.sleep(WAIT_POLL_MS);
            }
            if (!stop.isClosed()) {
                log.warn("终止节点上的命令超时: {}@{}", node.getUsername(), node.getHost());
                return "终止节点上的命令超时，命令可能仍在运行";
            }
            int code = stop.getExitStatus();
            if (code == 0) {
                return "已在节点上终止";
            }
            if (code == STOP_NO_PID_EXIT_CODE) {
                log.warn("节点上没有找到命令的进程号，无法终止: {}@{}", node.getUsername(), node.getHost());
                return "没有找到节点上命令的进程号，命令可能仍在运行";
            }
            log.warn("终止节点上的命令失败: {}@{} exit={}", node.getUsername(), node.getHost(), code);
            return "终止节点上的命令失败（退出码 " + code + "），命令可能仍在运行";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "终止节点上的命令时再次被中断，命令可能仍在运行";
        } catch (Exception e) {
            log.warn("终止节点上的命令失败: {}@{} - {}", node.getUsername(), node.getHost(), e.getMessage());
            return "终止节点上的命令失败（" + e.getMessage() + "），命令可能仍在运行";
        } finally {
            if (stop != null && stop.isConnected()) {
                stop.disconnect();
            }
        }
    }

    /**
     * 首次连接成功（已通过认证）后持久化主机公钥；仅在库中尚未记录时写入，避免并发连接互相覆盖
     */
    private void rememberHostKey(BuildNode node, TofuHostKeyRepository hostKeys) {
        String newKey = hostKeys.newlyTrustedHostKey();
        if (newKey == null || node.getId() == null || buildNodeMapper == null) {
            return;
        }
        String fingerprint = hostKeys.presentedFingerprint();
        try {
            UpdateWrapper<BuildNode> wrapper = new UpdateWrapper<>();
            wrapper.eq("id", node.getId())
                    .and(w -> w.isNull("host_key").or().eq("host_key", ""))
                    .set("host_key", newKey)
                    .set("host_key_fingerprint", fingerprint);
            if (buildNodeMapper.update(null, wrapper) > 0) {
                log.info("已记录节点 SSH 主机指纹: node={} {}:{} {}", node.getName(), node.getHost(), node.getPort(), fingerprint);
            }
            node.setHostKey(newKey);
            node.setHostKeyFingerprint(fingerprint);
        } catch (Exception e) {
            // 记录失败不影响本次执行，下次连接会再次尝试
            log.warn("保存节点 SSH 主机指纹失败: node={} - {}", node.getName(), e.getMessage());
        }
    }

    public SshCommandResult testConnection(BuildNode node) {
        // 只验证能执行远程命令；sshd -V 在不少发行版无效且易误导
        return execute(node, "echo SSH_OK; uname -sr 2>/dev/null || true");
    }

    /**
     * 使用 pipefail 执行检测命令，避免管道掩盖真实退出码
     */
    public SshCommandResult executeCheck(BuildNode node, String command) {
        String wrapped = "bash -lc 'set -o pipefail; " + command.replace("'", "'\\''") + "'";
        return execute(node, wrapped);
    }

    /**
     * 远端有 timeout 命令时用它限制执行时间（TERM 后 KILL_AFTER_SECONDS 秒再 KILL，作用于整个进程组），
     * 这样即使本地因超时断开连接，节点上的构建进程也会被终止，不会失控继续运行。
     * stderr 合并到 stdout，保持日志顺序。
     */
    public static String wrapWithRemoteTimeout(String command, long timeoutSeconds) {
        String quoted = "'" + command.replace("'", "'\\''") + "'";
        return "if command -v timeout >/dev/null 2>&1; then "
            + "timeout -k " + KILL_AFTER_SECONDS + " " + timeoutSeconds + " bash -c " + quoted + "; "
            + "else bash -c " + quoted + "; fi 2>&1";
    }

    private String combineOutput(BoundedOutputBuffer outBuf, BoundedOutputBuffer errBuf) {
        String out = outBuf.toString().trim();
        String err = errBuf.toString().trim();
        return combineText(out, err);
    }

    private String combineText(String out, String err) {
        if (!out.isEmpty() && !err.isEmpty()) {
            return out + "\n" + err;
        }
        return !out.isEmpty() ? out : err;
    }

    private String validateNodeCredentials(BuildNode node) {
        if (node.getHost() == null || node.getHost().trim().isEmpty()) {
            return "节点未配置 SSH 主机地址";
        }
        if (node.getUsername() == null || node.getUsername().trim().isEmpty()) {
            return "节点未配置 SSH 用户名";
        }
        String authType = node.getAuthType() != null ? node.getAuthType() : "password";
        if ("private_key".equals(authType)) {
            if (node.getPrivateKey() == null || node.getPrivateKey().trim().isEmpty()) {
                return "节点未配置 SSH 私钥";
            }
        } else if (node.getPassword() == null || node.getPassword().isEmpty()) {
            return "节点未配置 SSH 密码";
        }
        return null;
    }
}
