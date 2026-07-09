package com.opsflow.integration.pipeline.step.impl;

import com.opsflow.integration.pipeline.PipelineExecutionContext;
import com.opsflow.integration.pipeline.WorkspacePathHelper;
import com.opsflow.integration.pipeline.step.StepExecutor;
import com.opsflow.integration.pipeline.step.StepExecutionResult;
import lombok.extern.slf4j.Slf4j;
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

            Map<String, String> variables = buildVariables(context, stepParams);
            String workspace = WorkspacePathHelper.toLocalPath(context.getWorkspace());
            File outDir = new File(workspace, outputDir);
            if (!outDir.exists() && !outDir.mkdirs()) {
                result.setErrorMessage("无法创建输出目录: " + outDir.getAbsolutePath());
                return result;
            }

            StringBuilder logBuilder = new StringBuilder();
            List<String> renderedFiles = new ArrayList<>();

            if (hasDeployment) {
                File deploymentFile = new File(outDir, "deployment.yaml");
                String content = renderTemplate(deploymentTemplate, variables);
                Files.write(deploymentFile.toPath(), content.getBytes(StandardCharsets.UTF_8));
                renderedFiles.add(deploymentFile.getAbsolutePath());
                logBuilder.append("已渲染 deployment.yaml -> ").append(deploymentFile.getAbsolutePath()).append('\n');
            }

            if (hasService) {
                File serviceFile = new File(outDir, "service.yaml");
                String content = renderTemplate(serviceTemplate, variables);
                Files.write(serviceFile.toPath(), content.getBytes(StandardCharsets.UTF_8));
                renderedFiles.add(serviceFile.getAbsolutePath());
                logBuilder.append("已渲染 service.yaml -> ").append(serviceFile.getAbsolutePath()).append('\n');
            }

            result.getOutputData().put("manifestDir", outDir.getAbsolutePath());
            result.getOutputData().put("renderedManifests", String.join(",", renderedFiles));
            result.setLog(logBuilder.toString());
            result.setSuccess(true);
            log.info("模版渲染完成，输出目录: {}", outDir.getAbsolutePath());
        } catch (Exception e) {
            log.error("渲染模版失败", e);
            result.setErrorMessage("渲染模版失败: " + e.getMessage());
        }

        return result;
    }

    private Map<String, String> buildVariables(PipelineExecutionContext context, Map<String, String> stepParams) {
        Map<String, String> variables = new HashMap<>();
        if (context.getService() != null) {
            putIfPresent(variables, "service", context.getService().getName());
            putIfPresent(variables, "serviceName", context.getService().getName());
            putIfPresent(variables, "serviceCode", context.getService().getCode());
            putIfPresent(variables, "deployment", context.getService().getK8sDeployment());
            putIfPresent(variables, "branch", context.getService().getBranch());
        }
        if (context.getEnvironment() != null) {
            putIfPresent(variables, "env", context.getEnvironment().getName());
            putIfPresent(variables, "namespace", context.getEnvironment().getK8sNamespace());
        }
        if (context.getParameters() != null) {
            variables.putAll(context.getParameters());
        }
        if (stepParams != null) {
            putIfPresent(variables, "image", stepParams.get("imageFullName"));
            putIfPresent(variables, "imageFullName", stepParams.get("imageFullName"));
            putIfPresent(variables, "imageTag", stepParams.get("imageTag"));
            putIfPresent(variables, "commitId", stepParams.get("commitId"));
        }
        return variables;
    }

    private void putIfPresent(Map<String, String> variables, String key, String value) {
        if (value != null && !value.isEmpty()) {
            variables.put(key, value);
        }
    }

    private String renderTemplate(String template, Map<String, String> variables) {
        String rendered = template;
        for (Map.Entry<String, String> entry : variables.entrySet()) {
            rendered = rendered.replace("${" + entry.getKey() + "}", entry.getValue());
        }
        return rendered;
    }

    @Override
    public boolean supports(String stepType) {
        return "render_template".equalsIgnoreCase(stepType);
    }
}
