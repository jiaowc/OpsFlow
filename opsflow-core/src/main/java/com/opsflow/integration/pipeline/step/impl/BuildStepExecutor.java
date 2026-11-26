package com.opsflow.integration.pipeline.step.impl;

import com.opsflow.integration.pipeline.NodeCommandHelper;
import com.opsflow.integration.pipeline.PipelineExecutionContext;
import com.opsflow.integration.pipeline.PipelineTemplateRenderer;
import com.opsflow.integration.pipeline.StageLogHelper;
import com.opsflow.integration.pipeline.step.StepExecutor;
import com.opsflow.integration.pipeline.step.StepExecutionResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 构建步骤执行器（支持Maven、Gradle等）
 */
@Slf4j
@Component
public class BuildStepExecutor implements StepExecutor {

    /** 构建命令里的 ${变量名}；只认纯标识符，${VAR:-x}、${1} 等 shell 写法不匹配 */
    private static final Pattern COMMAND_PLACEHOLDER = Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_]*)\\}");
    /** 允许直接拼进 shell 命令的取值：不含空白、引号、$、;、| 等会改变命令结构的字符 */
    private static final Pattern SAFE_COMMAND_VALUE = Pattern.compile("[A-Za-z0-9._/:@+=,-]+");

    @Autowired
    private NodeCommandHelper nodeCommandHelper;

    @Override
    public StepExecutionResult execute(String stepType, Map<String, String> stepParams, PipelineExecutionContext context) {
        StepExecutionResult result = new StepExecutionResult();
        result.setSuccess(false);
        result.setOutputData(new HashMap<>());

        try {
            String workspace = context.getWorkspace();
            String buildCommand = stepParams.getOrDefault("buildCommand", context.getService().getBuildCommand());

            if (buildCommand == null || buildCommand.isEmpty()) {
                buildCommand = "mvn clean package -DskipTests";
            }
            // 支持 ${env}、${branch} 等平台变量，如 npm run build:${env}
            buildCommand = renderBuildCommand(buildCommand, PipelineTemplateRenderer.buildVariables(context, stepParams));

            String nodeDesc = nodeCommandHelper.describeBuildNode(context);
            StringBuilder logBuilder = StageLogHelper.start(context, "代码构建");
            StageLogHelper.appendKv(logBuilder, context, "执行节点", nodeDesc);
            StageLogHelper.appendKv(logBuilder, context, "工作目录", workspace);
            StageLogHelper.appendKv(logBuilder, context, "服务",
                context.getService() != null ? context.getService().getCode() : "-");
            if (context.getStepTimeoutSeconds() != null) {
                StageLogHelper.appendKv(logBuilder, context, "超时(秒)", String.valueOf(context.getStepTimeoutSeconds()));
            }
            StageLogHelper.appendCommand(logBuilder, context, buildCommand);
            StageLogHelper.appendSection(logBuilder, context, "命令输出");

            log.info("执行构建命令: {} 在目录: {} 节点: {}", buildCommand, workspace, nodeDesc);

            NodeCommandHelper.CommandResult cmdResult = nodeCommandHelper.runOnBuildNode(context, buildCommand, workspace);
            StageLogHelper.appendCommandResult(logBuilder, context, cmdResult);
            result.setLog(StageLogHelper.finalLog(context, logBuilder));

            if (!cmdResult.isSuccess()) {
                result.setErrorMessage(cmdResult.getErrorMessage() != null
                    ? cmdResult.getErrorMessage()
                    : "构建失败，退出码: " + cmdResult.getExitCode());
                return result;
            }

            result.setSuccess(true);
            log.info("构建成功");

        } catch (Exception e) {
            log.error("构建失败", e);
            result.setErrorMessage("构建失败: " + e.getMessage());
            StageLogHelper.emitLine(context, "[异常] " + e.getMessage());
        }

        return result;
    }

    @Override
    public boolean supports(String stepType) {
        return "build".equalsIgnoreCase(stepType);
    }

    /**
     * 把构建命令里的 ${变量名} 替换成平台变量的值，变量表与 Dockerfile / K8s 模版一致
     * （见 {@link PipelineTemplateRenderer#buildVariables}）。
     * <p>
     * 平台不认识的变量名原样保留，交给构建节点的 shell 处理（如 ${JAVA_HOME}）。
     * 取值会直接拼进 shell 命令，因此只接受不含空白和 shell 特殊字符的值；
     * 分支名这类外部可控的值不合规时直接报错，不执行命令。
     * </p>
     *
     * @param command   原始构建命令
     * @param variables 平台变量表
     * @return 替换后的命令
     * @throws IllegalArgumentException 用到的变量取值含不允许的字符
     */
    static String renderBuildCommand(String command, Map<String, String> variables) {
        if (command == null || command.isEmpty() || variables == null || variables.isEmpty()) {
            return command;
        }
        Matcher matcher = COMMAND_PLACEHOLDER.matcher(command);
        StringBuffer rendered = new StringBuffer();
        while (matcher.find()) {
            String name = matcher.group(1);
            String value = variables.get(name);
            if (value == null) {
                matcher.appendReplacement(rendered, Matcher.quoteReplacement(matcher.group()));
                continue;
            }
            if (!SAFE_COMMAND_VALUE.matcher(value).matches()) {
                throw new IllegalArgumentException("构建命令中的变量 ${" + name + "} 取值为空或含有空格、引号等特殊字符，不能用于命令: " + value);
            }
            matcher.appendReplacement(rendered, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(rendered);
        return rendered.toString();
    }
}
