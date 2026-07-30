package com.opsflow.integration.ssh;

import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.Session;
import com.opsflow.dao.model.BuildNode;
import lombok.extern.slf4j.Slf4j;
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
        Session session = null;
        ChannelExec channel = null;
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

            Properties config = new Properties();
            config.put("StrictHostKeyChecking", "no");
            session.setConfig(config);
            session.connect(CONNECT_TIMEOUT_MS);

            channel = (ChannelExec) session.openChannel("exec");
            channel.setCommand("export LANG=C.UTF-8 2>/dev/null; " + command);
            channel.setInputStream(null);
            InputStream stdout = channel.getInputStream();
            InputStream stderr = channel.getErrStream();
            channel.connect(CONNECT_TIMEOUT_MS);

            ByteArrayOutputStream outBuf = new ByteArrayOutputStream();
            ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
            StringBuilder outLine = new StringBuilder();
            StringBuilder errLine = new StringBuilder();

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
            String out = new String(outBuf.toByteArray(), StandardCharsets.UTF_8).trim();
            String err = new String(errBuf.toByteArray(), StandardCharsets.UTF_8).trim();
            String combined = combineText(out, err);

            if (exitCode != 0) {
                return SshCommandResult.of(exitCode, combined.isEmpty() ? "命令执行失败" : combined);
            }
            return SshCommandResult.ok(combined);
        } catch (Exception e) {
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

    public SshCommandResult testConnection(BuildNode node) {
        // 返回远程 OpenSSH 版本；若无 sshd 则回退为系统信息
        return execute(node,
                "bash -lc 'out=$(sshd -V 2>&1 | head -n 1); if [ -n \"$out\" ]; then echo \"$out\"; else uname -sr; fi'");
    }

    /**
     * 使用 pipefail 执行检测命令，避免管道掩盖真实退出码
     */
    public SshCommandResult executeCheck(BuildNode node, String command) {
        String wrapped = "bash -lc 'set -o pipefail; " + command.replace("'", "'\\''") + "'";
        return execute(node, wrapped);
    }

    private void drainStream(InputStream in, ByteArrayOutputStream sink,
                             StringBuilder lineBuf, Consumer<String> lineListener) throws Exception {
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
            String chunk = new String(buf, 0, n, StandardCharsets.UTF_8);
            for (int i = 0; i < chunk.length(); i++) {
                char c = chunk.charAt(i);
                if (c == '\n') {
                    lineListener.accept(lineBuf.toString());
                    lineBuf.setLength(0);
                } else if (c != '\r') {
                    lineBuf.append(c);
                }
            }
        }
    }

    private void flushLine(StringBuilder lineBuf, Consumer<String> lineListener) {
        if (lineListener != null && lineBuf.length() > 0) {
            lineListener.accept(lineBuf.toString());
            lineBuf.setLength(0);
        }
    }

    private String combineOutput(ByteArrayOutputStream outBuf, ByteArrayOutputStream errBuf) {
        String out = new String(outBuf.toByteArray(), StandardCharsets.UTF_8).trim();
        String err = new String(errBuf.toByteArray(), StandardCharsets.UTF_8).trim();
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
