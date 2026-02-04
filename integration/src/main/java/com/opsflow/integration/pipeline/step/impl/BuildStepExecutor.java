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
 * 构建步骤执行器（支持Maven、Gradle等）
 */
@Slf4j
@Component
public class BuildStepExecutor implements StepExecutor {
    
    @Override
    public StepExecutionResult execute(String stepType, Map<String, String> stepParams, PipelineExecutionContext context) {
        StepExecutionResult result = new StepExecutionResult();
        result.setSuccess(false);
        result.setOutputData(new HashMap<>());
        
        try {
            String workspace = context.getWorkspace();
            String buildCommand = stepParams.getOrDefault("buildCommand", context.getService().getBuildCommand());
            
            if (buildCommand == null || buildCommand.isEmpty()) {
                // 默认使用Maven
                buildCommand = "mvn clean package -DskipTests";
            }
            
            log.info("执行构建命令: {} 在目录: {}", buildCommand, workspace);
            
            File workspaceDir = new File(workspace);
            
            // 根据构建命令类型选择执行方式
            ProcessBuilder processBuilder;
            if (buildCommand.startsWith("mvn")) {
                // Maven构建
                String[] mavenArgs = buildCommand.split("\\s+");
                String[] cmd = new String[mavenArgs.length + 1];
                cmd[0] = "mvn";
                System.arraycopy(mavenArgs, 1, cmd, 1, mavenArgs.length - 1);
                processBuilder = new ProcessBuilder(cmd);
            } else if (buildCommand.startsWith("gradle")) {
                // Gradle构建
                String[] gradleArgs = buildCommand.split("\\s+");
                String[] cmd = new String[gradleArgs.length + 1];
                cmd[0] = "gradle";
                System.arraycopy(gradleArgs, 1, cmd, 1, gradleArgs.length - 1);
                processBuilder = new ProcessBuilder(cmd);
            } else {
                // 其他命令，直接执行
                processBuilder = new ProcessBuilder("/bin/sh", "-c", buildCommand);
            }
            
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
                    log.debug("Build output: {}", line);
                }
            }
            
            int exitCode = process.waitFor();
            result.setLog(logOutput.toString());
            
            if (exitCode != 0) {
                result.setErrorMessage("构建失败，退出码: " + exitCode);
                return result;
            }
            
            result.setSuccess(true);
            log.info("构建成功");
            
        } catch (Exception e) {
            log.error("构建失败", e);
            result.setErrorMessage("构建失败: " + e.getMessage());
        }
        
        return result;
    }
    
    @Override
    public boolean supports(String stepType) {
        return "build".equalsIgnoreCase(stepType);
    }
}


