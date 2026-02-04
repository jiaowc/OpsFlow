package com.opsflow.integration.pipeline.step.impl;

import com.opsflow.integration.pipeline.PipelineExecutionContext;
import com.opsflow.integration.pipeline.step.StepExecutor;
import com.opsflow.integration.pipeline.step.StepExecutionResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

/**
 * Git代码拉取步骤执行器
 */
@Slf4j
@Component
public class CheckoutStepExecutor implements StepExecutor {
    
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
            
            File workspaceDir = new File(workspace);
            File gitDir = new File(workspaceDir, ".git");
            
            // 如果已存在Git仓库，执行pull，否则执行clone
            ProcessBuilder processBuilder;
            if (gitDir.exists()) {
                log.info("Git仓库已存在，执行pull操作");
                processBuilder = new ProcessBuilder("git", "pull", "origin", branch);
            } else {
                log.info("Git仓库不存在，执行clone操作: {} 分支: {}", gitRepo, branch);
                processBuilder = new ProcessBuilder("git", "clone", "-b", branch, gitRepo, workspace);
            }
            
            processBuilder.directory(workspaceDir.getParentFile());
            processBuilder.redirectErrorStream(true);
            
            Process process = processBuilder.start();
            int exitCode = process.waitFor();
            
            if (exitCode != 0) {
                result.setErrorMessage("Git操作失败，退出码: " + exitCode);
                return result;
            }
            
            // 获取Commit ID
            ProcessBuilder commitIdBuilder = new ProcessBuilder("git", "rev-parse", "HEAD");
            commitIdBuilder.directory(workspaceDir);
            commitIdBuilder.redirectErrorStream(true);
            
            Process commitIdProcess = commitIdBuilder.start();
            StringBuilder commitIdOutput = new StringBuilder();
            try (java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(commitIdProcess.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    commitIdOutput.append(line);
                }
            }
            commitIdProcess.waitFor();
            
            String commitId = commitIdOutput.toString().trim();
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
}


