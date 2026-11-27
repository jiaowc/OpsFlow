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

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Collectors;

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

    /**
     * 输出已逐行写入阶段日志时，返回结果里只保留开头和结尾各一小段（够解析结果、拼错误信息），
     * 不再把整份输出（最多 20MB）留在内存里
     */
    static final int STREAMED_HEAD_BYTES = 16 * 1024;
    static final int STREAMED_TAIL_BYTES = 64 * 1024;

    /**
     * 输出已逐行写入阶段日志时，错误信息最多保留的字符数（错误信息会写入数据库的 TEXT 字段）
     */
    static final int STREAMED_ERROR_MAX_CHARS = 4000;

    /**
     * 本机执行时单行输出的最大字节数，超过即强制切分（与 SSH 执行的上限一致）
     */
    static final int MAX_LINE_BYTES = 64 * 1024;

    /**
     * 本机命令超时或被中断、进程已杀掉后，最多再等多久把管道里剩下的输出读完
     */
    static final long OUTPUT_DRAIN_MS = 2000;

    @Autowired
    private BuildNodeMapper buildNodeMapper;

    @Autowired
    private SshClient sshClient;

    public CommandResult runOnBuildNode(PipelineExecutionContext context, String command) {
        return runOnBuildNode(context, command, null);
    }

    public CommandResult runOnBuildNode(PipelineExecutionContext context, String command, String workDir) {
        return runForStep(context, resolveBuildNodeId(context), "构建", command, workDir, true);
    }

    /**
     * 在构建节点执行命令并取回完整输出，输出不写入阶段日志。
     * 用于需要读取输出内容的场景（读文件、列目录、输出要先脱敏再记录）。
     */
    public CommandResult captureOnBuildNode(PipelineExecutionContext context, String command, String workDir) {
        return runForStep(context, resolveBuildNodeId(context), "构建", command, workDir, false);
    }

    private CommandResult runForStep(PipelineExecutionContext context, Long nodeId, String nodeKind,
                                     String command, String workDir, boolean streamToStageLog) {
        int commandTimeoutMs = resolveCommandTimeoutMs(context);
        Consumer<String> lineListener = streamToStageLog ? lineListenerOf(context) : null;
        // 输出正在逐行落盘时，内存里只留开头和结尾
        boolean streamed = streamToStageLog && context != null && context.isLogStreaming();
        int keepHead = streamed ? STREAMED_HEAD_BYTES : BoundedOutputBuffer.DEFAULT_HEAD_BYTES;
        int keepTail = streamed ? STREAMED_TAIL_BYTES : BoundedOutputBuffer.DEFAULT_TAIL_BYTES;
        CommandResult result = null;
        if (nodeId != null) {
            BuildNode node = buildNodeMapper.selectById(nodeId);
            if (node != null) {
                log.info("在{}节点 {}@{} 执行命令", nodeKind, node.getUsername(), node.getHost());
                long timeoutSeconds = Math.max(1, commandTimeoutMs / 1000);
                String remoteCommand = SshClient.wrapWithRemoteTimeout(
                    wrapCommandWithWorkDir(command, workDir, true), timeoutSeconds);
                result = CommandResult.fromSsh(sshClient.execute(node, remoteCommand,
                    commandTimeoutMs + REMOTE_TIMEOUT_GRACE_MS, lineListener, keepHead, keepTail));
            } else {
                log.warn("{}节点不存在: {}", nodeKind, nodeId);
            }
        }
        if (result == null) {
            log.info("未配置{}节点，在本地执行命令", nodeKind);
            result = runLocal(command, WorkspacePathHelper.toLocalPath(workDir), commandTimeoutMs, lineListener,
                keepHead, keepTail);
        }
        if (streamed) {
            result.setErrorMessage(abbreviateError(result.getErrorMessage()));
        }
        return result;
    }

    /** 完整输出已在阶段日志里，错误信息只留结尾一段 */
    static String abbreviateError(String errorMessage) {
        if (errorMessage == null || errorMessage.length() <= STREAMED_ERROR_MAX_CHARS) {
            return errorMessage;
        }
        int start = errorMessage.length() - STREAMED_ERROR_MAX_CHARS;
        if (Character.isLowSurrogate(errorMessage.charAt(start))) {
            start++;
        }
        return "......（前面的输出见阶段日志）\n" + errorMessage.substring(start);
    }

    public CommandResult runOnDeployNode(PipelineExecutionContext context, String command) {
        return runOnDeployNode(context, command, null);
    }

    public CommandResult runOnDeployNode(PipelineExecutionContext context, String command, String workDir) {
        return runForStep(context, resolveDeployNodeId(context), "部署", command, workDir, true);
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
        return runLocal(command, workDir, commandTimeoutMs, lineListener,
            BoundedOutputBuffer.DEFAULT_HEAD_BYTES, BoundedOutputBuffer.DEFAULT_TAIL_BYTES);
    }

    private CommandResult runLocal(String command, String workDir, int commandTimeoutMs,
                                   Consumer<String> lineListener, int keepHeadBytes, int keepTailBytes) {
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
            BoundedOutputBuffer output = new BoundedOutputBuffer(keepHeadBytes, keepTailBytes);
            Process finalProcess = process;
            outputReader = new Thread(() -> {
                try (InputStream in = finalProcess.getInputStream()) {
                    readLines(in, MAX_LINE_BYTES, line -> {
                        output.appendLine(line);
                        if (lineListener != null) {
                            try {
                                lineListener.accept(line);
                            } catch (Exception ignored) {
                                // ignore listener errors
                            }
                        }
                    });
                } catch (Exception ignored) {
                    // ignore read interruption on timeout
                }
            }, "local-cmd-output");
            outputReader.setDaemon(true);
            outputReader.start();

            int waitTimeoutMs = commandTimeoutMs > 0 ? commandTimeoutMs : 60000;
            long deadline = System.currentTimeMillis() + waitTimeoutMs;
            boolean interrupted = false;
            try {
                while (process.isAlive() && System.currentTimeMillis() < deadline) {
                    Thread.sleep(100);
                }
            } catch (InterruptedException e) {
                // 步骤超时或任务被停止：线程被中断，命令也要跟着停下，不能留在后台继续跑
                interrupted = true;
            }
            if (interrupted || process.isAlive()) {
                destroyProcessTree(process);
                // 超时前已经打印的内容可能还在管道里，读完再返回
                joinQuietly(outputReader, OUTPUT_DRAIN_MS);
                result.setSuccess(false);
                result.setExitCode(-1);
                result.setOutput(output.toString());
                result.setErrorMessage(interrupted
                    ? "命令执行被中断"
                    : "命令执行超时（" + (waitTimeoutMs / 1000) + "秒）");
                if (interrupted) {
                    Thread.currentThread().interrupt();
                }
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
            if (process != null && process.isAlive()) {
                destroyProcessTree(process);
            }
            result.setSuccess(false);
            result.setErrorMessage("本地命令执行失败: " + e.getMessage());
        }
        return result;
    }

    /**
     * 杀掉命令进程及其所有子孙进程。只杀 /bin/sh 时，它启动的子进程（sleep、mvn、docker 等）
     * 会被托管给 init 继续运行，并一直占着输出管道。
     */
    static void destroyProcessTree(Process process) {
        // 先记下子孙进程：父进程一死，它们就不再挂在这棵树上了
        List<ProcessHandle> descendants = process.descendants().collect(Collectors.toList());
        process.destroyForcibly();
        for (ProcessHandle descendant : descendants) {
            descendant.destroyForcibly();
        }
    }

    private static void joinQuietly(Thread thread, long millis) {
        if (thread == null) {
            return;
        }
        try {
            thread.join(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 按行读取命令输出（\n、\r、\r\n 都算换行，与 BufferedReader.readLine 一致），每行按 UTF-8 解码。
     * <p>
     * 单行超过 maxLineBytes 时强制切成多行：一直不换行的输出（如二进制内容）不会让行缓冲无限增长。
     * 切分点落在字符边界上，不会把多字节字符切成乱码。
     * </p>
     */
    static void readLines(InputStream in, int maxLineBytes, Consumer<String> onLine) throws IOException {
        byte[] line = new byte[Math.min(maxLineBytes, 8192)];
        int length = 0;
        boolean afterCarriageReturn = false;
        byte[] chunk = new byte[8192];
        int n;
        while ((n = in.read(chunk)) != -1) {
            for (int i = 0; i < n; i++) {
                byte b = chunk[i];
                if (b == '\n') {
                    // \r\n 里的 \n：这一行已经在 \r 处输出过了
                    if (!afterCarriageReturn) {
                        onLine.accept(new String(line, 0, length, StandardCharsets.UTF_8));
                        length = 0;
                    }
                    afterCarriageReturn = false;
                    continue;
                }
                afterCarriageReturn = b == '\r';
                if (afterCarriageReturn) {
                    onLine.accept(new String(line, 0, length, StandardCharsets.UTF_8));
                    length = 0;
                    continue;
                }
                if (length == line.length) {
                    if (length < maxLineBytes) {
                        line = Arrays.copyOf(line, (int) Math.min(maxLineBytes, length * 2L));
                    } else {
                        // 到上限：输出到最后一个完整字符为止，没输出的半个字符留给下一行
                        int cut = completeUtf8Length(line, length);
                        onLine.accept(new String(line, 0, cut, StandardCharsets.UTF_8));
                        System.arraycopy(line, cut, line, 0, length - cut);
                        length -= cut;
                    }
                }
                line[length++] = b;
            }
        }
        if (length > 0) {
            onLine.accept(new String(line, 0, length, StandardCharsets.UTF_8));
        }
    }

    /** 去掉末尾不完整的 UTF-8 字符后的长度；整段都凑不出一个完整字符时原样返回 */
    private static int completeUtf8Length(byte[] bytes, int length) {
        int lead = length - 1;
        while (lead > 0 && length - lead < 4 && (bytes[lead] & 0xC0) == 0x80) {
            lead--;
        }
        int first = bytes[lead] & 0xFF;
        int expected = first >= 0xF0 ? 4 : first >= 0xE0 ? 3 : first >= 0xC0 ? 2 : 1;
        boolean incomplete = first >= 0xC0 && length - lead < expected;
        return incomplete && lead > 0 ? lead : length;
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
