package com.opsflow.integration.pipeline.step.impl;

import com.opsflow.integration.git.GitCredentialService;
import com.opsflow.integration.pipeline.NodeCommandHelper;
import com.opsflow.integration.pipeline.PipelineExecutionContext;
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
 * Git代码拉取步骤执行器
 */
@Slf4j
@Component
public class CheckoutStepExecutor implements StepExecutor {

    @Autowired
    private NodeCommandHelper nodeCommandHelper;

    @Autowired
    private GitCredentialService gitCredentialService;

    @Override
    public StepExecutionResult execute(String stepType, Map<String, String> stepParams, PipelineExecutionContext context) {
        StepExecutionResult result = new StepExecutionResult();
        result.setSuccess(false);
        result.setOutputData(new HashMap<>());

        try {
            String workspace = context.getWorkspace();
            String gitRepo = stepParams.getOrDefault("gitRepo", context.getService().getGitRepo());
            String branch = stepParams.getOrDefault("branch", context.getService().getBranch());
            Long componentId = context.getService() != null ? context.getService().getComponentId() : null;
            String authenticatedRepo = gitCredentialService.injectCredentials(gitRepo, componentId);

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
            boolean isTag = "tag".equalsIgnoreCase(stepParams.get("gitType"));
            String repoQuoted = NodeCommandHelper.shellQuote(authenticatedRepo);
            String commitMarker = "__OPSFLOW_COMMIT_ID__=";
            String nodeDesc = nodeCommandHelper.describeBuildNode(context);

            StringBuilder logBuilder = StageLogHelper.start(context, "拉取代码");
            StageLogHelper.appendKv(logBuilder, context, "执行节点", nodeDesc);
            StageLogHelper.appendKv(logBuilder, context, "工作目录", workspace);
            StageLogHelper.appendKv(logBuilder, context, "仓库", gitRepo);
            StageLogHelper.appendKv(logBuilder, context, "分支/Tag", branch);
            StageLogHelper.appendKv(logBuilder, context, "操作",
                "若目录无 .git 则 clone；然后 fetch 并强制切到远端最新提交，清理未跟踪文件");
            // 不回显带凭据的完整命令，避免密码进入日志
            StageLogHelper.appendCommand(logBuilder, context,
                "git fetch --prune --force --tags && git checkout -f " + (isTag ? "tag " : "") + branch + " && git clean -ffd");
            StageLogHelper.appendSection(logBuilder, context, "命令输出");

            // 幂等拉取：复用已有仓库只做 fetch，然后强制切到远端最新提交并清理未跟踪文件，
            // 不依赖本地分支状态，远端强推、工作区残留改动都不会导致失败；被 .gitignore 忽略的构建缓存保留
            String checkoutCommand = isTag
                ? "git checkout -f --detach " + NodeCommandHelper.shellQuote("refs/tags/" + branch)
                : "git checkout -f -B " + NodeCommandHelper.shellQuote(branch) + " " + NodeCommandHelper.shellQuote("refs/remotes/origin/" + branch);

            String command = "set -e; mkdir -p " + remoteParent + "; "
                + "if [ ! -d " + remoteWorkspace + "/.git ]; then rm -rf " + remoteWorkspace + " && git clone --no-checkout " + repoQuoted + " " + remoteWorkspace + "; fi; "
                + "cd " + remoteWorkspace + "; "
                + "git remote set-url origin " + repoQuoted + "; "
                + "rm -f .git/index.lock; "
                + "git fetch --prune --force --tags origin '+refs/heads/*:refs/remotes/origin/*'; "
                + checkoutCommand + "; "
                + "git reset --hard HEAD; "
                + "git clean -ffd; "
                + "printf '" + commitMarker + "' && git rev-parse HEAD";

            NodeCommandHelper.CommandResult cmdResult = nodeCommandHelper.runOnBuildNode(context, command);
            StageLogHelper.appendCommandResult(logBuilder, context, cmdResult);
            result.setLog(logBuilder.toString());

            if (!cmdResult.isSuccess()) {
                result.setErrorMessage(cmdResult.getErrorMessage() != null
                    ? cmdResult.getErrorMessage()
                    : "Git操作失败，退出码: " + cmdResult.getExitCode());
                return result;
            }

            String commitId = extractCommitId(cmdResult.getOutput(), commitMarker);
            StageLogHelper.appendKv(logBuilder, context, "Commit ID", commitId);
            result.setLog(logBuilder.toString());
            result.getOutputData().put("commitId", commitId);
            result.getOutputData().put("workspace", workspace);

            result.setSuccess(true);
            log.info("Git代码拉取成功，Commit ID: {}", commitId);

        } catch (Exception e) {
            log.error("Git代码拉取失败", e);
            result.setErrorMessage("Git代码拉取失败: " + e.getMessage());
            StageLogHelper.emitLine(context, "[异常] " + e.getMessage());
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
