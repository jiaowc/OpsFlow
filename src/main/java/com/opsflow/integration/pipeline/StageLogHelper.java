package com.opsflow.integration.pipeline;

/**
 * 阶段日志格式化与实时输出辅助
 */
public final class StageLogHelper {

    private StageLogHelper() {
    }

    public static void emit(PipelineExecutionContext context, String text) {
        if (context == null || text == null || text.isEmpty()) {
            return;
        }
        context.emitLog(text);
    }

    public static void emitLine(PipelineExecutionContext context, String line) {
        if (line == null) {
            return;
        }
        emit(context, line.endsWith("\n") ? line : line + "\n");
    }

    public static StringBuilder start(PipelineExecutionContext context, String title) {
        StringBuilder sb = new StringBuilder();
        appendLine(sb, "==== " + title + " ====");
        emit(context, sb.toString());
        return sb;
    }

    public static void appendLine(StringBuilder sb, String line) {
        if (sb == null) {
            return;
        }
        sb.append(line == null ? "" : line).append('\n');
    }

    public static void appendKv(StringBuilder sb, PipelineExecutionContext context, String key, String value) {
        String line = key + ": " + (value == null ? "-" : value);
        appendLine(sb, line);
        emitLine(context, line);
    }

    public static void appendCommand(StringBuilder sb, PipelineExecutionContext context, String displayCommand) {
        String line = "$ " + (displayCommand == null ? "" : displayCommand);
        appendLine(sb, line);
        emitLine(context, line);
    }

    public static void appendSection(StringBuilder sb, PipelineExecutionContext context, String title) {
        String line = "---- " + title + " ----";
        appendLine(sb, line);
        emitLine(context, line);
    }

    public static void appendOutput(StringBuilder sb, String output) {
        if (sb == null) {
            return;
        }
        if (output == null || output.trim().isEmpty()) {
            appendLine(sb, "(无输出)");
            return;
        }
        if (!output.endsWith("\n")) {
            sb.append(output).append('\n');
        } else {
            sb.append(output);
        }
    }

    public static void appendCommandResult(StringBuilder sb, PipelineExecutionContext context,
                                           NodeCommandHelper.CommandResult cmdResult) {
        if (cmdResult == null) {
            appendLine(sb, "命令结果为空");
            emitLine(context, "命令结果为空");
            return;
        }
        // 输出已在执行过程中流式 emit，这里补最终摘要，避免终态日志缺失
        appendOutput(sb, cmdResult.getOutput());
        String summary = "退出码: " + cmdResult.getExitCode()
            + (cmdResult.isSuccess() ? " (成功)" : " (失败)");
        appendLine(sb, summary);
        emitLine(context, summary);
        if (!cmdResult.isSuccess() && cmdResult.getErrorMessage() != null
            && !cmdResult.getErrorMessage().trim().isEmpty()) {
            String err = "错误: " + cmdResult.getErrorMessage().trim();
            appendLine(sb, err);
            emitLine(context, err);
        }
    }
}
