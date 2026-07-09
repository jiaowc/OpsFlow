package com.opsflow.integration.pipeline.step.impl;

import com.opsflow.integration.pipeline.WorkspacePathHelper;
import com.opsflow.integration.pipeline.PipelineExecutionContext;
import com.opsflow.integration.pipeline.NodeCommandHelper;
import com.opsflow.integration.pipeline.step.StepExecutor;
import com.opsflow.integration.pipeline.step.StepExecutionResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

/**
 * Docker镜像构建和推送步骤执行器
 */
@Slf4j
@Component
public class DockerBuildStepExecutor implements StepExecutor {

    @Autowired
    private NodeCommandHelper nodeCommandHelper;
    
    @Override
    public StepExecutionResult execute(String stepType, Map<String, String> stepParams, PipelineExecutionContext context) {
        StepExecutionResult result = new StepExecutionResult();
        result.setSuccess(false);
        result.setOutputData(new HashMap<>());
        
        try {
            String workspace = context.getWorkspace();
            String commitId = stepParams.get("commitId");
            String nodeDesc = nodeCommandHelper.describeBuildNode(context);
            
            // 构建镜像标签
            String imageTag = buildImageTag(context, commitId, stepParams);
            
            // 构建完整镜像名称
            String harborRegistry = context.getEnvironment().getHarborRegistry();
            String harborProject = context.getEnvironment().getName();
            String serviceCode = context.getService().getCode();
            String imageFullName = String.format("%s/%s/%s:%s", harborRegistry, harborProject, serviceCode, imageTag);
            
            log.info("构建Docker镜像: {}", imageFullName);
            
            String dockerfilePath = stepParams.getOrDefault("dockerfilePath", context.getService().getDockerfilePath());
            if (dockerfilePath == null || dockerfilePath.trim().isEmpty()) {
                dockerfilePath = "Dockerfile";
            }
            String dockerfileShell = WorkspacePathHelper.toRemoteShellPath(workspace + "/" + dockerfilePath);
            StringBuilder buildCommand = new StringBuilder();
            String dockerfileContent = stepParams.get("dockerfileContent");
            if (dockerfileContent != null && !dockerfileContent.trim().isEmpty()) {
                buildCommand.append("cat <<'EOF' > ").append(dockerfileShell).append("\n")
                    .append(dockerfileContent).append("\nEOF\n");
            }
            buildCommand.append("docker build -f ").append(dockerfileShell)
                .append(" -t ").append(NodeCommandHelper.shellQuote(imageFullName))
                .append(" ").append(WorkspacePathHelper.toRemoteShellPath(workspace));

            NodeCommandHelper.CommandResult buildResult = nodeCommandHelper.runOnBuildNode(context, buildCommand.toString(), workspace);
            result.setLog("执行节点: " + nodeDesc + "\n工作目录: " + workspace + "\n" + (buildResult.getOutput() == null ? "" : buildResult.getOutput()));
            if (!buildResult.isSuccess()) {
                result.setErrorMessage(buildResult.getErrorMessage() != null
                    ? buildResult.getErrorMessage()
                    : "Docker镜像构建失败，退出码: " + buildResult.getExitCode());
                return result;
            }

            result.getOutputData().put("imageTag", imageTag);
            result.getOutputData().put("imageFullName", imageFullName);

            // docker_build 仅制作镜像；docker-build 兼容旧配置可在同一步推送
            boolean pushAfterBuild = Boolean.parseBoolean(stepParams.getOrDefault(
                "pushAfterBuild",
                String.valueOf("docker-build".equalsIgnoreCase(stepType))
            ));

            if (!pushAfterBuild) {
                result.setSuccess(true);
                log.info("Docker镜像构建成功: {}", imageFullName);
                return result;
            }

            log.info("Docker镜像构建成功，开始推送到Harbor");
            
            // 推送到Harbor
            NodeCommandHelper.CommandResult pushResult = nodeCommandHelper.runOnBuildNode(
                context,
                "docker push " + NodeCommandHelper.shellQuote(imageFullName),
                workspace
            );
            result.setLog((result.getLog() == null ? "" : result.getLog() + "\n") + (pushResult.getOutput() == null ? "" : pushResult.getOutput()));
            if (!pushResult.isSuccess()) {
                result.setErrorMessage(pushResult.getErrorMessage() != null
                    ? pushResult.getErrorMessage()
                    : "Docker镜像推送失败，退出码: " + pushResult.getExitCode());
                return result;
            }
            
            result.getOutputData().put("imageTag", imageTag);
            result.getOutputData().put("imageFullName", imageFullName);
            result.setSuccess(true);
            log.info("Docker镜像推送成功: {}", imageFullName);
            
        } catch (Exception e) {
            log.error("Docker镜像构建或推送失败", e);
            result.setErrorMessage("Docker镜像构建或推送失败: " + e.getMessage());
        }
        
        return result;
    }
    
    /**
     * 构建镜像标签
     * 格式: branch-timestamp-commitId前7位
     */
    private String buildImageTag(PipelineExecutionContext context, String commitId, Map<String, String> stepParams) {
        String branch = context.getService().getBranch();
        String timestamp = new SimpleDateFormat("yyyyMMddHHmmss").format(new Date());
        
        String tagFormat = stepParams.getOrDefault("tagFormat", "%s-%s-%s");
        String commitSuffix = "";
        if (commitId != null && !commitId.isEmpty()) {
            commitSuffix = commitId.length() > 7 ? commitId.substring(0, 7) : commitId;
        }
        
        return String.format(tagFormat, branch, timestamp, commitSuffix);
    }
    
    @Override
    public boolean supports(String stepType) {
        return "docker-build".equalsIgnoreCase(stepType)
            || "dockerbuild".equalsIgnoreCase(stepType)
            || "docker_build".equalsIgnoreCase(stepType);
    }
}


