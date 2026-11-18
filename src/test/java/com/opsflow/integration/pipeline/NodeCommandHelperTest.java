package com.opsflow.integration.pipeline;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NodeCommandHelperTest {

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
}
