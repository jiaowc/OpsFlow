package com.opsflow.integration.pipeline;

import java.util.HashMap;
import java.util.Map;

/**
 * 流水线模版变量收集与 ${key} 占位符渲染工具。
 * <p>
 * 在 render_template、deploy 等步骤执行前，将服务信息、环境信息、构建参数
 * 合并为变量表，用于渲染 Dockerfile、Deployment、Service 等 K8s 模版文本。
 * 变量名与模版中 ${serviceName}、${port}、${image} 等占位符一一对应。
 * </p>
 */
public final class PipelineTemplateRenderer {

    private PipelineTemplateRenderer() {
    }

    /**
     * 从执行上下文与步骤参数构建模版变量表。
     * <p>步骤参数优先级高于上下文参数；port 与 servicePort 互为别名。</p>
     *
     * @param context 流水线执行上下文（服务、环境、全局参数）
     * @param stepParams 当前步骤参数（镜像、tag、serviceType 等）
     * @return 变量名 → 替换值的映射
     */
    public static Map<String, String> buildVariables(PipelineExecutionContext context, Map<String, String> stepParams) {
        Map<String, String> variables = new HashMap<>();
        if (context != null && context.getService() != null) {
            PipelineExecutionContext.ServiceInfo service = context.getService();
            putIfPresent(variables, "service", service.getName());
            // serviceName：模版中的服务标识（对应 code，由服务名称生成，适合镜像/K8s）
            putIfPresent(variables, "serviceName", service.getCode());
            // 兼容旧模版中的 ${serviceCode}
            putIfPresent(variables, "serviceCode", service.getCode());
            putIfPresent(variables, "deployment", service.getK8sDeployment());
            putIfPresent(variables, "branch", service.getBranch());
            variables.put("servicePort", resolveServicePort(service.getServicePort()));
            // 兼容模版中写 ${port}
            variables.put("port", resolveServicePort(service.getServicePort()));
        }
        if (context != null && context.getEnvironment() != null) {
            putIfPresent(variables, "env", context.getEnvironment().getName());
            putIfPresent(variables, "namespace", context.getEnvironment().getK8sNamespace());
        }
        if (context != null && context.getParameters() != null) {
            variables.putAll(context.getParameters());
            // parameters 覆盖后再确保 port 别名存在
            if (variables.containsKey("servicePort") && !variables.containsKey("port")) {
                variables.put("port", variables.get("servicePort"));
            }
        }
        if (stepParams != null) {
            putIfPresent(variables, "image", stepParams.get("imageFullName"));
            putIfPresent(variables, "imageFullName", stepParams.get("imageFullName"));
            putIfPresent(variables, "imageTag", stepParams.get("imageTag"));
            putIfPresent(variables, "commitId", stepParams.get("commitId"));
            putIfPresent(variables, "servicePort", stepParams.get("servicePort"));
            putIfPresent(variables, "port", firstNonEmpty(stepParams.get("port"), stepParams.get("servicePort")));
            // Service 模版 type：取自模版管理「服务类型」下拉
            String serviceType = stepParams.get("serviceType");
            if (serviceType == null || serviceType.trim().isEmpty()) {
                serviceType = "ClusterIP";
            } else {
                serviceType = serviceType.trim();
            }
            variables.put("serviceType", serviceType);
        }
        return variables;
    }

    /**
     * 将模版文本中的 ${key} 占位符替换为变量表中的值。
     *
     * @param template 原始模版文本
     * @param variables 变量映射
     * @return 渲染后的文本；模版或变量为空时原样返回
     */
    public static String render(String template, Map<String, String> variables) {
        if (template == null || template.isEmpty() || variables == null || variables.isEmpty()) {
            return template;
        }
        String rendered = template;
        for (Map.Entry<String, String> entry : variables.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) {
                continue;
            }
            rendered = rendered.replace("${" + entry.getKey() + "}", entry.getValue());
        }
        return rendered;
    }

    /**
     * 便捷方法：先构建变量表再渲染模版。
     *
     * @param template 原始模版文本
     * @param context 流水线执行上下文
     * @param stepParams 当前步骤参数
     * @return 渲染后的文本
     */
    public static String render(String template, PipelineExecutionContext context, Map<String, String> stepParams) {
        return render(template, buildVariables(context, stepParams));
    }

    /**
     * 将服务端口转为字符串，无效时默认 8080。
     *
     * @param servicePort 服务端口号
     * @return 端口字符串
     */
    private static String resolveServicePort(Integer servicePort) {
        if (servicePort == null || servicePort <= 0) {
            return "8080";
        }
        return String.valueOf(servicePort);
    }

    /**
     * 返回第一个非空字符串（去空白后）。
     *
     * @param a 候选值 1
     * @param b 候选值 2
     * @return 首个非空值；均为空时返回 null
     */
    private static String firstNonEmpty(String a, String b) {
        if (a != null && !a.trim().isEmpty()) {
            return a.trim();
        }
        if (b != null && !b.trim().isEmpty()) {
            return b.trim();
        }
        return null;
    }

    /**
     * 非空时写入变量表。
     *
     * @param variables 目标映射
     * @param key 变量名
     * @param value 变量值
     */
    private static void putIfPresent(Map<String, String> variables, String key, String value) {
        if (value != null && !value.isEmpty()) {
            variables.put(key, value);
        }
    }
}
