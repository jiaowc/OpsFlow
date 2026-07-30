package com.opsflow.integration.pipeline.step.impl;

import com.opsflow.integration.harbor.HarborCredentialService;
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
 * 上传镜像到 Harbor：docker login + docker push
 * 镜像地址来自上一步「镜像制作」输出的 imageFullName
 */
@Slf4j
@Component
public class PushImageStepExecutor implements StepExecutor {

    public static final String DEFAULT_DOCKER_PUSH_COMMAND = "docker push ${imageFullName}";

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
            String imageFullName = firstNonEmpty(stepParams.get("imageFullName"), stepParams.get("image"));
            String imageTag = firstNonEmpty(stepParams.get("imageTag"), stepParams.get("tag"));
            String nodeDesc = nodeCommandHelper.describeBuildNode(context);
            StringBuilder logBuilder = new StringBuilder();
            logBuilder.append("执行节点: ").append(nodeDesc).append('\n')
                    .append("工作目录: ").append(workspace).append('\n');
            context.emitLog(logBuilder.toString());

            if (imageFullName == null || imageFullName.isEmpty()) {
                result.setLog(logBuilder.toString());
                result.setErrorMessage("镜像名称不能为空，请先执行镜像制作步骤（需输出 imageFullName）");
                return result;
            }

            String registry = HarborCredentialService.extractRegistryFromImage(imageFullName);
            if (registry == null || registry.isEmpty()) {
                registry = harborCredentialService.getRegistryHost(null);
            }

            // ---- 1) 展示待上传镜像信息 ----
            String section1 = "\n======== [1/3] 待上传镜像 ========\n"
                    + "镜像全名: " + imageFullName + "\n"
                    + "镜像标签: " + (imageTag != null ? imageTag : "-") + "\n"
                    + "Harbor 地址: " + (registry != null ? registry : "-") + "\n";
            logBuilder.append(section1)
                    .append("harbor: ").append(nullToDash(stepParams.get("harbor"))).append('\n')
                    .append("namespace: ").append(nullToDash(stepParams.get("namespace"))).append('\n')
                    .append("serviceName: ").append(nullToDash(firstNonEmpty(
                            stepParams.get("serviceName"), stepParams.get("serviceCode")))).append('\n');
            context.emitLog(section1);

            // ---- 2) docker login ----
            logBuilder.append("\n======== [2/3] docker login ========\n");
            context.emitLog("\n======== [2/3] docker login ========\n");
            String loginPrefix = harborCredentialService.buildDockerLoginPrefix(registry, null);
            if (loginPrefix == null || loginPrefix.trim().isEmpty()) {
                logBuilder.append("警告: 未配置 Harbor 登录凭据，将直接尝试 docker push（可能因未登录失败）\n");
                log.warn("Harbor 登录凭据为空，registry={}", registry);
            } else {
                logBuilder.append("$ docker login ").append(registry != null ? registry : "").append(" (凭据已注入，密码不落日志)\n");
                // 单独执行 login，便于在日志中区分登录失败 / 推送失败
                String loginOnly = loginPrefix.endsWith(" && ")
                        ? loginPrefix.substring(0, loginPrefix.length() - 4)
                        : loginPrefix;
                loginOnly = loginOnly + " && echo 'docker login 成功: " + registry + "'";
                NodeCommandHelper.CommandResult loginResult =
                        nodeCommandHelper.runOnBuildNode(context, loginOnly, workspace);
                String loginOut = sanitizeLoginOutput(loginResult.getOutput());
                if (loginOut != null && !loginOut.isEmpty()) {
                    logBuilder.append(loginOut).append('\n');
                }
                if (!loginResult.isSuccess()) {
                    result.setLog(logBuilder.toString());
                    result.setErrorMessage(loginResult.getErrorMessage() != null
                            ? "docker login 失败: " + loginResult.getErrorMessage()
                            : "docker login 失败，退出码: " + loginResult.getExitCode());
                    return result;
                }
            }

            // ---- 3) docker push ----
            String pushCommand = resolveDockerPushCommand(stepParams, imageFullName);
            logBuilder.append("\n======== [3/3] docker push ========\n")
                    .append("$ ").append(pushCommand).append('\n');
            context.emitLog("\n======== [3/3] docker push ========\n$ " + pushCommand + "\n");
            log.info("上传镜像: {}", imageFullName);

            NodeCommandHelper.CommandResult pushResult =
                    nodeCommandHelper.runOnBuildNode(context, pushCommand, workspace);
            if (pushResult.getOutput() != null) {
                logBuilder.append(pushResult.getOutput());
            }
            if (!pushResult.isSuccess()) {
                result.setLog(logBuilder.toString());
                result.setErrorMessage(pushResult.getErrorMessage() != null
                        ? pushResult.getErrorMessage()
                        : "docker push 失败，退出码: " + pushResult.getExitCode());
                return result;
            }

            logBuilder.append("\n-------- docker push 完成 --------\n")
                    .append("已推送镜像: ").append(imageFullName).append('\n')
                    .append("Harbor: ").append(registry != null ? registry : "-").append('\n');

            result.getOutputData().put("imageFullName", imageFullName);
            result.getOutputData().put("image", imageFullName);
            if (imageTag != null) {
                result.getOutputData().put("imageTag", imageTag);
                result.getOutputData().put("tag", imageTag);
            }
            result.setLog(logBuilder.toString());
            result.setSuccess(true);
            log.info("镜像上传成功: {}", imageFullName);
        } catch (Exception e) {
            log.error("镜像上传失败", e);
            result.setErrorMessage("镜像上传失败: " + e.getMessage());
        }

        return result;
    }

    private String resolveDockerPushCommand(Map<String, String> stepParams, String imageFullName) {
        String custom = stepParams.get("dockerPushCommand");
        String template = (custom != null && !custom.trim().isEmpty())
                ? custom.trim()
                : DEFAULT_DOCKER_PUSH_COMMAND;
        String extraArgs = stepParams.get("dockerPushArgs");
        if (extraArgs != null && !extraArgs.trim().isEmpty()
                && (custom == null || custom.trim().isEmpty())) {
            template = "docker push " + extraArgs.trim() + " ${imageFullName}";
        }
        return template
                .replace("${image}", NodeCommandHelper.shellQuote(imageFullName))
                .replace("${imageFullName}", NodeCommandHelper.shellQuote(imageFullName));
    }

    /** 避免把 login 命令中的密码回显进阶段日志 */
    private String sanitizeLoginOutput(String output) {
        if (output == null || output.isEmpty()) {
            return output;
        }
        // 粗略过滤可能回显的敏感片段
        return output.replaceAll("(?i)password[^\\s]*", "password=***");
    }

    private static String firstNonEmpty(String first, String second) {
        if (first != null && !first.trim().isEmpty()) {
            return first.trim();
        }
        if (second != null && !second.trim().isEmpty()) {
            return second.trim();
        }
        return null;
    }

    private static String nullToDash(String value) {
        return value == null || value.trim().isEmpty() ? "-" : value.trim();
    }

    @Override
    public boolean supports(String stepType) {
        return "push_image".equalsIgnoreCase(stepType);
    }
}
