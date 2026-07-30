package com.opsflow.integration.pipeline.step.impl;

import com.opsflow.integration.pipeline.NodeCommandHelper;
import com.opsflow.integration.pipeline.PipelineExecutionContext;
import com.opsflow.integration.pipeline.StageLogHelper;
import com.opsflow.integration.pipeline.step.StepExecutor;
import com.opsflow.integration.pipeline.step.StepExecutionResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * 清理缓存步骤执行器
 */
@Slf4j
@Component
public class CleanStepExecutor implements StepExecutor {

    @Autowired
    private NodeCommandHelper nodeCommandHelper;

    @Override
    public StepExecutionResult execute(String stepType, Map<String, String> stepParams, PipelineExecutionContext context) {
        StepExecutionResult result = new StepExecutionResult();
        result.setSuccess(false);
        result.setOutputData(new HashMap<>());

        try {
            String workspace = context.getWorkspace();

            String cleanCommand = stepParams.getOrDefault("cleanCommand", "mvn clean");

            if (cleanCommand.startsWith("mvn")) {
                cleanCommand = "mvn clean";
            } else if (cleanCommand.startsWith("gradle")) {
                cleanCommand = "gradle clean";
            } else if (cleanCommand.equals("docker")) {
                cleanCommand = "docker system prune -f";
            } else if (cleanCommand.equals("all")) {
                cleanCommand = "mvn clean && docker system prune -f";
            }

            String nodeDesc = nodeCommandHelper.describeBuildNode(context);
            StringBuilder logBuilder = StageLogHelper.start(context, "清理缓存");
            StageLogHelper.appendKv(logBuilder, context, "执行节点", nodeDesc);
            StageLogHelper.appendKv(logBuilder, context, "工作目录", workspace);
            StageLogHelper.appendCommand(logBuilder, context, cleanCommand);
            StageLogHelper.appendSection(logBuilder, context, "命令输出");

            log.info("执行清理缓存命令: {} 在目录: {} 节点: {}", cleanCommand, workspace, nodeDesc);

            NodeCommandHelper.CommandResult cmdResult = nodeCommandHelper.runOnBuildNode(context, cleanCommand, workspace);
            StageLogHelper.appendCommandResult(logBuilder, context, cmdResult);
            result.setLog(logBuilder.toString());

            if (!cmdResult.isSuccess()) {
                result.setErrorMessage(cmdResult.getErrorMessage() != null
                    ? cmdResult.getErrorMessage()
                    : "清理缓存失败，退出码: " + cmdResult.getExitCode());
                return result;
            }

            result.setSuccess(true);
            log.info("清理缓存成功");

        } catch (Exception e) {
            log.error("清理缓存失败", e);
            result.setErrorMessage("清理缓存失败: " + e.getMessage());
            StageLogHelper.emitLine(context, "[异常] " + e.getMessage());
        }

        return result;
    }

    @Override
    public boolean supports(String stepType) {
        return "clean".equalsIgnoreCase(stepType);
    }
}
