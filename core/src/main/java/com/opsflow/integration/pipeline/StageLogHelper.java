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

    /**
     * 追加一段步骤自己的说明文字：日志实时落盘时直接写入阶段日志，否则攒在 sb 里随步骤结果返回。
     */
    public static void append(StringBuilder sb, PipelineExecutionContext context, String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        if (context != null && context.isLogStreaming()) {
            context.emitLog(text);
        } else if (sb != null) {
            sb.append(text);
        }
    }

    /**
     * 追加命令输出：日志实时落盘时输出已逐行写入阶段日志，不再往内存里攒第二份。
     */
    public static void appendCommandOutput(StringBuilder sb, PipelineExecutionContext context, String output) {
        if (sb == null || output == null || (context != null && context.isLogStreaming())) {
            return;
        }
        sb.append(output);
    }

    /**
     * 步骤结果里的完整日志：日志实时落盘时返回 null（阶段日志文件已是完整内容，不需要再整段覆盖）。
     */
    public static String finalLog(PipelineExecutionContext context, CharSequence log) {
        if (log == null || (context != null && context.isLogStreaming())) {
            return null;
        }
        return log.toString();
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
        boolean streaming = context != null && context.isLogStreaming();
        if (!streaming) {
            appendOutput(sb, cmdResult.getOutput());
        } else if (cmdResult.getOutput() == null || cmdResult.getOutput().trim().isEmpty()) {
            emitLine(context, "(无输出)");
        }
        String summary = "退出码: " + cmdResult.getExitCode()
            + (cmdResult.isSuccess() ? " (成功)" : " (失败)");
        appendLine(sb, summary);
        emitLine(context, summary);
        if (!cmdResult.isSuccess() && cmdResult.getErrorMessage() != null
            && !cmdResult.getErrorMessage().trim().isEmpty()) {
            String err = "错误: " + cmdResult.getErrorMessage().trim();
            appendLine(sb, err);
            // 日志实时落盘时，阶段结束会统一在日志末尾追加错误信息，这里不重复写
            if (!streaming) {
                emitLine(context, err);
            }
        }
    }
}
