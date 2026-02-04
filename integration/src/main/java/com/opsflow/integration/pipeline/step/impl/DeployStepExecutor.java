package com.opsflow.integration.pipeline.step.impl;

import com.opsflow.integration.k8s.K8sClient;
import com.opsflow.integration.pipeline.PipelineExecutionContext;
import com.opsflow.integration.pipeline.step.StepExecutor;
import com.opsflow.integration.pipeline.step.StepExecutionResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * 部署步骤执行器（包含上传镜像和部署到K8s）
 */
@Slf4j
@Component
public class DeployStepExecutor implements StepExecutor {
    
    @Autowired
    private K8sClient k8sClient;
    
    @Override
    public StepExecutionResult execute(String stepType, Map<String, String> stepParams, PipelineExecutionContext context) {
        StepExecutionResult result = new StepExecutionResult();
        result.setSuccess(false);
        result.setOutputData(new HashMap<>());
        
        try {
            // 从步骤参数中获取镜像名称（通常由docker-build步骤输出）
            String imageFullName = stepParams.get("imageFullName");
            
            if (imageFullName == null || imageFullName.isEmpty()) {
                result.setErrorMessage("镜像名称不能为空");
                return result;
            }
            
            // 1. 上传镜像到Harbor（如果镜像还未推送）
            boolean pushImage = Boolean.parseBoolean(stepParams.getOrDefault("pushImage", "true"));
            if (pushImage) {
                log.info("开始推送镜像到Harbor: {}", imageFullName);
                
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
                result.setLog(pushLog.toString());
                
                if (pushExitCode != 0) {
                    result.setErrorMessage("镜像推送失败，退出码: " + pushExitCode);
                    return result;
                }
                
                log.info("镜像推送成功: {}", imageFullName);
            }
            
            // 2. 部署到K8s
            String namespace = context.getEnvironment().getK8sNamespace();
            String deployment = context.getService().getK8sDeployment();
            
            if (namespace == null || namespace.isEmpty()) {
                result.setErrorMessage("K8s命名空间不能为空");
                return result;
            }
            
            if (deployment == null || deployment.isEmpty()) {
                result.setErrorMessage("K8s Deployment名称不能为空");
                return result;
            }
            
            String containerName = context.getService().getCode();
            
            log.info("部署到K8s: namespace={}, deployment={}, image={}, container={}", 
                namespace, deployment, imageFullName, containerName);
            
            // 使用K8sClient更新镜像
            k8sClient.updateDeploymentImage(namespace, deployment, containerName, imageFullName);
            
            // 等待部署完成
            boolean waitForRollout = Boolean.parseBoolean(stepParams.getOrDefault("waitForRollout", "true"));
            if (waitForRollout) {
                int timeoutSeconds = Integer.parseInt(stepParams.getOrDefault("timeoutSeconds", "300"));
                k8sClient.waitForDeploymentRollout(namespace, deployment, timeoutSeconds);
            }
            
            result.setSuccess(true);
            log.info("部署成功");
            
        } catch (Exception e) {
            log.error("部署失败", e);
            result.setErrorMessage("部署失败: " + e.getMessage());
        }
        
        return result;
    }
    
    @Override
    public boolean supports(String stepType) {
        return "deploy".equalsIgnoreCase(stepType);
    }
}

