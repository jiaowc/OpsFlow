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
 * 构建步骤执行器（支持Maven、Gradle等）
 */
@Slf4j
@Component
public class BuildStepExecutor implements StepExecutor {

    @Autowired
    private NodeCommandHelper nodeCommandHelper;
    
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
            
            String nodeDesc = nodeCommandHelper.describeBuildNode(context);
            log.info("执行构建命令: {} 在目录: {} 节点: {}", buildCommand, workspace, nodeDesc);
            
            NodeCommandHelper.CommandResult cmdResult = nodeCommandHelper.runOnBuildNode(context, buildCommand, workspace);
            result.setLog("执行节点: " + nodeDesc + "\n工作目录: " + workspace + "\n" + (cmdResult.getOutput() == null ? "" : cmdResult.getOutput()));
            if (!cmdResult.isSuccess()) {
                result.setErrorMessage(cmdResult.getErrorMessage() != null
                    ? cmdResult.getErrorMessage()
                    : "构建失败，退出码: " + cmdResult.getExitCode());
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


