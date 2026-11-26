package com.opsflow.integration.pipeline;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 模版变量的收集与 ${key} 渲染。
 */
class PipelineTemplateRendererTest {

    private static PipelineExecutionContext context() {
        PipelineExecutionContext context = new PipelineExecutionContext();
        PipelineExecutionContext.ServiceInfo service = new PipelineExecutionContext.ServiceInfo();
        service.setName("订单服务");
        service.setCode("order");
        service.setK8sDeployment("order-deploy");
        service.setBranch("release/1.2");
        service.setServicePort(9000);
        context.setService(service);
        PipelineExecutionContext.EnvironmentInfo environment = new PipelineExecutionContext.EnvironmentInfo();
        environment.setName("prod");
        environment.setK8sNamespace("prod-ns");
        context.setEnvironment(environment);
        context.setParameters(new HashMap<>());
        return context;
    }

    private static Map<String, String> params(String... kv) {
        Map<String, String> map = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            map.put(kv[i], kv[i + 1]);
        }
        return map;
    }

    @Test
    void collectsServiceAndEnvironmentVariables() {
        Map<String, String> vars = PipelineTemplateRenderer.buildVariables(context(), null);
        assertEquals("订单服务", vars.get("service"));
        assertEquals("order", vars.get("serviceName"));
        assertEquals("order", vars.get("serviceCode"));
        assertEquals("order-deploy", vars.get("deployment"));
        assertEquals("release/1.2", vars.get("branch"));
        assertEquals("9000", vars.get("servicePort"));
        assertEquals("9000", vars.get("port"));
        assertEquals("prod", vars.get("env"));
        assertEquals("prod-ns", vars.get("namespace"));
    }

    @Test
    void portDefaultsTo8080WhenUnsetOrInvalid() {
        PipelineExecutionContext context = context();
        context.getService().setServicePort(null);
        assertEquals("8080", PipelineTemplateRenderer.buildVariables(context, null).get("servicePort"));
        context.getService().setServicePort(0);
        assertEquals("8080", PipelineTemplateRenderer.buildVariables(context, null).get("port"));
    }

    @Test
    void stepParametersProvideImageAndOverridePort() {
        Map<String, String> vars = PipelineTemplateRenderer.buildVariables(context(),
                params("imageFullName", "harbor/prod/order:1.2.0", "imageTag", "1.2.0", "commitId", "abc123",
                        "servicePort", "7001", "serviceType", " NodePort "));
        assertEquals("harbor/prod/order:1.2.0", vars.get("image"));
        assertEquals("harbor/prod/order:1.2.0", vars.get("imageFullName"));
        assertEquals("1.2.0", vars.get("imageTag"));
        assertEquals("abc123", vars.get("commitId"));
        assertEquals("7001", vars.get("servicePort"));
        assertEquals("7001", vars.get("port"), "port 是 servicePort 的别名，要一起被覆盖");
        assertEquals("NodePort", vars.get("serviceType"));
    }

    @Test
    void serviceTypeDefaultsToClusterIpOnlyWhenStepParamsExist() {
        assertEquals("ClusterIP", PipelineTemplateRenderer.buildVariables(context(), params()).get("serviceType"));
        assertEquals("ClusterIP",
                PipelineTemplateRenderer.buildVariables(context(), params("serviceType", "  ")).get("serviceType"));
        assertFalse(PipelineTemplateRenderer.buildVariables(context(), null).containsKey("serviceType"));
    }

    @Test
    void pipelineParametersOverrideContextValues() {
        PipelineExecutionContext context = context();
        context.getParameters().put("namespace", "override-ns");
        context.getParameters().put("customKey", "customValue");
        Map<String, String> vars = PipelineTemplateRenderer.buildVariables(context, null);
        assertEquals("override-ns", vars.get("namespace"));
        assertEquals("customValue", vars.get("customKey"));
    }

    @Test
    void rendersKnownPlaceholdersAndLeavesTheRestAlone() {
        String template = "name: ${serviceName}\nns: ${namespace}\nopts: ${JAVA_OPTS}\nraw: $serviceName {serviceName} ${ serviceName }";
        String rendered = PipelineTemplateRenderer.render(template, context(), null);
        assertEquals("name: order\nns: prod-ns\nopts: ${JAVA_OPTS}\nraw: $serviceName {serviceName} ${ serviceName }", rendered);
    }

    @Test
    void replacementValuesAreInsertedLiterally() {
        // 值里的 $ 和反斜杠不能被当成正则的反向引用
        Map<String, String> vars = new HashMap<>();
        vars.put("image", "harbor/a$1\\b:1.0");
        assertEquals("image: harbor/a$1\\b:1.0", PipelineTemplateRenderer.render("image: ${image}", vars));
    }

    @Test
    void nullAndEmptyInputsAreReturnedAsIs() {
        Map<String, String> vars = params("a", "1");
        assertNull(PipelineTemplateRenderer.render(null, vars));
        assertEquals("", PipelineTemplateRenderer.render("", vars));
        assertEquals("${a}", PipelineTemplateRenderer.render("${a}", (Map<String, String>) null));
        assertEquals("${a}", PipelineTemplateRenderer.render("${a}", new HashMap<>()));
        Map<String, String> withNull = new HashMap<>();
        withNull.put("a", null);
        withNull.put("b", "2");
        assertEquals("${a}-2", PipelineTemplateRenderer.render("${a}-${b}", withNull), "值为 null 的变量保持原样");
    }

    @Test
    void emptyContextStillWorks() {
        Map<String, String> vars = PipelineTemplateRenderer.buildVariables(new PipelineExecutionContext(), null);
        assertFalse(vars.containsKey("serviceName"));
        assertFalse(vars.containsKey("namespace"));
    }
}
