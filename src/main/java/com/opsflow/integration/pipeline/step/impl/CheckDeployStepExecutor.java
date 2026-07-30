package com.opsflow.integration.pipeline.step.impl;

import com.opsflow.integration.k8s.K8sClient;
import com.opsflow.integration.k8s.K8sCredentialService;
import com.opsflow.integration.pipeline.NodeCommandHelper;
import com.opsflow.integration.pipeline.PipelineExecutionContext;
import com.opsflow.integration.pipeline.StageLogHelper;
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

    @Autowired
    private K8sCredentialService k8sCredentialService;

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

            StringBuilder logBuilder = new StringBuilder();
            logBuilder.append("==== 检查部署状态 ====\n");
            logBuilder.append("执行节点: ").append(nodeDesc).append('\n');
            logBuilder.append("Namespace: ").append(namespace).append('\n');
            logBuilder.append("Deployment: ").append(deployment).append('\n');
            logBuilder.append("超时(秒): ").append(timeoutSeconds).append('\n');
            context.emitLog(logBuilder.toString());

            String logText;
            if (nodeCommandHelper.resolveDeployNodeId(context) != null) {
                String kubeSetup = k8sCredentialService.buildRemoteKubeconfigSetup(context.getExecutionId(),
                        context.getEnvironment() != null ? context.getEnvironment().getClusterId() : null, null);
                String command = kubeSetup
                    + "kubectl -n " + NodeCommandHelper.shellQuote(namespace)
                    + " rollout status " + NodeCommandHelper.shellQuote("deployment/" + deployment)
                    + " --timeout=" + timeoutSeconds + "s";
                StageLogHelper.appendCommand(logBuilder, context,
                    "kubectl -n " + namespace + " rollout status deployment/" + deployment
                        + " --timeout=" + timeoutSeconds + "s");
                context.emitLog("---- 命令输出 ----\n");
                NodeCommandHelper.CommandResult cmdResult = nodeCommandHelper.runOnDeployNode(context, command);
                logBuilder.append(cmdResult.getOutput() == null ? "" : cmdResult.getOutput());
                logBuilder.append("\n退出码: ").append(cmdResult.getExitCode())
                    .append(cmdResult.isSuccess() ? " (成功)\n" : " (失败)\n");
                logText = logBuilder.toString();
                result.setLog(logText);
                if (!cmdResult.isSuccess()) {
                    result.setErrorMessage(cmdResult.getErrorMessage() != null ? cmdResult.getErrorMessage() : "检查部署状态失败");
                    return result;
                }
            } else {
                context.emitLog("使用本机 K8s 客户端等待 rollout...\n");
                k8sClient.waitForDeploymentRollout(namespace, deployment, timeoutSeconds);
                logText = String.format("%s部署 rollout 完成: %s/%s\n", logBuilder, namespace, deployment);
                result.setLog(logText);
                context.emitLog("部署 rollout 完成: " + namespace + "/" + deployment + "\n");
            }
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
