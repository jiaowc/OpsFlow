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
 * 清理缓存步骤执行器
 */
@Slf4j
@Component
public class CleanStepExecutor implements StepExecutor {
    
    @Override
    public StepExecutionResult execute(String stepType, Map<String, String> stepParams, PipelineExecutionContext context) {
        StepExecutionResult result = new StepExecutionResult();
        result.setSuccess(false);
        result.setOutputData(new HashMap<>());
        
        try {
            String workspace = context.getWorkspace();
            
            // 获取清理命令，默认为Maven清理
            String cleanCommand = stepParams.getOrDefault("cleanCommand", "mvn clean");
            
            // 支持多种清理方式
            if (cleanCommand.startsWith("mvn")) {
                cleanCommand = "mvn clean";
            } else if (cleanCommand.startsWith("gradle")) {
                cleanCommand = "gradle clean";
            } else if (cleanCommand.equals("docker")) {
                // Docker清理：清理未使用的镜像和容器
                cleanCommand = "docker system prune -f";
            } else if (cleanCommand.equals("all")) {
                // 清理所有：Maven + Docker
                cleanCommand = "mvn clean && docker system prune -f";
            }
            
            log.info("执行清理缓存命令: {} 在目录: {}", cleanCommand, workspace);
            
            File workspaceDir = new File(workspace);
            
            ProcessBuilder processBuilder = new ProcessBuilder("/bin/sh", "-c", cleanCommand);
            processBuilder.directory(workspaceDir);
            processBuilder.redirectErrorStream(true);
            
            // 收集输出日志
            Process process = processBuilder.start();
            StringBuilder logOutput = new StringBuilder();
            try (java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    logOutput.append(line).append("\n");
                    log.debug("Clean output: {}", line);
                }
            }
            
            int exitCode = process.waitFor();
            result.setLog(logOutput.toString());
            
            if (exitCode != 0) {
                result.setErrorMessage("清理缓存失败，退出码: " + exitCode);
                return result;
            }
            
            result.setSuccess(true);
            log.info("清理缓存成功");
            
        } catch (Exception e) {
            log.error("清理缓存失败", e);
            result.setErrorMessage("清理缓存失败: " + e.getMessage());
        }
        
        return result;
    }
    
    @Override
    public boolean supports(String stepType) {
        return "clean".equalsIgnoreCase(stepType);
    }
}


