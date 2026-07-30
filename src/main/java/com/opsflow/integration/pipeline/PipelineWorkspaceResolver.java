package com.opsflow.integration.pipeline;

import java.util.Map;

/**
 * 解析 Pipeline 在构建节点上的工作目录路径
 */
public final class PipelineWorkspaceResolver {

    private static final String DEFAULT_BASE = WorkspacePathHelper.DEFAULT_WORK_DIR;

    private PipelineWorkspaceResolver() {
    }

    public static String resolve(PipelineExecutionContext context, Map<String, String> stepParams) {
        if (stepParams != null) {
            String customPath = stepParams.get("workspacePath");
            if (customPath != null && !customPath.trim().isEmpty()) {
                return customPath.trim();
            }
        }

        String base = DEFAULT_BASE;
        if (stepParams != null && stepParams.get("workspaceBase") != null && !stepParams.get("workspaceBase").trim().isEmpty()) {
            base = stepParams.get("workspaceBase").trim();
        } else if (context.getParameters() != null
            && context.getParameters().get("workspaceBase") != null
            && !context.getParameters().get("workspaceBase").trim().isEmpty()) {
            base = context.getParameters().get("workspaceBase").trim();
        }

        String serviceCode = "service";
        String envName = "env";
        String branch = "default";
        if (context.getService() != null) {
            if (context.getService().getCode() != null && !context.getService().getCode().isEmpty()) {
                serviceCode = sanitizePathSegment(context.getService().getCode());
            }
            if (context.getService().getBranch() != null && !context.getService().getBranch().isEmpty()) {
                branch = sanitizePathSegment(context.getService().getBranch());
            }
        }
        if (context.getEnvironment() != null && context.getEnvironment().getName() != null
            && !context.getEnvironment().getName().isEmpty()) {
            envName = sanitizePathSegment(context.getEnvironment().getName());
        }

        return base + "/" + serviceCode + "/" + envName + "/" + branch;
    }

    private static String sanitizePathSegment(String value) {
        return value.replaceAll("[^a-zA-Z0-9._-]", "_");
    }
}
