package com.opsflow.integration.pipeline;

import com.opsflow.dao.mapper.BuildNodeMapper;
import com.opsflow.dao.model.BuildNode;
import com.opsflow.integration.ssh.BoundedOutputBuffer;
import com.opsflow.integration.ssh.SshClient;
import com.opsflow.integration.ssh.SshCommandResult;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.function.Consumer;

/**
 * 在构建节点（SSH）或本地执行命令
 */
@Slf4j
@Component
public class NodeCommandHelper {

    /**
     * 本地等待比远端 timeout 多出的余量
     */
    private static final int REMOTE_TIMEOUT_GRACE_MS = 15000;

    @Autowired
    private BuildNodeMapper buildNodeMapper;

    @Autowired
    private SshClient sshClient;

    public CommandResult runOnBuildNode(PipelineExecutionContext context, String command) {
        return runOnBuildNode(context, command, null);
    }

    public CommandResult runOnBuildNode(PipelineExecutionContext context, String command, String workDir) {
        Long nodeId = resolveBuildNodeId(context);
        int commandTimeoutMs = resolveCommandTimeoutMs(context);
        Consumer<String> lineListener = lineListenerOf(context);
        if (nodeId != null) {
            BuildNode node = buildNodeMapper.selectById(nodeId);
            if (node != null) {
                log.info("在构建节点 {}@{} 执行命令", node.getUsername(), node.getHost());
                SshCommandResult result = executeRemote(node, wrapCommandWithWorkDir(command, workDir, true),
                    commandTimeoutMs, lineListener);
                return CommandResult.fromSsh(result);
            }
            log.warn("构建节点不存在: {}", nodeId);
        }

        log.info("未配置构建节点，在本地执行命令");
        return runLocal(command, WorkspacePathHelper.toLocalPath(workDir), commandTimeoutMs, lineListener);
    }

    public CommandResult runOnDeployNode(PipelineExecutionContext context, String command) {
        return runOnDeployNode(context, command, null);
    }

    public CommandResult runOnDeployNode(PipelineExecutionContext context, String command, String workDir) {
        Long nodeId = resolveDeployNodeId(context);
        int commandTimeoutMs = resolveCommandTimeoutMs(context);
        Consumer<String> lineListener = lineListenerOf(context);
        if (nodeId != null) {
            BuildNode node = buildNodeMapper.selectById(nodeId);
            if (node != null) {
                log.info("在部署节点 {}@{} 执行命令", node.getUsername(), node.getHost());
                SshCommandResult result = executeRemote(node, wrapCommandWithWorkDir(command, workDir, true),
                    commandTimeoutMs, lineListener);
                return CommandResult.fromSsh(result);
            }
            log.warn("部署节点不存在: {}", nodeId);
        }

        log.info("未配置部署节点，在本地执行命令");
        return runLocal(command, WorkspacePathHelper.toLocalPath(workDir), commandTimeoutMs, lineListener);
    }

    /**
     * 流水线命令在节点上执行：远端 timeout 负责到点终止进程，本地等待时间多留一点余量以拿到远端的超时退出码
     */
    private SshCommandResult executeRemote(BuildNode node, String command, int commandTimeoutMs,
                                           Consumer<String> lineListener) {
        long timeoutSeconds = Math.max(1, commandTimeoutMs / 1000);
        return sshClient.execute(node, SshClient.wrapWithRemoteTimeout(command, timeoutSeconds),
            commandTimeoutMs + REMOTE_TIMEOUT_GRACE_MS, lineListener);
    }

    public Long resolveBuildNodeId(PipelineExecutionContext context) {
        if (context.getOptions() == null) {
            return null;
        }
        if (context.getOptions().getNodeId() != null) {
            return context.getOptions().getNodeId();
        }
        if (context.getOptions().getBuildNodeId() != null) {
            return context.getOptions().getBuildNodeId();
        }
        return null;
    }

    public Long resolveDeployNodeId(PipelineExecutionContext context) {
        if (context.getOptions() == null) {
            return null;
        }
        if (context.getOptions().getNodeId() != null) {
            return context.getOptions().getNodeId();
        }
        return context.getOptions().getDeployNodeId();
    }

    public String describeBuildNode(PipelineExecutionContext context) {
        return describeNode(resolveBuildNodeId(context), "本机");
    }

    public String describeDeployNode(PipelineExecutionContext context) {
        return describeNode(resolveDeployNodeId(context), "本机");
    }

    /**
     * 在指定节点执行命令；nodeId 为空则本机执行
     */
    public CommandResult runOnNode(Long nodeId, String command) {
        return runOnNode(nodeId, command, 60000);
    }

    public CommandResult runOnNode(Long nodeId, String command, int commandTimeoutMs) {
        return runOnNode(nodeId, command, commandTimeoutMs, null);
    }

    public CommandResult runOnNode(Long nodeId, String command, int commandTimeoutMs,
                                   Consumer<String> lineListener) {
        if (nodeId != null) {
            BuildNode node = buildNodeMapper.selectById(nodeId);
            if (node != null) {
                log.info("在节点 {}@{} 执行命令", node.getUsername(), node.getHost());
                SshCommandResult result = sshClient.execute(node, command, commandTimeoutMs, lineListener);
                return CommandResult.fromSsh(result);
            }
            log.warn("节点不存在: {}，改为本地执行", nodeId);
        }
        return runLocal(command, null, commandTimeoutMs, lineListener);
    }

    private String describeNode(Long nodeId, String fallback) {
        if (nodeId == null) {
            return fallback;
        }
        BuildNode node = buildNodeMapper.selectById(nodeId);
        if (node == null) {
            return fallback;
        }
        String name = node.getName() != null && !node.getName().trim().isEmpty() ? node.getName().trim() : "node-" + nodeId;
        String host = node.getHost() != null && !node.getHost().trim().isEmpty() ? node.getHost().trim() : "";
        return host.isEmpty() ? name : name + " (" + host + ")";
    }

    private String wrapCommandWithWorkDir(String command, String workDir, boolean remote) {
        if (workDir == null || workDir.trim().isEmpty()) {
            return command;
        }
        String shellPath = remote
            ? WorkspacePathHelper.toRemoteShellPath(workDir)
            : shellQuote(WorkspacePathHelper.toLocalPath(workDir));
        return "mkdir -p " + shellPath + " && cd " + shellPath + " && " + command;
    }

    public CommandResult runLocal(String command, String workDir) {
        return runLocal(command, workDir, 60000, null);
    }

    public CommandResult runLocal(String command, String workDir, int commandTimeoutMs) {
        return runLocal(command, workDir, commandTimeoutMs, null);
    }

    public CommandResult runLocal(String command, String workDir, int commandTimeoutMs,
                                  Consumer<String> lineListener) {
        CommandResult result = new CommandResult();
        Process process = null;
        Thread outputReader = null;
        try {
            ProcessBuilder processBuilder = new ProcessBuilder("/bin/sh", "-c", command);
            if (workDir != null && !workDir.isEmpty()) {
                processBuilder.directory(new File(workDir));
            }
            processBuilder.redirectErrorStream(true);
            process = processBuilder.start();

            // 输出留在内存里的部分有上限，失控的大输出只保留开头和结尾
            BoundedOutputBuffer output = new BoundedOutputBuffer();
            Process finalProcess = process;
            outputReader = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(finalProcess.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        output.appendLine(line);
                        if (lineListener != null) {
                            try {
                                lineListener.accept(line);
                            } catch (Exception ignored) {
                                // ignore listener errors
                            }
                        }
                    }
                } catch (Exception ignored) {
                    // ignore read interruption on timeout
                }
            }, "local-cmd-output");
            outputReader.setDaemon(true);
            outputReader.start();

            int waitTimeoutMs = commandTimeoutMs > 0 ? commandTimeoutMs : 60000;
            long deadline = System.currentTimeMillis() + waitTimeoutMs;
            while (process.isAlive() && System.currentTimeMillis() < deadline) {
                Thread.sleep(100);
            }
            if (process.isAlive()) {
                process.destroyForcibly();
                result.setSuccess(false);
                result.setExitCode(-1);
                result.setOutput(output.toString());
                result.setErrorMessage("命令执行超时（" + (waitTimeoutMs / 1000) + "秒）");
                return result;
            }

            outputReader.join(2000);
            int exitCode = process.exitValue();
            result.setOutput(output.toString());
            result.setExitCode(exitCode);
            result.setSuccess(exitCode == 0);
            if (exitCode != 0) {
                result.setErrorMessage("命令执行失败，退出码: " + exitCode);
            }
        } catch (Exception e) {
            log.error("本地命令执行失败", e);
            result.setSuccess(false);
            result.setErrorMessage("本地命令执行失败: " + e.getMessage());
        }
        return result;
    }

    private Consumer<String> lineListenerOf(PipelineExecutionContext context) {
        if (context == null) {
            return null;
        }
        return line -> context.emitLog(line + "\n");
    }

    private int resolveCommandTimeoutMs(PipelineExecutionContext context) {
        if (context != null && context.getStepTimeoutSeconds() != null && context.getStepTimeoutSeconds() > 0) {
            return context.getStepTimeoutSeconds() * 1000;
        }
        return 60000;
    }

    /**
     * 生成「命令 + heredoc 正文」：{@code <command> <<'分隔符'\n正文\n分隔符}。
     * <p>
     * 正文来自模版或渲染结果，内容不受控。分隔符固定写成 EOF 时，正文里只要有一行恰好是 EOF，
     * heredoc 就会提前结束，后面的内容被当成命令在节点上执行。这里保证分隔符不会作为整行出现在正文里。
     * 分隔符带引号，正文中的 $、反引号不会被 shell 展开。
     * </p>
     *
     * @param command heredoc 之前的命令，如 {@code cat > 'file'}
     * @param content 正文
     */
    public static String heredoc(String command, String content) {
        String body = content == null ? "" : content;
        String delimiter = heredocDelimiter(body);
        return command + " <<'" + delimiter + "'\n" + body + "\n" + delimiter;
    }

    /** 不会作为整行出现在正文里的 heredoc 分隔符 */
    static String heredocDelimiter(String content) {
        String delimiter = "__OPSFLOW_EOF__";
        if (content == null || content.isEmpty()) {
            return delimiter;
        }
        java.util.Set<String> lines = new java.util.HashSet<>(java.util.Arrays.asList(content.split("\\r?\\n", -1)));
        int seq = 0;
        while (lines.contains(delimiter)) {
            delimiter = "__OPSFLOW_EOF_" + (++seq) + "__";
        }
        return delimiter;
    }

    public static String shellQuote(String value) {
        if (value == null) {
            return "''";
        }
        return "'" + value.replace("'", "'\\''") + "'";
    }

    @Data
    public static class CommandResult {
        private boolean success;
        private int exitCode;
        private String output;
        private String errorMessage;

        public static CommandResult fromSsh(SshCommandResult sshResult) {
            CommandResult result = new CommandResult();
            result.setSuccess(sshResult.isSuccess());
            result.setExitCode(sshResult.getExitCode());
            result.setOutput(sshResult.getOutput());
            result.setErrorMessage(sshResult.getErrorMessage());
            return result;
        }
    }
}
