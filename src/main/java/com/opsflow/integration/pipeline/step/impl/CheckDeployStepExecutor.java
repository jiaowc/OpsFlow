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

    /** 步骤超时里留给诊断信息收集的秒数 */
    static final int DIAGNOSTICS_RESERVE_SECONDS = 30;
    /** rollout 至少等待的秒数，避免步骤超时配得太小时算出 0 或负数 */
    static final int MIN_ROLLOUT_TIMEOUT_SECONDS = 10;
    private static final int DIAGNOSTICS_LOG_TAIL_LINES = 40;

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

            int configuredTimeout = Integer.parseInt(stepParams.getOrDefault("timeoutSeconds", "300"));
            int timeoutSeconds = effectiveRolloutTimeout(configuredTimeout, context.getStepTimeoutSeconds());
            String nodeDesc = nodeCommandHelper.describeDeployNode(context);
            log.info("检查部署状态: namespace={}, deployment={}, timeout={}s", namespace, deployment, timeoutSeconds);

            StringBuilder logBuilder = new StringBuilder();
            logBuilder.append("==== 检查部署状态 ====\n");
            logBuilder.append("执行节点: ").append(nodeDesc).append('\n');
            logBuilder.append("Namespace: ").append(namespace).append('\n');
            logBuilder.append("Deployment: ").append(deployment).append('\n');
            logBuilder.append("超时(秒): ").append(timeoutSeconds).append('\n');
            if (timeoutSeconds < configuredTimeout) {
                logBuilder.append("提示: 步骤超时为 ").append(context.getStepTimeoutSeconds())
                    .append(" 秒，小于配置的 rollout 等待 ").append(configuredTimeout)
                    .append(" 秒；本次按 ").append(timeoutSeconds)
                    .append(" 秒等待，并预留时间收集诊断信息。需要等更久请调大该步骤的超时\n");
            }
            context.emitLog(logBuilder.toString());

            String logText;
            if (nodeCommandHelper.resolveDeployNodeId(context) != null) {
                String kubeSetup = k8sCredentialService.buildRemoteKubeconfigSetup(context.getExecutionId(),
                        context.getEnvironment() != null ? context.getEnvironment().getClusterId() : null, null);
                // rollout 失败时在同一次 SSH 会话里接着收集诊断信息（kubeconfig 只在本次会话内有效），最后仍以 rollout 的退出码结束
                String command = kubeSetup
                    + "kubectl -n " + NodeCommandHelper.shellQuote(namespace)
                    + " rollout status " + NodeCommandHelper.shellQuote("deployment/" + deployment)
                    + " --timeout=" + timeoutSeconds + "s\n"
                    + "rc=$?\n"
                    + "if [ \"$rc\" -ne 0 ]; then\n"
                    + buildDiagnosticsScript(namespace, deployment)
                    + "fi\n"
                    + "exit \"$rc\"";
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
                    result.setErrorMessage("部署未在 " + timeoutSeconds + " 秒内就绪: " + namespace + "/" + deployment
                        + "，Pod 状态和容器日志见本步骤日志");
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

    /**
     * rollout 实际等待的秒数。
     * <p>
     * 步骤整体有超时（到点后平台直接终止命令），如果 rollout 自己的等待时间不比它短，
     * 失败时就只剩一句「步骤超时」，来不及输出任何诊断信息。这里保证 rollout 先于步骤超时结束，
     * 并留出 {@link #DIAGNOSTICS_RESERVE_SECONDS} 秒收集 Pod 状态和日志。
     * </p>
     *
     * @param configuredSeconds  步骤参数里配置的 rollout 等待时间
     * @param stepTimeoutSeconds 步骤整体超时；为空表示未知，不做调整
     */
    static int effectiveRolloutTimeout(int configuredSeconds, Integer stepTimeoutSeconds) {
        int configured = Math.max(MIN_ROLLOUT_TIMEOUT_SECONDS, configuredSeconds);
        if (stepTimeoutSeconds == null || stepTimeoutSeconds <= 0) {
            return configured;
        }
        int budget = Math.max(MIN_ROLLOUT_TIMEOUT_SECONDS, stepTimeoutSeconds - DIAGNOSTICS_RESERVE_SECONDS);
        return Math.min(configured, budget);
    }

    /**
     * rollout 失败后在部署节点上执行的只读诊断脚本：Deployment、Pod、容器状态、最近日志、事件。
     * 每条 kubectl 都带请求超时，任何一条失败都不影响后面的输出。
     */
    static String buildDiagnosticsScript(String namespace, String deployment) {
        String deploy = NodeCommandHelper.shellQuote(deployment);
        String selectorTemplate = NodeCommandHelper.shellQuote(
            "{{range $k,$v := .spec.selector.matchLabels}}{{$k}}={{$v}},{{end}}");
        String containerTemplate = NodeCommandHelper.shellQuote(
            "{{range .status.containerStatuses}}{{.name}}: restarts={{.restartCount}}"
                + "{{if .state.waiting}} waiting={{.state.waiting.reason}}{{end}}"
                + "{{if .lastState.terminated}} lastExit={{.lastState.terminated.exitCode}}"
                + "({{.lastState.terminated.reason}}){{end}}{{\"\\n\"}}{{end}}");
        int tail = DIAGNOSTICS_LOG_TAIL_LINES;
        return "  k() { kubectl --request-timeout=5s -n " + NodeCommandHelper.shellQuote(namespace) + " \"$@\"; }\n"
            + "  echo\n"
            + "  echo '==== 部署未就绪，自动收集诊断信息 ===='\n"
            + "  echo '---- Deployment ----'\n"
            + "  k get deployment " + deploy + " -o wide 2>&1\n"
            + "  SEL=$(k get deployment " + deploy + " -o go-template=" + selectorTemplate + " 2>/dev/null | sed 's/,$//')\n"
            + "  if [ -n \"$SEL\" ]; then\n"
            + "    echo '---- Pod ----'\n"
            + "    k get pods -l \"$SEL\" -o wide 2>&1\n"
            // 只看最新的两个 Pod：滚动发布卡住时，问题在新建出来的 Pod 上
            + "    for p in $(k get pods -l \"$SEL\" --sort-by=.metadata.creationTimestamp -o name 2>/dev/null | tail -n 2); do\n"
            + "      echo \"---- $p 容器状态 ----\"\n"
            + "      k get \"$p\" -o go-template=" + containerTemplate + " 2>&1\n"
            + "      echo \"---- $p 日志（最后 " + tail + " 行）----\"\n"
            + "      k logs \"$p\" --all-containers --tail=" + tail + " 2>&1\n"
            + "      echo \"---- $p 上一次退出前的日志（最后 " + tail + " 行）----\"\n"
            + "      k logs \"$p\" --all-containers --previous --tail=" + tail + " 2>/dev/null || echo '(没有上一次退出的记录)'\n"
            + "    done\n"
            + "  fi\n"
            + "  echo '---- 最近事件 ----'\n"
            + "  k get events --sort-by=.lastTimestamp 2>&1 | tail -n 15\n";
    }
}
