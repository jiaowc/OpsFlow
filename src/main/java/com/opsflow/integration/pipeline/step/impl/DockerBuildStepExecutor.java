package com.opsflow.integration.pipeline.step.impl;

import com.opsflow.integration.harbor.HarborCredentialService;
import com.opsflow.integration.pipeline.NodeCommandHelper;
import com.opsflow.integration.pipeline.PipelineExecutionContext;
import com.opsflow.integration.pipeline.PipelineTemplateRenderer;
import com.opsflow.integration.pipeline.WorkspacePathHelper;
import com.opsflow.integration.pipeline.step.StepExecutor;
import com.opsflow.integration.pipeline.step.StepExecutionResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

/**
 * Docker 镜像制作：1) 写入 Dockerfile  2) docker build
 * 默认命令：docker build -f ${dockerfilePath} -t ${harbor}/${namespace}/${serviceName}:${tag} ${workspace}
 * tag 为当前时间戳（yyyyMMddHHmmss）
 */
@Slf4j
@Component
public class DockerBuildStepExecutor implements StepExecutor {

    public static final String DEFAULT_DOCKER_BUILD_COMMAND =
            "docker build -f ${dockerfilePath} -t ${harbor}/${namespace}/${serviceName}:${tag} ${workspace}";

    @Autowired
    private NodeCommandHelper nodeCommandHelper;

    @Autowired
    private HarborCredentialService harborCredentialService;

    @Override
    public StepExecutionResult execute(String stepType, Map<String, String> stepParams, PipelineExecutionContext context) {
        StepExecutionResult result = new StepExecutionResult();
        result.setSuccess(false);
        result.setOutputData(new HashMap<>());

        try {
            String workspace = context.getWorkspace();
            String nodeDesc = nodeCommandHelper.describeBuildNode(context);
            StringBuilder logBuilder = new StringBuilder();
            logBuilder.append("执行节点: ").append(nodeDesc).append('\n')
                    .append("工作目录: ").append(workspace).append('\n');
            context.emitLog(logBuilder.toString());

            String harbor = resolveHarbor(context);
            if (harbor == null || harbor.isEmpty()) {
                result.setErrorMessage("Harbor 地址未配置，请在组件管理中配置 Harbor 组件");
                return result;
            }
            String namespace = resolveNamespace(context);
            String serviceName = context.getService().getCode();
            String tag = buildImageTag(stepParams);
            String imageFullName = harbor + "/" + namespace + "/" + serviceName + ":" + tag;

            String dockerfilePath = stepParams.getOrDefault("dockerfilePath", context.getService().getDockerfilePath());
            if (dockerfilePath == null || dockerfilePath.trim().isEmpty()) {
                dockerfilePath = "Dockerfile";
            }
            String dockerfileShell = WorkspacePathHelper.toRemoteShellPath(workspace + "/" + dockerfilePath);
            String workspaceShell = WorkspacePathHelper.toRemoteShellPath(workspace);

            Map<String, String> buildVars = new HashMap<>();
            buildVars.put("harbor", harbor);
            buildVars.put("namespace", namespace);
            buildVars.put("serviceName", serviceName);
            buildVars.put("serviceCode", serviceName);
            buildVars.put("tag", tag);
            buildVars.put("imageTag", tag);
            buildVars.put("image", imageFullName);
            buildVars.put("imageFullName", imageFullName);
            buildVars.put("dockerfilePath", dockerfileShell);
            buildVars.put("workspace", workspaceShell);

            // ---- 1) 写入 Dockerfile（模版内容）----
            String dockerfileContent = stepParams.get("dockerfileContent");
            if (dockerfileContent != null && !dockerfileContent.trim().isEmpty()) {
                Map<String, String> renderParams = new HashMap<>(stepParams);
                renderParams.putAll(buildVars);
                dockerfileContent = PipelineTemplateRenderer.render(dockerfileContent, context, renderParams);
                String writeCmd = "cat <<'EOF' > " + dockerfileShell + "\n"
                        + dockerfileContent + "\nEOF\n"
                        + "echo \"Dockerfile 已写入: " + dockerfileShell + "\"\n"
                        + "ls -la " + dockerfileShell;
                logBuilder.append("\n======== [1/2] 写入 Dockerfile ========\n");
                context.emitLog("\n======== [1/2] 写入 Dockerfile ========\n");
                log.info("写入 Dockerfile: {}", dockerfileShell);
                NodeCommandHelper.CommandResult writeResult =
                        nodeCommandHelper.runOnBuildNode(context, writeCmd, workspace);
                if (writeResult.getOutput() != null) {
                    logBuilder.append(writeResult.getOutput());
                }
                if (!writeResult.isSuccess()) {
                    result.setLog(logBuilder.toString());
                    result.setErrorMessage(writeResult.getErrorMessage() != null
                            ? writeResult.getErrorMessage()
                            : "写入 Dockerfile 失败，退出码: " + writeResult.getExitCode());
                    return result;
                }
            } else {
                logBuilder.append("\n======== [1/2] 写入 Dockerfile ========\n")
                        .append("未配置模版内容，使用工作目录已有文件: ").append(dockerfilePath).append('\n');
            }

            // ---- 2) docker build ----
            String dockerBuildCommand = resolveDockerBuildCommand(stepParams, buildVars);
            logBuilder.append("\n======== [2/2] docker build ========\n")
                    .append("$ ").append(dockerBuildCommand).append('\n');
            context.emitLog("\n======== [2/2] docker build ========\n$ " + dockerBuildCommand + "\n");
            log.info("执行 docker build: {}", dockerBuildCommand);

            NodeCommandHelper.CommandResult buildResult =
                    nodeCommandHelper.runOnBuildNode(context, dockerBuildCommand, workspace);
            if (buildResult.getOutput() != null) {
                logBuilder.append(buildResult.getOutput());
            }
            if (!buildResult.isSuccess()) {
                result.setLog(logBuilder.toString());
                result.setErrorMessage(buildResult.getErrorMessage() != null
                        ? buildResult.getErrorMessage()
                        : "docker build 失败，退出码: " + buildResult.getExitCode());
                return result;
            }

            logBuilder.append("\n-------- docker build 完成 --------\n")
                    .append("镜像全名: ").append(imageFullName).append('\n')
                    .append("harbor: ").append(harbor).append('\n')
                    .append("namespace: ").append(namespace).append('\n')
                    .append("serviceName: ").append(serviceName).append('\n')
                    .append("tag: ").append(tag).append('\n')
                    .append("Dockerfile: ").append(dockerfilePath).append('\n');

            String inspectCmd = "docker images --format 'table {{.Repository}}:{{.Tag}}\\t{{.ID}}\\t{{.Size}}\\t{{.CreatedSince}}' "
                    + NodeCommandHelper.shellQuote(imageFullName)
                    + " 2>/dev/null || docker images | grep -F "
                    + NodeCommandHelper.shellQuote(serviceName + ":" + tag)
                    + " || true";
            NodeCommandHelper.CommandResult inspectResult =
                    nodeCommandHelper.runOnBuildNode(context, inspectCmd, workspace);
            if (inspectResult.getOutput() != null && !inspectResult.getOutput().trim().isEmpty()) {
                logBuilder.append("本地镜像:\n").append(inspectResult.getOutput().trim()).append('\n');
            }

            result.getOutputData().put("imageTag", tag);
            result.getOutputData().put("tag", tag);
            result.getOutputData().put("imageFullName", imageFullName);
            result.getOutputData().put("image", imageFullName);
            result.getOutputData().put("dockerfilePath", dockerfilePath);
            result.getOutputData().put("harbor", harbor);
            result.getOutputData().put("harborRegistry", harbor);
            result.getOutputData().put("namespace", namespace);
            result.getOutputData().put("serviceName", serviceName);
            result.getOutputData().put("serviceCode", serviceName);

            boolean pushAfterBuild = Boolean.parseBoolean(stepParams.getOrDefault(
                    "pushAfterBuild",
                    String.valueOf("docker-build".equalsIgnoreCase(stepType))
            ));

            if (!pushAfterBuild) {
                result.setLog(logBuilder.toString());
                result.setSuccess(true);
                log.info("Docker 镜像构建成功: {}", imageFullName);
                return result;
            }

            logBuilder.append("\n======== 推送镜像 ========\n");
            log.info("Docker 镜像构建成功，开始推送到 Harbor: {}", imageFullName);
            String registry = HarborCredentialService.extractRegistryFromImage(imageFullName);
            String loginPrefix = harborCredentialService.buildDockerLoginPrefix(registry, null);
            NodeCommandHelper.CommandResult pushResult = nodeCommandHelper.runOnBuildNode(
                    context,
                    loginPrefix + "docker push " + NodeCommandHelper.shellQuote(imageFullName),
                    workspace
            );
            if (pushResult.getOutput() != null) {
                logBuilder.append(pushResult.getOutput());
            }
            if (!pushResult.isSuccess()) {
                result.setLog(logBuilder.toString());
                result.setErrorMessage(pushResult.getErrorMessage() != null
                        ? pushResult.getErrorMessage()
                        : "Docker 镜像推送失败，退出码: " + pushResult.getExitCode());
                return result;
            }

            result.setLog(logBuilder.toString());
            result.setSuccess(true);
            log.info("Docker 镜像推送成功: {}", imageFullName);
        } catch (Exception e) {
            log.error("Docker 镜像构建或推送失败", e);
            result.setErrorMessage("Docker 镜像构建或推送失败: " + e.getMessage());
        }

        return result;
    }

    private String resolveHarbor(PipelineExecutionContext context) {
        String harborRegistry = null;
        if (context.getEnvironment() != null) {
            harborRegistry = context.getEnvironment().getHarborRegistry();
        }
        if (harborRegistry == null || harborRegistry.trim().isEmpty()) {
            harborRegistry = harborCredentialService.getRegistryHost(null);
        }
        return harborRegistry == null ? null : harborRegistry.trim();
    }

    /**
     * Harbor 路径中的 namespace：优先 K8s 命名空间，否则用环境名称
     */
    private String resolveNamespace(PipelineExecutionContext context) {
        if (context.getEnvironment() == null) {
            return "default";
        }
        String ns = context.getEnvironment().getK8sNamespace();
        if (ns != null && !ns.trim().isEmpty()) {
            return ns.trim();
        }
        String envName = context.getEnvironment().getName();
        if (envName != null && !envName.trim().isEmpty()) {
            return envName.trim();
        }
        return "default";
    }

    /**
     * 默认：docker build -f ${dockerfilePath} -t ${harbor}/${namespace}/${serviceName}:${tag} ${workspace}
     */
    private String resolveDockerBuildCommand(Map<String, String> stepParams, Map<String, String> buildVars) {
        String custom = stepParams.get("dockerBuildCommand");
        String template = (custom != null && !custom.trim().isEmpty())
                ? custom.trim()
                : DEFAULT_DOCKER_BUILD_COMMAND;

        String extraArgs = stepParams.get("dockerBuildArgs");
        if (extraArgs != null && !extraArgs.trim().isEmpty()
                && (custom == null || custom.trim().isEmpty())) {
            // 仅在使用默认命令时插入额外参数
            template = "docker build " + extraArgs.trim()
                    + " -f ${dockerfilePath} -t ${harbor}/${namespace}/${serviceName}:${tag} ${workspace}";
        }

        String cmd = template;
        for (Map.Entry<String, String> entry : buildVars.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) {
                continue;
            }
            cmd = cmd.replace("${" + entry.getKey() + "}", entry.getValue());
        }
        // -t 镜像名加引号，避免特殊字符
        if (cmd.contains(" -t ") && !cmd.contains(" -t '") && !cmd.contains(" -t \"")) {
            String image = buildVars.get("imageFullName");
            if (image != null) {
                cmd = cmd.replace(" -t " + image, " -t " + NodeCommandHelper.shellQuote(image));
            }
        }
        return cmd;
    }

    /** tag 默认当前时间戳 yyyyMMddHHmmss */
    private String buildImageTag(Map<String, String> stepParams) {
        String customTag = stepParams.get("tag");
        if (customTag != null && !customTag.trim().isEmpty()) {
            return customTag.trim();
        }
        String tagFormat = stepParams.get("tagFormat");
        String timestamp = new SimpleDateFormat("yyyyMMddHHmmss").format(new Date());
        if (tagFormat != null && !tagFormat.trim().isEmpty()) {
            return String.format(tagFormat, timestamp);
        }
        return timestamp;
    }

    @Override
    public boolean supports(String stepType) {
        return "docker-build".equalsIgnoreCase(stepType)
                || "dockerbuild".equalsIgnoreCase(stepType)
                || "docker_build".equalsIgnoreCase(stepType);
    }
}
