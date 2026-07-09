package com.opsflow.integration.pipeline.step.impl;

import com.opsflow.integration.pipeline.NodeCommandHelper;
import com.opsflow.integration.pipeline.WorkspacePathHelper;
import com.opsflow.integration.pipeline.PipelineExecutionContext;
import com.opsflow.integration.pipeline.PipelineWorkspaceResolver;
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

            log.info("清理构建工作目录: {}", workspacePath);
            NodeCommandHelper.CommandResult cmdResult = nodeCommandHelper.runOnBuildNode(context, command.toString());

            result.setLog("执行节点: " + nodeDesc + "\n" + buildLog(workspacePath, cleanDocker, cmdResult.getOutput()));
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
        }

        return result;
    }

    private String buildLog(String workspacePath, boolean cleanDocker, String output) {
        StringBuilder log = new StringBuilder();
        log.append("工作目录: ").append(workspacePath).append('\n');
        log.append("操作: 删除并重建工作目录");
        if (cleanDocker) {
            log.append("，并清理 Docker 缓存");
        }
        log.append('\n');
        if (output != null && !output.trim().isEmpty()) {
            log.append(output.trim());
        }
        return log.toString();
    }

    @Override
    public boolean supports(String stepType) {
        return "clean_workspace".equalsIgnoreCase(stepType);
    }
}
