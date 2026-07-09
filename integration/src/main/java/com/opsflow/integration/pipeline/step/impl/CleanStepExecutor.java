package com.opsflow.integration.pipeline.step.impl;

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
 * 清理缓存步骤执行器
 */
@Slf4j
@Component
public class CleanStepExecutor implements StepExecutor {

    @Autowired
    private NodeCommandHelper nodeCommandHelper;
    
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
            
            String nodeDesc = nodeCommandHelper.describeBuildNode(context);
            log.info("执行清理缓存命令: {} 在目录: {} 节点: {}", cleanCommand, workspace, nodeDesc);
            
            NodeCommandHelper.CommandResult cmdResult = nodeCommandHelper.runOnBuildNode(context, cleanCommand, workspace);
            result.setLog("执行节点: " + nodeDesc + "\n工作目录: " + workspace + "\n" + (cmdResult.getOutput() == null ? "" : cmdResult.getOutput()));
            if (!cmdResult.isSuccess()) {
                result.setErrorMessage(cmdResult.getErrorMessage() != null
                    ? cmdResult.getErrorMessage()
                    : "清理缓存失败，退出码: " + cmdResult.getExitCode());
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


