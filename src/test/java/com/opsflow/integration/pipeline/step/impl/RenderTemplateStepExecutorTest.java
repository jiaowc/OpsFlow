package com.opsflow.integration.pipeline.step.impl;

import com.opsflow.integration.pipeline.NodeCommandHelper;
import com.opsflow.integration.pipeline.PipelineExecutionContext;
import com.opsflow.integration.pipeline.step.StepExecutionResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.opsflow.integration.pipeline.step.impl.StepTestSupport.WORKSPACE;
import static com.opsflow.integration.pipeline.step.impl.StepTestSupport.failed;
import static com.opsflow.integration.pipeline.step.impl.StepTestSupport.ok;
import static com.opsflow.integration.pipeline.step.impl.StepTestSupport.params;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 模版渲染步骤：把 Deployment/Service 模版里的变量替换掉，写到工作目录的 manifests 下供部署步骤使用。
 */
class RenderTemplateStepExecutorTest {

    private static final String IMAGE = "harbor.local/dev/ruoyi-vue:v1";
    private static final String DEPLOYMENT_TEMPLATE =
            "kind: Deployment\nmetadata:\n  name: ${deployment}\n  namespace: ${namespace}\n"
                    + "spec:\n  image: ${image}\n  port: ${port}\n";
    private static final String SERVICE_TEMPLATE =
            "kind: Service\nmetadata:\n  name: ${serviceName}\nspec:\n  type: ${serviceType}\n  port: ${servicePort}\n";
    private static final String RENDERED_DEPLOYMENT =
            "kind: Deployment\nmetadata:\n  name: ruoyi-vue\n  namespace: dev\n"
                    + "spec:\n  image: " + IMAGE + "\n  port: 8080\n";

    @TempDir
    Path tempDir;

    private NodeCommandHelper nodeCommandHelper;
    private RenderTemplateStepExecutor executor;
    private PipelineExecutionContext context;
    private List<String> commands;
    private Map<Integer, NodeCommandHelper.CommandResult> results;

    @BeforeEach
    void setUp() {
        nodeCommandHelper = mock(NodeCommandHelper.class);
        executor = new RenderTemplateStepExecutor();
        ReflectionTestUtils.setField(executor, "nodeCommandHelper", nodeCommandHelper);
        context = StepTestSupport.context();
        commands = new ArrayList<>();
        results = new HashMap<>();

        when(nodeCommandHelper.describeBuildNode(any())).thenReturn("build-1");
        // 默认没有构建节点（Mockito 对 Long 返回值默认给 0，这里要显式给 null）
        when(nodeCommandHelper.resolveBuildNodeId(any())).thenReturn(null);
        when(nodeCommandHelper.runOnBuildNode(any(), anyString(), eq(WORKSPACE))).thenAnswer(invocation -> {
            commands.add(invocation.getArgument(1));
            NodeCommandHelper.CommandResult configured = results.get(commands.size() - 1);
            return configured != null ? configured : ok("");
        });
    }

    private void useBuildNode() {
        when(nodeCommandHelper.resolveBuildNodeId(any())).thenReturn(3L);
    }

    // ---------- 构建节点 ----------

    @Test
    void onBuildNodeWritesBothManifestsUnderManifestsDir() {
        useBuildNode();
        StepExecutionResult result = executor.execute("render_template", params(
                "deploymentTemplateContent", DEPLOYMENT_TEMPLATE,
                "serviceTemplateContent", SERVICE_TEMPLATE,
                "imageFullName", IMAGE), context);

        assertTrue(result.getSuccess());
        assertEquals(3, commands.size());
        assertEquals("mkdir -p \"$HOME/opsflow/ruoyi-vue/dev/master/manifests\"", commands.get(0));
        assertEquals("cat > \"$HOME/opsflow/ruoyi-vue/dev/master/manifests/deployment.yaml\" <<'__OPSFLOW_EOF__'\n"
                + RENDERED_DEPLOYMENT + "\n__OPSFLOW_EOF__", commands.get(1));
        assertTrue(commands.get(2).startsWith(
                "cat > \"$HOME/opsflow/ruoyi-vue/dev/master/manifests/service.yaml\" <<'__OPSFLOW_EOF__'\n"));
        assertTrue(commands.get(2).contains("  name: ruoyi-vue\nspec:\n  type: ClusterIP\n  port: 8080\n"));

        assertEquals(WORKSPACE + "/manifests", result.getOutputData().get("manifestDir"));
        assertEquals(WORKSPACE + "/manifests/deployment.yaml," + WORKSPACE + "/manifests/service.yaml",
                result.getOutputData().get("renderedManifests"));
        assertTrue(result.getLog().contains("----- 渲染结果: deployment.yaml -----\n" + RENDERED_DEPLOYMENT
                + "----- END: deployment.yaml -----"), "渲染结果要进日志，方便核对实际下发的内容");
    }

    @Test
    void onlyConfiguredTemplateIsWritten() {
        useBuildNode();
        StepExecutionResult result = executor.execute("render_template", params(
                "serviceTemplateContent", SERVICE_TEMPLATE,
                "serviceType", " NodePort ", "servicePort", "9090", "outputDir", "k8s"), context);

        assertTrue(result.getSuccess());
        assertEquals(2, commands.size());
        assertEquals("mkdir -p \"$HOME/opsflow/ruoyi-vue/dev/master/k8s\"", commands.get(0));
        assertTrue(commands.get(1).contains("  type: NodePort\n  port: 9090\n"));
        assertEquals(WORKSPACE + "/k8s/service.yaml", result.getOutputData().get("renderedManifests"));
    }

    @Test
    void unknownPlaceholdersAreKeptAsIs() {
        useBuildNode();
        executor.execute("render_template",
                params("deploymentTemplateContent", "env: ${JAVA_OPTS}\nimage: ${image}\n"), context);
        // 没有 imageFullName 时 ${image} 不会被替换成空串，留给检查步骤或 kubectl 报错
        assertTrue(commands.get(1).contains("env: ${JAVA_OPTS}\nimage: ${image}\n"));
    }

    @Test
    void mkdirFailureStopsBeforeWriting() {
        useBuildNode();
        results.put(0, failed(1, null, null));
        StepExecutionResult result = executor.execute("render_template",
                params("deploymentTemplateContent", DEPLOYMENT_TEMPLATE), context);

        assertFalse(result.getSuccess());
        assertEquals("无法在构建节点创建输出目录: " + WORKSPACE + "/manifests", result.getErrorMessage());
        assertEquals(1, commands.size());
    }

    @Test
    void writeFailureIsReportedPerFile() {
        useBuildNode();
        results.put(1, failed(1, null, null));
        assertEquals("写入 deployment.yaml 失败", executor.execute("render_template",
                params("deploymentTemplateContent", DEPLOYMENT_TEMPLATE, "serviceTemplateContent", SERVICE_TEMPLATE),
                context).getErrorMessage());
        assertEquals(2, commands.size(), "deployment 写失败后不再写 service");

        commands.clear();
        results.clear();
        results.put(2, failed(1, null, "磁盘已满"));
        StepExecutionResult result = executor.execute("render_template",
                params("deploymentTemplateContent", DEPLOYMENT_TEMPLATE, "serviceTemplateContent", SERVICE_TEMPLATE),
                context);
        assertFalse(result.getSuccess());
        assertEquals("磁盘已满", result.getErrorMessage());
        assertTrue(result.getOutputData().isEmpty());
    }

    // ---------- 本机执行 ----------

    @Test
    void withoutBuildNodeWritesLocalFiles() throws Exception {
        Path workspace = tempDir.resolve("ws");
        context.setWorkspace(workspace.toString());

        StepExecutionResult result = executor.execute("render_template", params(
                "deploymentTemplateContent", DEPLOYMENT_TEMPLATE,
                "serviceTemplateContent", SERVICE_TEMPLATE,
                "imageFullName", IMAGE), context);

        assertTrue(result.getSuccess(), result.getErrorMessage());
        Path manifests = workspace.resolve("manifests");
        assertEquals(RENDERED_DEPLOYMENT,
                new String(Files.readAllBytes(manifests.resolve("deployment.yaml")), StandardCharsets.UTF_8));
        assertTrue(Files.isRegularFile(manifests.resolve("service.yaml")));
        assertEquals(manifests.toFile().getAbsolutePath(), result.getOutputData().get("manifestDir"));
        assertEquals(manifests.resolve("deployment.yaml").toFile().getAbsolutePath() + ","
                + manifests.resolve("service.yaml").toFile().getAbsolutePath(),
                result.getOutputData().get("renderedManifests"));
        assertTrue(commands.isEmpty());
    }

    // ---------- 参数校验 ----------

    @Test
    void atLeastOneTemplateIsRequired() {
        StepExecutionResult result = executor.execute("render_template",
                params("deploymentTemplateContent", "  ", "serviceTemplateContent", ""), context);
        assertFalse(result.getSuccess());
        assertEquals("请配置 Deployment 或 Service 模版内容", result.getErrorMessage());
    }

    @Test
    void workspaceIsRequired() {
        context.setWorkspace("  ");
        StepExecutionResult result = executor.execute("render_template",
                params("deploymentTemplateContent", DEPLOYMENT_TEMPLATE), context);
        assertFalse(result.getSuccess());
        assertEquals("工作目录未配置", result.getErrorMessage());
    }

    @Test
    void supportsOnlyRenderTemplate() {
        assertTrue(executor.supports("render_template"));
        assertTrue(executor.supports("RENDER_TEMPLATE"));
        assertFalse(executor.supports("deploy"));
        assertFalse(executor.supports(null));
    }
}
