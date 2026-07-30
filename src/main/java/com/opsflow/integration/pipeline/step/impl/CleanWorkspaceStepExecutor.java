package com.opsflow.integration.pipeline.step.impl;

import com.opsflow.integration.pipeline.NodeCommandHelper;
import com.opsflow.integration.pipeline.PipelineExecutionContext;
import com.opsflow.integration.pipeline.PipelineWorkspaceResolver;
import com.opsflow.integration.pipeline.StageLogHelper;
import com.opsflow.integration.pipeline.WorkspacePathHelper;
import com.opsflow.integration.pipeline.step.StepExecutor;
import com.opsflow.integration.pipeline.step.StepExecutionResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * 清理构建节点工作目录，避免历史代码或缓存影响后续构建
 */
@Slf4j
@Component
public class CleanWorkspaceStepExecutor implements StepExecutor {

    @Autowired
    private NodeCommandHelper nodeCommandHelper;

    @Override
    public StepExecutionResult execute(String stepType, Map<String, String> stepParams, PipelineExecutionContext context) {
        StepExecutionResult result = new StepExecutionResult();
        result.setSuccess(false);
        result.setOutputData(new HashMap<>());

        try {
            String workspacePath = PipelineWorkspaceResolver.resolve(context, stepParams);
            context.setWorkspace(workspacePath);

            boolean cleanDocker = "true".equalsIgnoreCase(stepParams.get("cleanDocker"));
            String shellPath = WorkspacePathHelper.toRemoteShellPath(workspacePath);
            String nodeDesc = nodeCommandHelper.describeBuildNode(context);

            StringBuilder command = new StringBuilder();
            command.append("rm -rf ").append(shellPath);
            command.append(" && mkdir -p ").append(shellPath);
            if (cleanDocker) {
                command.append(" && docker system prune -f 2>/dev/null || true");
            }

            StringBuilder logBuilder = StageLogHelper.start(context, "清理工作目录");
            StageLogHelper.appendKv(logBuilder, context, "执行节点", nodeDesc);
            StageLogHelper.appendKv(logBuilder, context, "工作目录", workspacePath);
            StageLogHelper.appendKv(logBuilder, context, "清理 Docker 缓存", cleanDocker ? "是" : "否");
            StageLogHelper.appendCommand(logBuilder, context, command.toString());
            StageLogHelper.appendSection(logBuilder, context, "命令输出");

            log.info("清理构建工作目录: {}", workspacePath);
            NodeCommandHelper.CommandResult cmdResult = nodeCommandHelper.runOnBuildNode(context, command.toString());
            StageLogHelper.appendCommandResult(logBuilder, context, cmdResult);
            result.setLog(logBuilder.toString());

            if (!cmdResult.isSuccess()) {
                result.setErrorMessage(cmdResult.getErrorMessage() != null
                    ? cmdResult.getErrorMessage()
                    : "清理工作目录失败");
                return result;
            }

            result.getOutputData().put("workspace", workspacePath);
            result.setSuccess(true);
            log.info("工作目录清理完成: {}", workspacePath);
        } catch (Exception e) {
            log.error("清理工作目录失败", e);
            result.setErrorMessage("清理工作目录失败: " + e.getMessage());
            StageLogHelper.emitLine(context, "[异常] " + e.getMessage());
        }

        return result;
    }

    @Override
    public boolean supports(String stepType) {
        return "clean_workspace".equalsIgnoreCase(stepType);
    }
}
