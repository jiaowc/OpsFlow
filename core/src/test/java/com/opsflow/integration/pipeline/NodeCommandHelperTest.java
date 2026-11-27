package com.opsflow.integration.pipeline;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NodeCommandHelperTest {

    @TempDir
    Path tempDir;

    @Test
    void heredocWrapsContentWithQuotedDelimiter() {
        assertEquals("cat > 'f' <<'__OPSFLOW_EOF__'\nFROM nginx\n__OPSFLOW_EOF__",
                NodeCommandHelper.heredoc("cat > 'f'", "FROM nginx"));
    }

    @Test
    void plainEofLineInContentNoLongerTerminatesHeredoc() {
        String content = "FROM nginx\nEOF\nrm -rf ~";
        String delimiter = NodeCommandHelper.heredocDelimiter(content);
        assertNotEquals("EOF", delimiter);
        assertEquals("cat > 'f' <<'" + delimiter + "'\n" + content + "\n" + delimiter,
                NodeCommandHelper.heredoc("cat > 'f'", content));
    }

    @Test
    void delimiterChangesWhenContentContainsItAsAWholeLine() {
        assertEquals("__OPSFLOW_EOF_1__", NodeCommandHelper.heredocDelimiter("a\n__OPSFLOW_EOF__\nb"));
        assertEquals("__OPSFLOW_EOF_2__",
                NodeCommandHelper.heredocDelimiter("__OPSFLOW_EOF__\r\n__OPSFLOW_EOF_1__\r\nb"));
    }

    @Test
    void delimiterIsKeptWhenItOnlyAppearsInsideALine() {
        assertEquals("__OPSFLOW_EOF__", NodeCommandHelper.heredocDelimiter("echo __OPSFLOW_EOF__ done"));
    }

    @Test
    void runLocalCapturesOutputAndStreamsLines() {
        List<String> lines = new ArrayList<>();
        NodeCommandHelper.CommandResult result = new NodeCommandHelper()
                .runLocal("printf 'one\\ntwo\\n'; echo three >&2", null, 10000, lines::add);

        assertTrue(result.isSuccess(), result.getErrorMessage());
        assertEquals("one\ntwo\nthree\n", result.getOutput());
        assertEquals(Arrays.asList("one", "two", "three"), lines);
    }

    @Test
    void runLocalReportsExitCodeAndKeepsOutput() {
        NodeCommandHelper.CommandResult result = new NodeCommandHelper()
                .runLocal("echo before-fail; exit 3", null, 10000, null);

        assertFalse(result.isSuccess());
        assertEquals(3, result.getExitCode());
        assertEquals("before-fail\n", result.getOutput());
    }

    @Test
    void nullOrEmptyContentIsHandled() {
        assertEquals("cat <<'__OPSFLOW_EOF__'\n\n__OPSFLOW_EOF__", NodeCommandHelper.heredoc("cat", null));
        assertEquals("__OPSFLOW_EOF__", NodeCommandHelper.heredocDelimiter(""));
    }

    // ---------- 输出已实时落盘时不在内存里留整份 ----------

    /** 约 330KB、3000 行输出，退出码 2 */
    private static final String BIG_OUTPUT_COMMAND =
            "awk 'BEGIN{for(i=0;i<3000;i++) printf \"line-%d %0100d\\n\", i, 0; exit 2}'";

    private static PipelineExecutionContext contextWithTimeout() {
        PipelineExecutionContext context = new PipelineExecutionContext();
        context.setStepTimeoutSeconds(30);
        return context;
    }

    @Test
    void streamedStepCommandKeepsOnlyHeadAndTailInMemory() {
        PipelineExecutionContext context = contextWithTimeout();
        StringBuilder stageLog = new StringBuilder();
        context.setStageListener(new PipelineStageListener() {
            @Override
            public void onStageStart(int stepOrder, String stepType, String stepName) {
            }

            @Override
            public synchronized void onStageLog(int stepOrder, String chunk) {
                stageLog.append(chunk);
            }

            @Override
            public void onStageComplete(int stepOrder, boolean success, String log, String errorMessage, long durationMs) {
            }
        });
        context.setCurrentStepOrder(1);

        NodeCommandHelper.CommandResult result = new NodeCommandHelper().runOnBuildNode(context, BIG_OUTPUT_COMMAND);

        assertFalse(result.isSuccess());
        assertEquals(2, result.getExitCode());
        // 阶段日志拿到全部 3000 行
        String logged = stageLog.toString();
        assertEquals(3000, logged.split("\n").length);
        assertTrue(logged.startsWith("line-0 "));
        assertTrue(logged.contains("\nline-1500 "));
        // 返回结果里只有开头 16KB + 结尾 64KB
        String output = result.getOutput();
        assertTrue(output.length() < NodeCommandHelper.STREAMED_HEAD_BYTES + NodeCommandHelper.STREAMED_TAIL_BYTES + 200,
                "实际保留 " + output.length() + " 字符");
        assertTrue(output.startsWith("line-0 "));
        assertTrue(output.contains("已省略中间约"));
        assertTrue(output.contains("\nline-2999 "));
        assertFalse(output.contains("\nline-1500 "));
    }

    @Test
    void stepCommandKeepsFullOutputWhenStageLogIsNotStreaming() {
        NodeCommandHelper.CommandResult result =
                new NodeCommandHelper().runOnBuildNode(contextWithTimeout(), BIG_OUTPUT_COMMAND);

        assertEquals(2, result.getExitCode());
        assertEquals(3000, result.getOutput().split("\n").length);
        assertFalse(result.getOutput().contains("已省略"));
    }

    @Test
    void captureKeepsFullOutputAndWritesNothingToStageLog() {
        PipelineExecutionContext context = contextWithTimeout();
        List<String> chunks = new ArrayList<>();
        context.setStageListener(new PipelineStageListener() {
            @Override
            public void onStageStart(int stepOrder, String stepType, String stepName) {
            }

            @Override
            public void onStageLog(int stepOrder, String chunk) {
                chunks.add(chunk);
            }

            @Override
            public void onStageComplete(int stepOrder, boolean success, String log, String errorMessage, long durationMs) {
            }
        });
        context.setCurrentStepOrder(1);

        NodeCommandHelper.CommandResult result =
                new NodeCommandHelper().captureOnBuildNode(context, BIG_OUTPUT_COMMAND, null);

        assertEquals(3000, result.getOutput().split("\n").length);
        assertTrue(chunks.isEmpty());
    }

    @Test
    void longErrorMessageOfStreamedCommandKeepsOnlyItsEnd() {
        assertEquals(null, NodeCommandHelper.abbreviateError(null));
        assertEquals("exit 1", NodeCommandHelper.abbreviateError("exit 1"));

        StringBuilder longMessage = new StringBuilder();
        for (int i = 0; i < 2000; i++) {
            longMessage.append("第 ").append(i).append(" 行\n");
        }
        String shortened = NodeCommandHelper.abbreviateError(longMessage.toString());
        assertTrue(shortened.startsWith("......（前面的输出见阶段日志）\n"));
        assertTrue(shortened.endsWith("第 1999 行\n"));
        assertTrue(shortened.length() <= NodeCommandHelper.STREAMED_ERROR_MAX_CHARS + 30);
    }

    // ---------- 本机执行按行读取 ----------

    private static List<String> linesOf(byte[] bytes, int maxLineBytes) throws Exception {
        List<String> lines = new ArrayList<>();
        // 每次只给 7 个字节，覆盖换行符、多字节字符跨读取边界的情况
        java.io.InputStream in = new java.io.ByteArrayInputStream(bytes) {
            @Override
            public synchronized int read(byte[] b, int off, int len) {
                return super.read(b, off, Math.min(len, 7));
            }
        };
        NodeCommandHelper.readLines(in, maxLineBytes, lines::add);
        return lines;
    }

    private static List<String> readLineReference(String text) throws Exception {
        List<String> lines = new ArrayList<>();
        java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.StringReader(text));
        for (String line = reader.readLine(); line != null; line = reader.readLine()) {
            lines.add(line);
        }
        return lines;
    }

    @Test
    void readLinesSplitsLikeBufferedReaderAndDecodesUtf8() throws Exception {
        String[] samples = {
            "", "one", "one\n", "one\ntwo", "\n\none\n\n", "a\r\nb\r\n", "进度 10%\r进度 100%\r\n完成\n",
            "a\r", "a\r\rb", "a\n\rb", "构建成功 \uD83D\uDE80\n第二行 é\r\n尾巴"
        };
        for (String sample : samples) {
            assertEquals(readLineReference(sample),
                    linesOf(sample.getBytes(java.nio.charset.StandardCharsets.UTF_8), 64 * 1024),
                    "输入: " + sample.replace("\r", "\\r").replace("\n", "\\n"));
        }
    }

    @Test
    void readLinesForcesASplitWhenALineExceedsTheLimitWithoutBreakingCharacters() throws Exception {
        // 上限 10 字节：「中」占 3 字节，10 字节处正好切在第 4 个字的中间
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 40; i++) {
            text.append('中');
        }
        text.append("\uD83D\uDE80ab\n短行\n");
        List<String> lines = linesOf(text.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8), 10);

        assertEquals("短行", lines.get(lines.size() - 1));
        StringBuilder joined = new StringBuilder();
        for (String line : lines.subList(0, lines.size() - 1)) {
            assertTrue(line.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 10, line);
            assertFalse(line.contains("\uFFFD"), "不应出现被切坏的字符: " + line);
            joined.append(line);
        }
        assertEquals(text.substring(0, text.indexOf("\n")), joined.toString());
    }

    @Test
    void runLocalDoesNotBufferAnEndlessLine() {
        // 300KB 不带换行的输出：按 64KB 切成多行交给监听器，而不是攒成一整行
        List<String> lines = new ArrayList<>();
        NodeCommandHelper.CommandResult result = new NodeCommandHelper()
                .runLocal("head -c 300000 /dev/zero | tr '\\0' 'x'", null, 10000, lines::add);

        assertTrue(result.isSuccess(), result.getErrorMessage());
        assertEquals(5, lines.size());
        int total = 0;
        for (String line : lines) {
            assertTrue(line.length() <= NodeCommandHelper.MAX_LINE_BYTES);
            total += line.length();
        }
        assertEquals(300000, total);
    }

    @Test
    void runLocalDecodesOutputAsUtf8() {
        List<String> lines = new ArrayList<>();
        NodeCommandHelper.CommandResult result = new NodeCommandHelper()
                .runLocal("printf '\\346\\236\\204\\345\\273\\272\\346\\210\\220\\345\\212\\237\\n'", null, 10000, lines::add);

        assertEquals(Arrays.asList("构建成功"), lines);
        assertEquals("构建成功\n", result.getOutput());
    }

    @Test
    void runLocalTimeoutKillsTheChildProcessesToo() throws Exception {
        // 只杀最外层的 sh 的话，它启动的子 shell 会继续跑完，然后创建标记文件
        Path marker = tempDir.resolve("still-running");
        long start = System.nanoTime();
        NodeCommandHelper.CommandResult result = new NodeCommandHelper()
                .runLocal("echo started; " + childThatTouchesLater(marker), null, 1000, line -> { });

        assertFalse(result.isSuccess());
        assertEquals(-1, result.getExitCode());
        assertTrue(result.getErrorMessage().contains("超时"), result.getErrorMessage());
        assertEquals("started\n", result.getOutput());
        assertMarkerNeverAppears(marker, start);
    }

    @Test
    void runLocalStopsTheCommandWhenTheStepThreadIsInterrupted() throws Exception {
        // 步骤超时时执行器会中断步骤线程：命令要跟着停下，已经打印的内容要保留，中断标记要还给调用方
        Path marker = tempDir.resolve("still-running");
        CountDownLatch printed = new CountDownLatch(1);
        AtomicReference<NodeCommandHelper.CommandResult> result = new AtomicReference<>();
        AtomicBoolean interruptKept = new AtomicBoolean();
        long start = System.nanoTime();
        Thread step = new Thread(() -> {
            result.set(new NodeCommandHelper().runLocal("echo started; " + childThatTouchesLater(marker),
                    null, 60000, line -> printed.countDown()));
            interruptKept.set(Thread.currentThread().isInterrupted());
        });
        step.start();
        assertTrue(printed.await(30, TimeUnit.SECONDS), "命令一直没有输出");
        step.interrupt();
        step.join(10000);

        assertFalse(step.isAlive(), "被中断后 runLocal 应尽快返回");
        assertTrue(interruptKept.get());
        assertFalse(result.get().isSuccess());
        assertEquals(-1, result.get().getExitCode());
        assertEquals("命令执行被中断", result.get().getErrorMessage());
        assertEquals("started\n", result.get().getOutput());
        assertMarkerNeverAppears(marker, start);
    }

    /**
     * 由子进程（不是最外层的 sh）在 2 秒后创建标记文件。
     * 后面跟一条 true，免得 sh 把最后一条命令直接 exec 成自己，那样就不是子进程了。
     */
    private static String childThatTouchesLater(Path marker) {
        return "sh -c \"sleep 2; touch '" + marker + "'\"; true";
    }

    /** 命令里的子进程若没被杀掉，开始后约 2 秒就会创建标记文件；多等一会儿确认它一直没出现 */
    private static void assertMarkerNeverAppears(Path marker, long startNanos) throws InterruptedException {
        long waitUntil = startNanos + TimeUnit.MILLISECONDS.toNanos(2800);
        while (System.nanoTime() < waitUntil) {
            assertFalse(Files.exists(marker), "命令没有被杀掉，仍在后台运行");
            Thread.sleep(100);
        }
        assertFalse(Files.exists(marker), "命令没有被杀掉，仍在后台运行");
    }
}
