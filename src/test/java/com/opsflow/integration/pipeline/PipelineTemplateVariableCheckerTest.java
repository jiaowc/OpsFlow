package com.opsflow.integration.pipeline;

import com.opsflow.api.dto.TemplateVariableCheckDTO;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PipelineTemplateVariableCheckerTest {

    private static TemplateVariableCheckDTO.Issue issue(TemplateVariableCheckDTO result, String placeholder) {
        return result.getIssues().stream()
                .filter(i -> placeholder.equals(i.getPlaceholder()))
                .findFirst()
                .orElse(null);
    }

    private static Set<String> usedNames(TemplateVariableCheckDTO result) {
        return result.getUsed().stream().map(TemplateVariableCheckDTO.UsedVariable::getName).collect(Collectors.toSet());
    }

    @Test
    void supportedVariablesPass() {
        String yaml = "metadata:\n"
                + "  name: ${serviceName}\n"
                + "  namespace: ${namespace}\n"
                + "spec:\n"
                + "  containers:\n"
                + "  - name: ${serviceName}\n"
                + "    image: ${image}\n"
                + "    ports:\n"
                + "    - containerPort: ${servicePort}\n";

        TemplateVariableCheckDTO result = PipelineTemplateVariableChecker.check("deployment", yaml);

        assertTrue(result.isPassed());
        assertTrue(result.getIssues().isEmpty());
        assertEquals(new java.util.HashSet<>(Arrays.asList("serviceName", "namespace", "image", "servicePort")),
                usedNames(result));
        TemplateVariableCheckDTO.UsedVariable serviceName = result.getUsed().get(0);
        assertEquals("serviceName", serviceName.getName());
        assertEquals(Arrays.asList(2, 6), serviceName.getLines());
    }

    @Test
    void conditionalVariablesCarryTheirCondition() {
        TemplateVariableCheckDTO result = PipelineTemplateVariableChecker.check("deployment", "image: ${image}");

        assertTrue(result.isPassed());
        assertNotNull(result.getUsed().get(0).getCondition());

        // Dockerfile 里的 ${image} 就是本次要构建的镜像，一定有值
        TemplateVariableCheckDTO dockerfile = PipelineTemplateVariableChecker.check("dockerfile", "LABEL image=${image}");
        assertNull(dockerfile.getUsed().get(0).getCondition());
    }

    @Test
    void misspelledNameIsReportedWithSuggestionAndLines() {
        String yaml = "name: ${serviceNmae}\n"
                + "port: ${servicePort}\n"
                + "app: ${serviceNmae}\n";

        TemplateVariableCheckDTO result = PipelineTemplateVariableChecker.check("deployment", yaml);

        assertFalse(result.isPassed());
        assertEquals(1, result.getIssues().size());
        TemplateVariableCheckDTO.Issue issue = issue(result, "${serviceNmae}");
        assertNotNull(issue);
        assertEquals("serviceName", issue.getSuggestion());
        assertEquals(Arrays.asList(1, 3), issue.getLines());
    }

    @Test
    void wrongCaseOrSeparatorSuggestsTheRealName() {
        TemplateVariableCheckDTO result = PipelineTemplateVariableChecker.check("service",
                "a: ${ServiceName}\nb: ${service_port}\nc: ${SERVICE_NAME}\nd: ${service-name}");

        assertEquals("serviceName", issue(result, "${ServiceName}").getSuggestion());
        assertEquals("servicePort", issue(result, "${service_port}").getSuggestion());
        assertEquals("serviceName", issue(result, "${SERVICE_NAME}").getSuggestion());
        assertEquals("serviceName", issue(result, "${service-name}").getSuggestion());
    }

    @Test
    void commonAliasSuggestsThePlatformName() {
        TemplateVariableCheckDTO result = PipelineTemplateVariableChecker.check("deployment",
                "ns: ${ns}\nport: ${containerPort}");

        assertEquals("namespace", issue(result, "${ns}").getSuggestion());
        assertEquals("servicePort", issue(result, "${containerPort}").getSuggestion());
    }

    @Test
    void unknownNameWithoutCloseMatchHasNoSuggestion() {
        TemplateVariableCheckDTO result = PipelineTemplateVariableChecker.check("deployment", "replicas: ${replicas}");

        assertFalse(result.isPassed());
        assertNull(issue(result, "${replicas}").getSuggestion());
    }

    @Test
    void spacesInsidePlaceholderAreReported() {
        TemplateVariableCheckDTO result = PipelineTemplateVariableChecker.check("deployment", "name: ${ serviceName }");

        assertFalse(result.isPassed());
        assertEquals("serviceName", issue(result, "${ serviceName }").getSuggestion());
        assertTrue(result.getUsed().isEmpty());
    }

    @Test
    void otherPlaceholderSyntaxIsReportedForPlatformNames() {
        TemplateVariableCheckDTO result = PipelineTemplateVariableChecker.check("deployment",
                "a: {{serviceName}}\nb: $(namespace)\nc: {image}\nd: $servicePort\ne: $(POD_NAME)");

        assertEquals("serviceName", issue(result, "{{serviceName}}").getSuggestion());
        assertEquals("namespace", issue(result, "$(namespace)").getSuggestion());
        assertEquals("image", issue(result, "{image}").getSuggestion());
        assertEquals("servicePort", issue(result, "$servicePort").getSuggestion());
        // K8s 自己的 $(ENV) 引用不是平台变量，不报
        assertEquals(4, result.getIssues().size());
    }

    @Test
    void unclosedPlaceholderIsReported() {
        TemplateVariableCheckDTO result = PipelineTemplateVariableChecker.check("deployment", "name: ${serviceName");

        assertFalse(result.isPassed());
        assertNotNull(issue(result, "${serviceName"));
    }

    @Test
    void defaultValueSyntaxOnPlatformNameIsReported() {
        TemplateVariableCheckDTO result = PipelineTemplateVariableChecker.check("deployment", "port: ${port:-8080}");

        assertFalse(result.isPassed());
        assertEquals("port", issue(result, "${port:-8080}").getSuggestion());
    }

    @Test
    void shellAndEnvironmentVariablesAreLeftAlone() {
        TemplateVariableCheckDTO result = PipelineTemplateVariableChecker.check("deployment",
                "command: [\"sh\", \"-c\", \"java ${JAVA_OPTS} ${EXTRA:-} -jar app.jar ${1}\"]");

        assertTrue(result.isPassed());
        assertEquals(Arrays.asList("${JAVA_OPTS}", "${EXTRA:-}", "${1}"), result.getPassthrough());
    }

    @Test
    void dockerfileArgAndEnvDeclarationsAreLeftAlone() {
        String dockerfile = "FROM openjdk:8-jre-slim\n"
                + "ARG appVersion=1.0\n"
                + "ENV appHome=/app profile=prod\n"
                + "ENV logDir /data/logs\n"
                + "WORKDIR ${appHome}\n"
                + "LABEL version=${appVersion} profile=${profile} logs=${logDir}\n"
                + "EXPOSE ${servicePort}\n";

        TemplateVariableCheckDTO result = PipelineTemplateVariableChecker.check("dockerfile", dockerfile);

        assertTrue(result.isPassed(), () -> "unexpected issues: " + result.getIssues());
        assertEquals(java.util.Collections.singleton("servicePort"), usedNames(result));
        assertEquals(4, result.getPassthrough().size());
    }

    @Test
    void sameNamesAreNotExcusedOutsideDockerfile() {
        TemplateVariableCheckDTO result = PipelineTemplateVariableChecker.check("deployment",
                "ARG appVersion=1.0\nversion: ${appVersion}");

        assertFalse(result.isPassed());
    }

    @Test
    void buildCommandOnlyVariablesAreExplainedInDockerfile() {
        TemplateVariableCheckDTO result = PipelineTemplateVariableChecker.check("dockerfile",
                "LABEL tag=${tag}\nLABEL registry=${harbor}\nFROM ${baseImage}");

        assertEquals(3, result.getIssues().size());
        assertEquals("imageTag", issue(result, "${tag}").getSuggestion());
        assertTrue(issue(result, "${harbor}").getMessage().contains("Docker 构建命令"));
        assertTrue(issue(result, "${baseImage}").getMessage().contains("基础镜像"));
    }

    @Test
    void emptyContentPasses() {
        assertTrue(PipelineTemplateVariableChecker.check("deployment", null).isPassed());
        assertTrue(PipelineTemplateVariableChecker.check("deployment", "").isPassed());
    }

    @Test
    void windowsLineEndingsKeepLineNumbers() {
        TemplateVariableCheckDTO result = PipelineTemplateVariableChecker.check("deployment",
                "a: ${serviceName}\r\nb: ${nope}\r\n");

        assertEquals(Arrays.asList(2), issue(result, "${nope}").getLines());
    }

    /**
     * 渲染器实际产出的变量都必须在清单里，否则检查会把能用的变量报成不能用。
     */
    @Test
    void everyVariableTheRendererProducesIsSupported() {
        PipelineExecutionContext context = new PipelineExecutionContext();
        PipelineExecutionContext.ServiceInfo service = new PipelineExecutionContext.ServiceInfo();
        service.setName("订单服务");
        service.setCode("order-service");
        service.setK8sDeployment("order-service");
        service.setBranch("main");
        service.setServicePort(8081);
        context.setService(service);
        PipelineExecutionContext.EnvironmentInfo env = new PipelineExecutionContext.EnvironmentInfo();
        env.setName("test");
        env.setK8sNamespace("test-ns");
        context.setEnvironment(env);

        Map<String, String> stepParams = new HashMap<>();
        stepParams.put("imageFullName", "harbor.local/test/order-service:1.0");
        stepParams.put("imageTag", "1.0");
        stepParams.put("commitId", "abc123");
        stepParams.put("servicePort", "8081");
        stepParams.put("port", "8081");
        stepParams.put("serviceType", "NodePort");

        Set<String> produced = PipelineTemplateRenderer.buildVariables(context, stepParams).keySet();

        for (String type : Arrays.asList("dockerfile", "deployment", "service")) {
            Set<String> supported = PipelineTemplateVariableChecker.supportedVariables(type).stream()
                    .map(TemplateVariableCheckDTO.Variable::getName)
                    .collect(Collectors.toSet());
            for (String name : produced) {
                assertTrue(supported.contains(name), type + " 变量清单缺少渲染器产出的变量: " + name);
            }
        }
    }

    /**
     * 页面上「添加模版」预填的默认内容必须能通过检查。
     */
    @Test
    void builtInDefaultTemplatesPass() throws Exception {
        String js = readResource("/static/js/modules/template.js");
        Map<String, String> defaults = new HashMap<>();
        defaults.put("dockerfile", "DEFAULT_DOCKERFILE_CONTENT");
        defaults.put("deployment", "DEFAULT_DEPLOYMENT_CONTENT");
        defaults.put("service", "DEFAULT_SERVICE_CONTENT");

        for (Map.Entry<String, String> entry : defaults.entrySet()) {
            Matcher matcher = Pattern.compile("const " + entry.getValue() + " = `([^`]*)`").matcher(js);
            assertTrue(matcher.find(), "template.js 中未找到 " + entry.getValue());
            String content = matcher.group(1).replace("\\$", "$");

            TemplateVariableCheckDTO result = PipelineTemplateVariableChecker.check(entry.getKey(), content);

            List<TemplateVariableCheckDTO.Issue> issues = result.getIssues();
            assertTrue(issues.isEmpty(), entry.getValue() + " 存在变量问题: " + issues);
            assertFalse(result.getUsed().isEmpty(), entry.getValue() + " 应至少用到一个平台变量");
        }
    }

    private static String readResource(String path) throws Exception {
        try (InputStream in = PipelineTemplateVariableCheckerTest.class.getResourceAsStream(path)) {
            assertNotNull(in, "找不到资源 " + path);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            return new String(out.toByteArray(), "UTF-8");
        }
    }
}
