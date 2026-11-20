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

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
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
        TofuHostKeyRepository hostKeys = null;
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
            channel.setCommand("export LANG=C.UTF-8 2>/dev/null; " + command);
            channel.setInputStream(null);
            InputStream stdout = channel.getInputStream();
            InputStream stderr = channel.getErrStream();
            channel.connect(CONNECT_TIMEOUT_MS);

            // 输出留在内存里的部分有上限，失控的大输出只保留开头和结尾
            BoundedOutputBuffer outBuf = new BoundedOutputBuffer(keepHeadBytes, keepTailBytes);
            BoundedOutputBuffer errBuf = new BoundedOutputBuffer(keepHeadBytes, keepTailBytes);
            ByteArrayOutputStream outLine = new ByteArrayOutputStream();
            ByteArrayOutputStream errLine = new ByteArrayOutputStream();

            int waitTimeoutMs = commandTimeoutMs > 0 ? commandTimeoutMs : DEFAULT_COMMAND_TIMEOUT_MS;
            long deadline = System.currentTimeMillis() + waitTimeoutMs;
            while (!channel.isClosed() && System.currentTimeMillis() < deadline) {
                drainStream(stdout, outBuf, outLine, lineListener);
                drainStream(stderr, errBuf, errLine, lineListener);
                Thread.sleep(80);
            }

            // 超时后仍尽量读完已缓冲内容
            drainStream(stdout, outBuf, outLine, lineListener);
            drainStream(stderr, errBuf, errLine, lineListener);
            flushLine(outLine, lineListener);
            flushLine(errLine, lineListener);

            if (!channel.isClosed()) {
                channel.disconnect();
                String partial = combineOutput(outBuf, errBuf);
                SshCommandResult timeout = SshCommandResult.fail(
                    "命令执行超时（" + (waitTimeoutMs / 1000) + "秒）"
                        + (partial.isEmpty() ? "" : "\n--- 超时前已捕获输出 ---\n" + partial));
                timeout.setOutput(partial);
                return timeout;
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

    /**
     * 按字节切分行，整行再按 UTF-8 解码，避免多字节字符被读缓冲边界截断成乱码
     */
    private void drainStream(InputStream in, BoundedOutputBuffer sink,
                             ByteArrayOutputStream lineBuf, Consumer<String> lineListener) throws Exception {
        if (in == null) {
            return;
        }
        byte[] buf = new byte[2048];
        while (in.available() > 0) {
            int n = in.read(buf);
            if (n <= 0) {
                break;
            }
            sink.write(buf, 0, n);
            if (lineListener == null) {
                continue;
            }
            for (int i = 0; i < n; i++) {
                byte b = buf[i];
                if (b == '\n') {
                    emitLine(lineBuf, lineListener);
                } else if (b != '\r') {
                    lineBuf.write(b);
                    if (lineBuf.size() >= MAX_LINE_BYTES) {
                        emitLine(lineBuf, lineListener);
                    }
                }
            }
        }
    }

    private void flushLine(ByteArrayOutputStream lineBuf, Consumer<String> lineListener) {
        if (lineListener != null && lineBuf.size() > 0) {
            emitLine(lineBuf, lineListener);
        }
    }

    private void emitLine(ByteArrayOutputStream lineBuf, Consumer<String> lineListener) {
        lineListener.accept(new String(lineBuf.toByteArray(), StandardCharsets.UTF_8));
        lineBuf.reset();
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
