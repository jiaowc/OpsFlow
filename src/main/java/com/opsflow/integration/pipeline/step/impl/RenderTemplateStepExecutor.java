package com.opsflow.integration.pipeline.step.impl;

import com.opsflow.integration.pipeline.NodeCommandHelper;
import com.opsflow.integration.pipeline.PipelineExecutionContext;
import com.opsflow.integration.pipeline.PipelineTemplateRenderer;
import com.opsflow.integration.pipeline.WorkspacePathHelper;
import com.opsflow.integration.pipeline.step.StepExecutor;
import com.opsflow.integration.pipeline.step.StepExecutionResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 渲染 K8s 部署模版（Deployment / Service YAML）
 */
@Slf4j
@Component
public class RenderTemplateStepExecutor implements StepExecutor {

    @Autowired
    private NodeCommandHelper nodeCommandHelper;

    /**
     * 渲染 K8s Deployment/Service YAML 模版，输出至 workspace 供 deploy 步骤 apply。
     * <p><b>模版变量来源</b></p>
     * 调用 {@link PipelineTemplateRenderer#buildVariables(PipelineExecutionContext, Map)} 合并：
     * <ul>
     *   <li>服务信息：serviceName/serviceCode、deployment、branch、servicePort；</li>
     *   <li>环境信息：env、namespace；</li>
     *   <li>{@code context.parameters}（含 buildContext 写入的 port、k8sNamespace、imageFullName 等）；</li>
     *   <li>{@code stepParams} 中的 imageFullName/imageTag/commitId/serviceType 等（步骤级覆盖）。</li>
     * </ul>
     * <p><b>与 image / servicePort 的关系</b></p>
     * <ul>
     *   <li>{@code image}/${imageFullName}：前置 push_image 步骤写入 stepOutputs 后，经参数合并进入 stepParams，
     *       再进入 variables，供 Deployment 模版 {@code ${image}} 占位符；CD 仅部署场景可由 buildParameters 预填；</li>
     *   <li>{@code servicePort}/${port}：来自服务配置或 parameters，供 Service 模版端口占位符，
     *       缺省 8080。</li>
     * </ul>
     * 渲染结果写入 {@code outputDir}（默认 manifests），并通过 outputData 输出 {@code manifestDir}、
     * {@code renderedManifests} 供 {@link DeployStepExecutor} 使用。
     *
     * @param stepType   步骤类型（render_template）
     * @param stepParams 含 deploymentTemplateContent、serviceTemplateContent、outputDir 等
     * @param context    流水线执行上下文
     * @return 成功时 outputData 含 manifest 路径；失败时含 errorMessage
     */
    @Override
    public StepExecutionResult execute(String stepType, Map<String, String> stepParams, PipelineExecutionContext context) {
        StepExecutionResult result = new StepExecutionResult();
        result.setSuccess(false);
        result.setOutputData(new HashMap<>());

        try {
            String deploymentTemplate = stepParams.get("deploymentTemplateContent");
            String serviceTemplate = stepParams.get("serviceTemplateContent");
            String outputDir = stepParams.getOrDefault("outputDir", "manifests");

            boolean hasDeployment = deploymentTemplate != null && !deploymentTemplate.trim().isEmpty();
            boolean hasService = serviceTemplate != null && !serviceTemplate.trim().isEmpty();
            if (!hasDeployment && !hasService) {
                result.setErrorMessage("请配置 Deployment 或 Service 模版内容");
                return result;
            }

            Map<String, String> variables = PipelineTemplateRenderer.buildVariables(context, stepParams);
            String workspace = context.getWorkspace();
            if (workspace == null || workspace.trim().isEmpty()) {
                result.setErrorMessage("工作目录未配置");
                return result;
            }

            String nodeDesc = nodeCommandHelper.describeBuildNode(context);
            boolean remoteBuildNode = nodeCommandHelper.resolveBuildNodeId(context) != null;
            String manifestDir = workspace + "/" + outputDir;
            StringBuilder logBuilder = new StringBuilder();
            logBuilder.append("执行节点: ").append(nodeDesc).append('\n')
                    .append("工作目录: ").append(workspace).append('\n')
                    .append("输出目录: ").append(manifestDir).append('\n');
            context.emitLog(logBuilder.toString());
            List<String> renderedFiles = new ArrayList<>();

            if (remoteBuildNode) {
                String outDirShell = WorkspacePathHelper.toRemoteShellPath(manifestDir);
                String mkdirCmd = "mkdir -p " + outDirShell;
                NodeCommandHelper.CommandResult mkdirResult =
                        nodeCommandHelper.runOnBuildNode(context, mkdirCmd, workspace);
                if (!mkdirResult.isSuccess()) {
                    result.setLog(logBuilder.toString());
                    result.setErrorMessage(mkdirResult.getErrorMessage() != null
                            ? mkdirResult.getErrorMessage()
                            : "无法在构建节点创建输出目录: " + manifestDir);
                    return result;
                }

                if (hasDeployment) {
                    String logicalPath = manifestDir + "/deployment.yaml";
                    String remoteFileShell = WorkspacePathHelper.toRemoteShellPath(logicalPath);
                    String content = PipelineTemplateRenderer.render(deploymentTemplate, variables);
                    String writeCmd = "cat <<'EOF' > " + remoteFileShell + "\n"
                            + content + "\nEOF";
                    NodeCommandHelper.CommandResult writeResult =
                            nodeCommandHelper.runOnBuildNode(context, writeCmd, workspace);
                    if (!writeResult.isSuccess()) {
                        result.setLog(logBuilder.toString());
                        result.setErrorMessage(writeResult.getErrorMessage() != null
                                ? writeResult.getErrorMessage()
                                : "写入 deployment.yaml 失败");
                        return result;
                    }
                    renderedFiles.add(logicalPath);
                    appendRenderedContentLog(logBuilder, context, "deployment.yaml", logicalPath, content);
                }

                if (hasService) {
                    String logicalPath = manifestDir + "/service.yaml";
                    String remoteFileShell = WorkspacePathHelper.toRemoteShellPath(logicalPath);
                    String content = PipelineTemplateRenderer.render(serviceTemplate, variables);
                    String writeCmd = "cat <<'EOF' > " + remoteFileShell + "\n"
                            + content + "\nEOF";
                    NodeCommandHelper.CommandResult writeResult =
                            nodeCommandHelper.runOnBuildNode(context, writeCmd, workspace);
                    if (!writeResult.isSuccess()) {
                        result.setLog(logBuilder.toString());
                        result.setErrorMessage(writeResult.getErrorMessage() != null
                                ? writeResult.getErrorMessage()
                                : "写入 service.yaml 失败");
                        return result;
                    }
                    renderedFiles.add(logicalPath);
                    appendRenderedContentLog(logBuilder, context, "service.yaml", logicalPath, content);
                }
            } else {
                String localWorkspace = WorkspacePathHelper.toLocalPath(workspace);
                File outDir = new File(localWorkspace, outputDir);
                if (!outDir.exists() && !outDir.mkdirs()) {
                    result.setErrorMessage("无法创建输出目录: " + outDir.getAbsolutePath());
                    return result;
                }

                if (hasDeployment) {
                    File deploymentFile = new File(outDir, "deployment.yaml");
                    String content = PipelineTemplateRenderer.render(deploymentTemplate, variables);
                    Files.write(deploymentFile.toPath(), content.getBytes(StandardCharsets.UTF_8));
                    renderedFiles.add(deploymentFile.getAbsolutePath());
                    appendRenderedContentLog(logBuilder, context, "deployment.yaml",
                            deploymentFile.getAbsolutePath(), content);
                }

                if (hasService) {
                    File serviceFile = new File(outDir, "service.yaml");
                    String content = PipelineTemplateRenderer.render(serviceTemplate, variables);
                    Files.write(serviceFile.toPath(), content.getBytes(StandardCharsets.UTF_8));
                    renderedFiles.add(serviceFile.getAbsolutePath());
                    appendRenderedContentLog(logBuilder, context, "service.yaml",
                            serviceFile.getAbsolutePath(), content);
                }
                manifestDir = new File(localWorkspace, outputDir).getAbsolutePath();
            }

            result.getOutputData().put("manifestDir", manifestDir);
            result.getOutputData().put("renderedManifests", String.join(",", renderedFiles));
            result.setLog(logBuilder.toString());
            result.setSuccess(true);
            log.info("模版渲染完成，输出目录: {}，节点: {}", manifestDir, nodeDesc);
        } catch (Exception e) {
            log.error("渲染模版失败", e);
            result.setErrorMessage("渲染模版失败: " + e.getMessage());
        }

        return result;
    }

    private void appendRenderedContentLog(StringBuilder logBuilder,
                                          PipelineExecutionContext context,
                                          String fileName,
                                          String path,
                                          String content) {
        StringBuilder section = new StringBuilder();
        section.append("已渲染 ").append(fileName).append(" -> ").append(path).append('\n');
        section.append("----- 渲染结果: ").append(fileName).append(" -----\n");
        section.append(content == null ? "" : content);
        if (content != null && !content.isEmpty() && !content.endsWith("\n")) {
            section.append('\n');
        }
        section.append("----- END: ").append(fileName).append(" -----\n");
        String text = section.toString();
        logBuilder.append(text);
        if (context != null) {
            context.emitLog(text);
        }
    }

    @Override
    public boolean supports(String stepType) {
        return "render_template".equalsIgnoreCase(stepType);
    }
}
