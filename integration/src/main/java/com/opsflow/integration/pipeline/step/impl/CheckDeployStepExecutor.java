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
 * 检查 K8s 部署状态
 */
@Slf4j
@Component
public class CheckDeployStepExecutor implements StepExecutor {

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
            String namespace = context.getEnvironment().getK8sNamespace();
            String deployment = context.getService().getK8sDeployment();

            if (namespace == null || namespace.isEmpty()) {
                result.setErrorMessage("K8s 命名空间不能为空");
                return result;
            }
            if (deployment == null || deployment.isEmpty()) {
                result.setErrorMessage("K8s Deployment 名称不能为空");
                return result;
            }

            int timeoutSeconds = Integer.parseInt(stepParams.getOrDefault("timeoutSeconds", "300"));
            String nodeDesc = nodeCommandHelper.describeDeployNode(context);
            log.info("检查部署状态: namespace={}, deployment={}, timeout={}s", namespace, deployment, timeoutSeconds);

            String logText;
            if (nodeCommandHelper.resolveDeployNodeId(context) != null) {
                String command = "kubectl -n " + NodeCommandHelper.shellQuote(namespace)
                    + " rollout status " + NodeCommandHelper.shellQuote("deployment/" + deployment)
                    + " --timeout=" + timeoutSeconds + "s";
                NodeCommandHelper.CommandResult cmdResult = nodeCommandHelper.runOnDeployNode(context, command);
                logText = "执行节点: " + nodeDesc + "\n" + (cmdResult.getOutput() == null ? "" : cmdResult.getOutput());
                if (!cmdResult.isSuccess()) {
                    result.setErrorMessage(cmdResult.getErrorMessage() != null ? cmdResult.getErrorMessage() : "检查部署状态失败");
                    return result;
                }
            } else {
                k8sClient.waitForDeploymentRollout(namespace, deployment, timeoutSeconds);
                logText = String.format("执行节点: %s\n部署 rollout 完成: %s/%s", nodeDesc, namespace, deployment);
            }
            result.setLog(logText);
            result.setSuccess(true);
        } catch (Exception e) {
            log.error("检查部署状态失败", e);
            result.setErrorMessage("检查部署状态失败: " + e.getMessage());
        }

        return result;
    }

    @Override
    public boolean supports(String stepType) {
        return "check_deploy".equalsIgnoreCase(stepType);
    }
}
