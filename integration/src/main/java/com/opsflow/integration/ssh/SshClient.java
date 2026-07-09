package com.opsflow.integration.ssh;

import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.Session;
import com.opsflow.dao.model.BuildNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

/**
 * SSH 远程命令执行客户端
 */
@Slf4j
@Component
public class SshClient {

    private static final int CONNECT_TIMEOUT_MS = 15000;
    private static final int COMMAND_TIMEOUT_MS = 15000;

    public SshCommandResult execute(BuildNode node, String command) {
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
            ByteArrayOutputStream stdout = new ByteArrayOutputStream();
            ByteArrayOutputStream stderr = new ByteArrayOutputStream();
            channel.setOutputStream(stdout);
            channel.setErrStream(stderr);
            channel.connect(COMMAND_TIMEOUT_MS);

            long deadline = System.currentTimeMillis() + COMMAND_TIMEOUT_MS;
            while (!channel.isClosed() && System.currentTimeMillis() < deadline) {
                Thread.sleep(100);
            }

            int exitCode = channel.isClosed() ? channel.getExitStatus() : -1;
            String out = new String(stdout.toByteArray(), StandardCharsets.UTF_8).trim();
            String err = new String(stderr.toByteArray(), StandardCharsets.UTF_8).trim();
            String combined = !out.isEmpty() ? out : err;
            if (!out.isEmpty() && !err.isEmpty()) {
                combined = out + "\n" + err;
            }

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
