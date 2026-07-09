package com.opsflow.integration.pipeline.step.impl;

import com.opsflow.integration.k8s.K8sClient;
import com.opsflow.integration.pipeline.NodeCommandHelper;
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

    @Autowired
    private NodeCommandHelper nodeCommandHelper;
    
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
            String nodeDesc = nodeCommandHelper.describeDeployNode(context);

            log.info("开始部署到K8s: namespace={}, deployment={}, image={}, container={}",
                namespace, deployment, imageFullName, containerName);

            if (nodeCommandHelper.resolveDeployNodeId(context) != null) {
                String command = "kubectl -n " + NodeCommandHelper.shellQuote(namespace)
                    + " set image " + NodeCommandHelper.shellQuote("deployment/" + deployment)
                    + " " + NodeCommandHelper.shellQuote(containerName + "=" + imageFullName);
                NodeCommandHelper.CommandResult cmdResult = nodeCommandHelper.runOnDeployNode(context, command);
                result.setLog("执行节点: " + nodeDesc + "\n" + (cmdResult.getOutput() == null ? "" : cmdResult.getOutput()));
                if (!cmdResult.isSuccess()) {
                    result.setErrorMessage(cmdResult.getErrorMessage() != null ? cmdResult.getErrorMessage() : "部署命令执行失败");
                    return result;
                }
            } else {
                k8sClient.updateDeploymentImage(namespace, deployment, containerName, imageFullName);
                result.setLog(String.format("执行节点: %s\n已触发部署: %s/%s -> %s", nodeDesc, namespace, deployment, imageFullName));
            }
            result.setSuccess(true);
            log.info("部署指令已下发");
            
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

