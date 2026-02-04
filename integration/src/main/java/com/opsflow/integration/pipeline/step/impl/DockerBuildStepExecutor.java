package com.opsflow.integration.pipeline.step.impl;

import com.opsflow.integration.pipeline.PipelineExecutionContext;
import com.opsflow.integration.pipeline.step.StepExecutor;
import com.opsflow.integration.pipeline.step.StepExecutionResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.File;
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
    
    @Override
    public StepExecutionResult execute(String stepType, Map<String, String> stepParams, PipelineExecutionContext context) {
        StepExecutionResult result = new StepExecutionResult();
        result.setSuccess(false);
        result.setOutputData(new HashMap<>());
        
        try {
            String workspace = context.getWorkspace();
            String dockerfilePath = stepParams.getOrDefault("dockerfilePath", context.getService().getDockerfilePath());
            String commitId = stepParams.get("commitId");
            
            // 构建镜像标签
            String imageTag = buildImageTag(context, commitId, stepParams);
            
            // 构建完整镜像名称
            String harborRegistry = context.getEnvironment().getHarborRegistry();
            String harborProject = context.getEnvironment().getHarborProject();
            String serviceCode = context.getService().getCode();
            String imageFullName = String.format("%s/%s/%s:%s", harborRegistry, harborProject, serviceCode, imageTag);
            
            log.info("构建Docker镜像: {}", imageFullName);
            
            File workspaceDir = new File(workspace);
            File dockerfile = new File(workspaceDir, dockerfilePath != null ? dockerfilePath : "Dockerfile");
            
            // 构建Docker镜像
            ProcessBuilder buildBuilder = new ProcessBuilder(
                "docker", "build",
                "-f", dockerfile.getAbsolutePath(),
                "-t", imageFullName,
                workspace
            );
            buildBuilder.redirectErrorStream(true);
            
            Process buildProcess = buildBuilder.start();
            StringBuilder buildLog = new StringBuilder();
            try (java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(buildProcess.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    buildLog.append(line).append("\n");
                    log.debug("Docker build: {}", line);
                }
            }
            
            int buildExitCode = buildProcess.waitFor();
            result.setLog(buildLog.toString());
            
            if (buildExitCode != 0) {
                result.setErrorMessage("Docker镜像构建失败，退出码: " + buildExitCode);
                return result;
            }
            
            log.info("Docker镜像构建成功，开始推送到Harbor");
            
            // 推送到Harbor
            ProcessBuilder pushBuilder = new ProcessBuilder("docker", "push", imageFullName);
            pushBuilder.redirectErrorStream(true);
            
            Process pushProcess = pushBuilder.start();
            StringBuilder pushLog = new StringBuilder();
            try (java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(pushProcess.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    pushLog.append(line).append("\n");
                    log.debug("Docker push: {}", line);
                }
            }
            
            int pushExitCode = pushProcess.waitFor();
            result.setLog(result.getLog() + "\n" + pushLog.toString());
            
            if (pushExitCode != 0) {
                result.setErrorMessage("Docker镜像推送失败，退出码: " + pushExitCode);
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
        return "docker-build".equalsIgnoreCase(stepType) || "dockerbuild".equalsIgnoreCase(stepType);
    }
}


