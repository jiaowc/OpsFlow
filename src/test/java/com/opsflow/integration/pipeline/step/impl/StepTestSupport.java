package com.opsflow.integration.pipeline.step.impl;

import com.opsflow.integration.pipeline.NodeCommandHelper;
import com.opsflow.integration.pipeline.PipelineExecutionContext;

import java.util.HashMap;
import java.util.Map;

/**
 * 步骤执行器测试共用的小工具：构造执行上下文、步骤参数和节点命令结果。
 */
final class StepTestSupport {

    static final String WORKSPACE = "~/opsflow/ruoyi-vue/dev/master";
    static final String WORKSPACE_SHELL = "\"$HOME/opsflow/ruoyi-vue/dev/master\"";

    private StepTestSupport() {
    }

    /** 服务 ruoyi-vue（Deployment 同名）、环境 dev（命名空间 dev、Harbor harbor.local），工作目录已解析 */
    static PipelineExecutionContext context() {
        PipelineExecutionContext context = new PipelineExecutionContext();
        context.setExecutionId("exec-1");
        context.setWorkspace(WORKSPACE);

        PipelineExecutionContext.ServiceInfo service = new PipelineExecutionContext.ServiceInfo();
        service.setName("若依后端");
        service.setCode("ruoyi-vue");
        service.setBranch("master");
        service.setK8sDeployment("ruoyi-vue");
        context.setService(service);

        PipelineExecutionContext.EnvironmentInfo environment = new PipelineExecutionContext.EnvironmentInfo();
        environment.setName("dev");
        environment.setClusterId(7L);
        environment.setK8sNamespace("dev");
        environment.setHarborRegistry("harbor.local");
        context.setEnvironment(environment);

        context.setParameters(new HashMap<>());
        return context;
    }

    static Map<String, String> params(String... kv) {
        Map<String, String> map = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            map.put(kv[i], kv[i + 1]);
        }
        return map;
    }

    static NodeCommandHelper.CommandResult ok(String output) {
        NodeCommandHelper.CommandResult result = new NodeCommandHelper.CommandResult();
        result.setSuccess(true);
        result.setExitCode(0);
        result.setOutput(output);
        return result;
    }

    /** 命令失败；errorMessage 为空时执行器应回退到自己的默认提示 */
    static NodeCommandHelper.CommandResult failed(int exitCode, String output, String errorMessage) {
        NodeCommandHelper.CommandResult result = new NodeCommandHelper.CommandResult();
        result.setSuccess(false);
        result.setExitCode(exitCode);
        result.setOutput(output);
        result.setErrorMessage(errorMessage);
        return result;
    }
}
