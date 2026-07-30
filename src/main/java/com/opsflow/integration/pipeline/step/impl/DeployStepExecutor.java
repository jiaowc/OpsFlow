package com.opsflow.integration.pipeline.step.impl;

import com.opsflow.integration.k8s.K8sClient;
import com.opsflow.integration.k8s.K8sCredentialService;
import com.opsflow.integration.pipeline.NodeCommandHelper;
import com.opsflow.integration.pipeline.PipelineExecutionContext;
import com.opsflow.integration.pipeline.WorkspacePathHelper;
import com.opsflow.integration.pipeline.step.StepExecutor;
import com.opsflow.integration.pipeline.step.StepExecutionResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 部署步骤：应用「渲染模版」产出的 manifests，再更新镜像
 */
@Slf4j
@Component
public class DeployStepExecutor implements StepExecutor {

    @Autowired
    private K8sClient k8sClient;

    @Autowired
    private NodeCommandHelper nodeCommandHelper;

    @Autowired
    private K8sCredentialService k8sCredentialService;

    /**
     * K8s 部署步骤：先 apply 渲染模版产物，再更新 Deployment 镜像，可选执行额外部署脚本。
     * <p><b>业务流程</b></p>
     * <ol>
     *   <li>校验 {@code imageFullName}（来自 push_image 等前置步骤 output 或 CD buildParameters）；</li>
     *   <li>校验 namespace（环境配置）、deployment 名（服务 k8sDeployment/code）；</li>
     *   <li><b>manifest apply</b>：若 stepParams 含 {@code renderedManifests} 或 {@code manifestDir}
     *       （由 render_template 步骤产出），对每个 YAML 执行 {@code kubectl apply}；
     *       无渲染产物则跳过 apply，仅更新镜像；</li>
     *   <li><b>镜像更新</b>：{@code kubectl set image deployment/xxx container=imageFullName}
     *       或本机 K8sClient；</li>
     *   <li>若配置了 {@code deployScript} 且在远程部署节点执行，追加运行该脚本。</li>
     * </ol>
     * <p><b>失败条件</b>：imageFullName/namespace/deployment 为空；kubectl apply 非零退出；
     * set image 失败；deployScript 失败；任意异常。失败时写入 log 与带 namespace 提示的 errorMessage。</p>
     *
     * @param stepType   步骤类型（deploy）
     * @param stepParams 合并后的步骤参数（含 imageFullName、renderedManifests、manifestDir 等）
     * @param context    执行上下文（环境 namespace、服务 deployment/container、部署节点）
     * @return 成功时 success=true；失败时 success=false 且含 errorMessage
     */
    @Override
    public StepExecutionResult execute(String stepType, Map<String, String> stepParams, PipelineExecutionContext context) {
        StepExecutionResult result = new StepExecutionResult();
        result.setSuccess(false);
        result.setOutputData(new HashMap<>());
        StringBuilder logBuilder = new StringBuilder();

        try {
            String imageFullName = stepParams.get("imageFullName");
            if (imageFullName == null || imageFullName.isEmpty()) {
                result.setErrorMessage("镜像名称不能为空（需先完成镜像制作/上传）");
                return result;
            }

            String namespace = context.getEnvironment().getK8sNamespace();
            String deployment = context.getService().getK8sDeployment();
            String envName = context.getEnvironment() != null ? context.getEnvironment().getName() : null;
            if (namespace == null || namespace.isEmpty()) {
                result.setErrorMessage("K8s命名空间不能为空（请在环境配置中填写命名空间）");
                return result;
            }
            if (deployment == null || deployment.isEmpty()) {
                result.setErrorMessage("K8s Deployment名称不能为空");
                return result;
            }

            String containerName = context.getService().getCode();
            String nodeDesc = nodeCommandHelper.describeDeployNode(context);
            logBuilder.append("执行节点: ").append(nodeDesc).append('\n');
            logBuilder.append("目标环境: ").append(envName != null ? envName : "-")
                    .append("，命名空间: ").append(namespace).append('\n');
            logBuilder.append("Deployment: ").append(deployment).append('\n');
            logBuilder.append("镜像: ").append(imageFullName).append('\n');
            context.emitLog(logBuilder.toString());

            String applyLog = applyRenderedManifests(stepParams, context, namespace);
            if (applyLog != null) {
                logBuilder.append(applyLog);
            } else {
                logBuilder.append("未找到渲染产物（manifestDir/renderedManifests），跳过 apply，仅更新镜像\n");
            }

            log.info("开始部署到K8s: namespace={}, deployment={}, image={}, container={}",
                    namespace, deployment, imageFullName, containerName);

            if (nodeCommandHelper.resolveDeployNodeId(context) != null) {
                String kubeSetup = k8sCredentialService.buildRemoteKubeconfigSetup(context.getExecutionId(),
                        context.getEnvironment() != null ? context.getEnvironment().getClusterId() : null, null);
                String command = kubeSetup
                        + "kubectl -n " + NodeCommandHelper.shellQuote(namespace)
                        + " set image " + NodeCommandHelper.shellQuote("deployment/" + deployment)
                        + " " + NodeCommandHelper.shellQuote(containerName + "=" + imageFullName);
                NodeCommandHelper.CommandResult cmdResult = nodeCommandHelper.runOnDeployNode(context, command);
                appendCmdOutput(logBuilder, cmdResult);
                if (!cmdResult.isSuccess()) {
                    result.setLog(logBuilder.toString());
                    result.setErrorMessage(buildKubectlFailureMessage(
                            "更新镜像失败", namespace, cmdResult));
                    return result;
                }
            } else {
                k8sClient.updateDeploymentImage(namespace, deployment, containerName, imageFullName);
                logBuilder.append(String.format("已触发部署: %s/%s -> %s\n", namespace, deployment, imageFullName));
            }

            String deployScript = stepParams.get("deployScript");
            if (deployScript != null && !deployScript.trim().isEmpty()
                    && nodeCommandHelper.resolveDeployNodeId(context) != null) {
                String kubeSetup = k8sCredentialService.buildRemoteKubeconfigSetup(context.getExecutionId(),
                        context.getEnvironment() != null ? context.getEnvironment().getClusterId() : null, null);
                NodeCommandHelper.CommandResult scriptResult = nodeCommandHelper.runOnDeployNode(context, kubeSetup + deployScript);
                appendCmdOutput(logBuilder, scriptResult);
                if (!scriptResult.isSuccess()) {
                    result.setLog(logBuilder.toString());
                    result.setErrorMessage(buildKubectlFailureMessage(
                            "额外部署脚本执行失败", namespace, scriptResult));
                    return result;
                }
            }

            result.setLog(logBuilder.toString());
            result.setSuccess(true);
            log.info("部署指令已下发");
        } catch (Exception e) {
            log.error("部署失败", e);
            if (logBuilder.length() > 0) {
                result.setLog(logBuilder.toString());
            }
            String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            result.setErrorMessage(enrichNamespaceHint("部署失败: " + msg, context));
        }

        return result;
    }

    /**
     * 应用「渲染模版」步骤产出的 YAML（优先 renderedManifests，其次 manifestDir）
     */
    private String applyRenderedManifests(Map<String, String> stepParams, PipelineExecutionContext context,
                                          String namespace) throws Exception {
        List<String> files = resolveManifestFiles(stepParams, context);
        if (files.isEmpty()) {
            return null;
        }

        StringBuilder logBuilder = new StringBuilder();
        logBuilder.append("应用渲染产物: ").append(String.join(", ", files)).append('\n');

        if (nodeCommandHelper.resolveDeployNodeId(context) != null) {
            String kubeSetup = k8sCredentialService.buildRemoteKubeconfigSetup(context.getExecutionId(),
                        context.getEnvironment() != null ? context.getEnvironment().getClusterId() : null, null);
            StringBuilder applyCmd = new StringBuilder(kubeSetup);
            for (String path : files) {
                String yaml = readManifestContent(path, context);
                applyCmd.append("kubectl -n ").append(NodeCommandHelper.shellQuote(namespace))
                        .append(" apply -f - <<'EOF'\n")
                        .append(yaml).append("\nEOF\n");
            }
            NodeCommandHelper.CommandResult cmdResult = nodeCommandHelper.runOnDeployNode(context, applyCmd.toString());
            appendCmdOutput(logBuilder, cmdResult);
            if (!cmdResult.isSuccess()) {
                throw new IllegalStateException(buildKubectlFailureMessage(
                        "kubectl apply 渲染产物失败", namespace, cmdResult));
            }
        } else {
            for (String path : files) {
                String yaml = readManifestContent(path, context);
                java.nio.file.Path tempFile = Files.createTempFile("opsflow-manifest-", ".yaml");
                Files.write(tempFile, yaml.getBytes(StandardCharsets.UTF_8));
                try {
                    ProcessBuilder pb = new ProcessBuilder("kubectl", "-n", namespace, "apply", "-f", tempFile.toString());
                    pb.redirectErrorStream(true);
                    Process process = pb.start();
                    String output = readStream(process.getInputStream());
                    logBuilder.append(output);
                    int code = process.waitFor();
                    if (code != 0) {
                        throw new IllegalStateException("kubectl apply 失败 [" + namespace + "]: " + trimOutput(output));
                    }
                } finally {
                    Files.deleteIfExists(tempFile);
                }
            }
        }
        return logBuilder.toString();
    }

    private void appendCmdOutput(StringBuilder logBuilder, NodeCommandHelper.CommandResult cmdResult) {
        if (cmdResult == null) {
            return;
        }
        if (cmdResult.getOutput() != null && !cmdResult.getOutput().trim().isEmpty()) {
            logBuilder.append(cmdResult.getOutput());
            if (!cmdResult.getOutput().endsWith("\n")) {
                logBuilder.append('\n');
            }
        }
        if (!cmdResult.isSuccess()
                && cmdResult.getErrorMessage() != null
                && !cmdResult.getErrorMessage().trim().isEmpty()
                && (cmdResult.getOutput() == null || !cmdResult.getOutput().contains(cmdResult.getErrorMessage()))) {
            logBuilder.append(cmdResult.getErrorMessage()).append('\n');
        }
    }

    private String buildKubectlFailureMessage(String prefix, String namespace,
                                              NodeCommandHelper.CommandResult cmdResult) {
        String detail = firstNonBlank(cmdResult.getOutput(), cmdResult.getErrorMessage());
        detail = trimOutput(detail);
        StringBuilder msg = new StringBuilder();
        msg.append(prefix).append(" [namespace=").append(namespace).append("]");
        if (detail != null && !detail.isEmpty()) {
            msg.append(": ").append(detail);
        }
        if (detail != null && detail.toLowerCase().contains("namespaces")
                && detail.toLowerCase().contains("not found")) {
            msg.append("。请确认集群中已存在该命名空间，或检查环境配置中的「命名空间」是否正确");
        }
        return msg.toString();
    }

    private String enrichNamespaceHint(String message, PipelineExecutionContext context) {
        if (message == null) {
            return null;
        }
        String lower = message.toLowerCase();
        if (lower.contains("namespaces") && lower.contains("not found")) {
            String ns = context != null && context.getEnvironment() != null
                    ? context.getEnvironment().getK8sNamespace() : null;
            return message + "。原因：目标命名空间"
                    + (ns != null ? "「" + ns + "」" : "")
                    + "在 K8s 集群中不存在；请先创建命名空间，或核对环境配置中的命名空间字段";
        }
        return message;
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.trim().isEmpty()) {
            return a.trim();
        }
        if (b != null && !b.trim().isEmpty()) {
            return b.trim();
        }
        return null;
    }

    private static String trimOutput(String output) {
        if (output == null) {
            return "";
        }
        String text = output.trim();
        if (text.length() > 2000) {
            return text.substring(0, 2000) + "...";
        }
        return text;
    }

    private List<String> resolveManifestFiles(Map<String, String> stepParams, PipelineExecutionContext context) {
        List<String> files = new ArrayList<>();
        String renderedManifests = stepParams.get("renderedManifests");
        if (renderedManifests != null && !renderedManifests.trim().isEmpty()) {
            files.addAll(Arrays.stream(renderedManifests.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .collect(Collectors.toList()));
            return files;
        }
        String manifestDir = stepParams.get("manifestDir");
        if (manifestDir == null || manifestDir.trim().isEmpty()) {
            return files;
        }
        File dir = new File(WorkspacePathHelper.toLocalPath(manifestDir.trim()));
        if (dir.isDirectory()) {
            File[] yamlFiles = dir.listFiles((d, name) ->
                    name.endsWith(".yaml") || name.endsWith(".yml"));
            if (yamlFiles != null) {
                Arrays.sort(yamlFiles, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
                for (File f : yamlFiles) {
                    files.add(f.getAbsolutePath());
                }
            }
            return files;
        }
        if (nodeCommandHelper.resolveBuildNodeId(context) != null) {
            String remoteDir = WorkspacePathHelper.toRemoteShellPath(manifestDir.trim());
            NodeCommandHelper.CommandResult listResult = nodeCommandHelper.runOnBuildNode(
                    context, "ls -1 " + remoteDir + "/*.yaml " + remoteDir + "/*.yml 2>/dev/null || true");
            if (listResult.isSuccess() && listResult.getOutput() != null) {
                for (String line : listResult.getOutput().split("\n")) {
                    String trimmed = line.trim();
                    if (!trimmed.isEmpty()) {
                        files.add(trimmed);
                    }
                }
            }
        }
        return files;
    }

    /**
     * 读取渲染产物内容：优先本机路径，否则从构建节点 SSH 读取。
     */
    private String readManifestContent(String path, PipelineExecutionContext context) throws Exception {
        File local = new File(WorkspacePathHelper.toLocalPath(path));
        if (local.isFile()) {
            return new String(Files.readAllBytes(local.toPath()), StandardCharsets.UTF_8);
        }
        if (nodeCommandHelper.resolveBuildNodeId(context) != null) {
            String remotePath = path.startsWith("/")
                    ? NodeCommandHelper.shellQuote(path)
                    : WorkspacePathHelper.toRemoteShellPath(path);
            NodeCommandHelper.CommandResult result = nodeCommandHelper.runOnBuildNode(context, "cat " + remotePath);
            if (result.isSuccess() && result.getOutput() != null && !result.getOutput().trim().isEmpty()) {
                return result.getOutput();
            }
            throw new IllegalStateException("无法从构建节点读取渲染产物: " + path
                    + (result.getErrorMessage() != null ? " - " + result.getErrorMessage() : ""));
        }
        throw new IllegalStateException("渲染产物不存在: " + path);
    }

    private static String readStream(InputStream inputStream) throws Exception {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] data = new byte[4096];
        int n;
        while ((n = inputStream.read(data)) != -1) {
            buffer.write(data, 0, n);
        }
        return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
    }

    @Override
    public boolean supports(String stepType) {
        return "deploy".equalsIgnoreCase(stepType);
    }
}
