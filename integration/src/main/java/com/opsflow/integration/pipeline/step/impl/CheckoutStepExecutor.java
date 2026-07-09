package com.opsflow.integration.pipeline.step.impl;

import com.opsflow.integration.pipeline.WorkspacePathHelper;
import com.opsflow.integration.pipeline.PipelineExecutionContext;
import com.opsflow.integration.pipeline.NodeCommandHelper;
import com.opsflow.integration.pipeline.step.StepExecutor;
import com.opsflow.integration.pipeline.step.StepExecutionResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * Git代码拉取步骤执行器
 */
@Slf4j
@Component
public class CheckoutStepExecutor implements StepExecutor {

    @Autowired
    private NodeCommandHelper nodeCommandHelper;
    
    @Override
    public StepExecutionResult execute(String stepType, Map<String, String> stepParams, PipelineExecutionContext context) {
        StepExecutionResult result = new StepExecutionResult();
        result.setSuccess(false);
        result.setOutputData(new HashMap<>());
        
        try {
            String workspace = context.getWorkspace();
            String gitRepo = stepParams.getOrDefault("gitRepo", context.getService().getGitRepo());
            String branch = stepParams.getOrDefault("branch", context.getService().getBranch());
            
            if (gitRepo == null || gitRepo.isEmpty()) {
                result.setErrorMessage("Git仓库地址不能为空");
                return result;
            }
            
            if (branch == null || branch.isEmpty()) {
                result.setErrorMessage("分支名称不能为空");
                return result;
            }
            
            String remoteWorkspace = WorkspacePathHelper.toRemoteShellPath(workspace);
            String remoteParent = WorkspacePathHelper.toRemoteShellPath(parentPath(workspace));
            String branchQuoted = NodeCommandHelper.shellQuote(branch);
            String repoQuoted = NodeCommandHelper.shellQuote(gitRepo);
            String commitMarker = "__OPSFLOW_COMMIT_ID__=";
            String nodeDesc = nodeCommandHelper.describeBuildNode(context);

            String command = "mkdir -p " + remoteParent
                + " && if [ -d " + remoteWorkspace + "/.git ]; then "
                + "cd " + remoteWorkspace + " && git fetch --all && git checkout " + branchQuoted + " && git pull origin " + branchQuoted
                + "; else rm -rf " + remoteWorkspace + " && git clone -b " + branchQuoted + " " + repoQuoted + " " + remoteWorkspace
                + "; fi && cd " + remoteWorkspace + " && printf '" + commitMarker + "' && git rev-parse HEAD";

            NodeCommandHelper.CommandResult cmdResult = nodeCommandHelper.runOnBuildNode(context, command);
            result.setLog("执行节点: " + nodeDesc + "\n工作目录: " + workspace + "\n" + (cmdResult.getOutput() == null ? "" : cmdResult.getOutput()));
            if (!cmdResult.isSuccess()) {
                result.setErrorMessage(cmdResult.getErrorMessage() != null
                    ? cmdResult.getErrorMessage()
                    : "Git操作失败，退出码: " + cmdResult.getExitCode());
                return result;
            }

            String commitId = extractCommitId(cmdResult.getOutput(), commitMarker);
            result.getOutputData().put("commitId", commitId);
            result.getOutputData().put("workspace", workspace);
            
            result.setSuccess(true);
            log.info("Git代码拉取成功，Commit ID: {}", commitId);
            
        } catch (Exception e) {
            log.error("Git代码拉取失败", e);
            result.setErrorMessage("Git代码拉取失败: " + e.getMessage());
        }
        
        return result;
    }
    
    @Override
    public boolean supports(String stepType) {
        return "checkout".equalsIgnoreCase(stepType);
    }

    private String extractCommitId(String output, String marker) {
        if (output == null) {
            return "";
        }
        int idx = output.lastIndexOf(marker);
        if (idx < 0) {
            return "";
        }
        return output.substring(idx + marker.length()).trim();
    }

    private String parentPath(String path) {
        String normalized = path == null ? "" : path.trim();
        int slash = normalized.lastIndexOf('/');
        if (slash <= 0) {
            return ".";
        }
        return normalized.substring(0, slash);
    }
}


