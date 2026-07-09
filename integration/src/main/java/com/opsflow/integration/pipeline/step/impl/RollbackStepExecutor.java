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
 * K8s 部署回滚步骤
 */
@Slf4j
@Component
public class RollbackStepExecutor implements StepExecutor {

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
            String namespace = stepParams.getOrDefault("namespace", context.getEnvironment().getK8sNamespace());
            String deployment = stepParams.getOrDefault("deployment", context.getService().getK8sDeployment());

            if (namespace == null || namespace.isEmpty()) {
                result.setErrorMessage("K8s 命名空间不能为空");
                return result;
            }
            if (deployment == null || deployment.isEmpty()) {
                result.setErrorMessage("K8s Deployment 名称不能为空");
                return result;
            }

            Integer toRevision = null;
            String revisionText = stepParams.get("toRevision");
            if (revisionText != null && !revisionText.trim().isEmpty()) {
                toRevision = Integer.parseInt(revisionText.trim());
            }

            boolean waitRollout = !"false".equalsIgnoreCase(stepParams.get("waitRollout"));
            int timeoutSeconds = 300;
            String timeoutText = stepParams.get("timeoutSeconds");
            if (timeoutText != null && !timeoutText.trim().isEmpty()) {
                timeoutSeconds = Integer.parseInt(timeoutText.trim());
            }
            String nodeDesc = nodeCommandHelper.describeDeployNode(context);

            log.info("回滚 Deployment: {}/{}, revision={}", namespace, deployment, toRevision);
            String rollbackLog;
            if (nodeCommandHelper.resolveDeployNodeId(context) != null) {
                StringBuilder command = new StringBuilder();
                command.append("kubectl -n ").append(NodeCommandHelper.shellQuote(namespace))
                    .append(" rollout undo ")
                    .append(NodeCommandHelper.shellQuote("deployment/" + deployment));
                if (toRevision != null && toRevision > 0) {
                    command.append(" --to-revision=").append(toRevision);
                }
                NodeCommandHelper.CommandResult rollbackResult = nodeCommandHelper.runOnDeployNode(context, command.toString());
                rollbackLog = "执行节点: " + nodeDesc + "\n" + (rollbackResult.getOutput() == null ? "" : rollbackResult.getOutput());
                if (!rollbackResult.isSuccess()) {
                    result.setErrorMessage(rollbackResult.getErrorMessage() != null ? rollbackResult.getErrorMessage() : "回滚失败");
                    return result;
                }
                if (waitRollout) {
                    String rolloutCommand = "kubectl -n " + NodeCommandHelper.shellQuote(namespace)
                        + " rollout status " + NodeCommandHelper.shellQuote("deployment/" + deployment)
                        + " --timeout=" + timeoutSeconds + "s";
                    NodeCommandHelper.CommandResult rolloutResult = nodeCommandHelper.runOnDeployNode(context, rolloutCommand);
                    rollbackLog += "\n" + (rolloutResult.getOutput() == null ? "" : rolloutResult.getOutput());
                    if (!rolloutResult.isSuccess()) {
                        result.setErrorMessage(rolloutResult.getErrorMessage() != null ? rolloutResult.getErrorMessage() : "回滚后检查失败");
                        return result;
                    }
                }
            } else {
                rollbackLog = "执行节点: " + nodeDesc + "\n" + k8sClient.rollbackDeployment(namespace, deployment, toRevision);
                if (waitRollout) {
                    k8sClient.waitForDeploymentRollout(namespace, deployment, timeoutSeconds);
                    rollbackLog += "\nDeployment 回滚后滚动更新已完成";
                }
            }

            result.setLog(rollbackLog);
            result.setSuccess(true);
            log.info("Deployment 回滚成功: {}/{}", namespace, deployment);
        } catch (NumberFormatException e) {
            result.setErrorMessage("步骤参数格式错误: " + e.getMessage());
        } catch (Exception e) {
            log.error("回滚失败", e);
            result.setErrorMessage("回滚失败: " + e.getMessage());
        }

        return result;
    }

    @Override
    public boolean supports(String stepType) {
        return "rollback".equalsIgnoreCase(stepType);
    }
}
