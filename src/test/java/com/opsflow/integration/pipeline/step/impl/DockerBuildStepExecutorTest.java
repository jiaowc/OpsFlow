package com.opsflow.integration.pipeline.step.impl;

import com.opsflow.integration.harbor.HarborCredentialService;
import com.opsflow.integration.pipeline.NodeCommandHelper;
import com.opsflow.integration.pipeline.PipelineExecutionContext;
import com.opsflow.integration.pipeline.step.StepExecutionResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.opsflow.integration.pipeline.step.impl.StepTestSupport.WORKSPACE;
import static com.opsflow.integration.pipeline.step.impl.StepTestSupport.WORKSPACE_SHELL;
import static com.opsflow.integration.pipeline.step.impl.StepTestSupport.failed;
import static com.opsflow.integration.pipeline.step.impl.StepTestSupport.ok;
import static com.opsflow.integration.pipeline.step.impl.StepTestSupport.params;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * 镜像制作步骤：镜像名和标签怎么定、在构建节点上按什么顺序执行哪些命令、哪一步失败就停在哪。
 */
class DockerBuildStepExecutorTest {

    private static final String DOCKERFILE_SHELL = "\"$HOME/opsflow/ruoyi-vue/dev/master/Dockerfile\"";
    private static final String IMAGE = "harbor.local/dev/ruoyi-vue:v1";

    private NodeCommandHelper nodeCommandHelper;
    private HarborCredentialService harborCredentialService;
    private DockerBuildStepExecutor executor;
    private PipelineExecutionContext context;
    /** 构建节点上依次执行的命令 */
    private List<String> commands;
    /** 第 N 条命令（从 0 开始）的返回结果；没配的都算成功 */
    private Map<Integer, NodeCommandHelper.CommandResult> results;

    @BeforeEach
    void setUp() {
        nodeCommandHelper = mock(NodeCommandHelper.class);
        harborCredentialService = mock(HarborCredentialService.class);
        executor = new DockerBuildStepExecutor();
        ReflectionTestUtils.setField(executor, "nodeCommandHelper", nodeCommandHelper);
        ReflectionTestUtils.setField(executor, "harborCredentialService", harborCredentialService);
        context = StepTestSupport.context();
        commands = new ArrayList<>();
        results = new java.util.HashMap<>();

        when(nodeCommandHelper.describeBuildNode(any())).thenReturn("build-1");
        org.mockito.stubbing.Answer<NodeCommandHelper.CommandResult> nodeAnswer = invocation -> {
            commands.add(invocation.getArgument(1));
            NodeCommandHelper.CommandResult configured = results.get(commands.size() - 1);
            return configured != null ? configured : ok("");
        };
        when(nodeCommandHelper.runOnBuildNode(any(), anyString(), eq(WORKSPACE))).thenAnswer(nodeAnswer);
        // 需要先处理再记录的输出（login 脱敏、镜像信息）走 captureOnBuildNode，不直接写阶段日志
        when(nodeCommandHelper.captureOnBuildNode(any(), anyString(), eq(WORKSPACE))).thenAnswer(nodeAnswer);
        when(harborCredentialService.buildDockerLoginPrefix("harbor.local", null)).thenReturn("LOGIN && ");
    }

    // ---------- 命令与镜像名 ----------

    @Test
    void defaultCommandBuildsImageNamedHarborNamespaceServiceTag() {
        StepExecutionResult result = executor.execute("docker_build", params("tag", "v1"), context);

        assertTrue(result.getSuccess());
        assertEquals(2, commands.size(), "没配模版内容时不写 Dockerfile：只有 build 和查看镜像");
        assertEquals("docker build -f " + DOCKERFILE_SHELL + " -t '" + IMAGE + "' " + WORKSPACE_SHELL, commands.get(0));
        assertTrue(commands.get(1).startsWith("docker images "));
        assertTrue(commands.get(1).contains("'" + IMAGE + "'"));

        Map<String, String> output = result.getOutputData();
        assertEquals(IMAGE, output.get("imageFullName"));
        assertEquals(IMAGE, output.get("image"));
        assertEquals("v1", output.get("imageTag"));
        assertEquals("v1", output.get("tag"));
        assertEquals("harbor.local", output.get("harbor"));
        assertEquals("harbor.local", output.get("harborRegistry"));
        assertEquals("dev", output.get("namespace"));
        assertEquals("ruoyi-vue", output.get("serviceName"));
        assertEquals("Dockerfile", output.get("dockerfilePath"));
        assertTrue(result.getLog().contains("未配置模版内容，使用工作目录已有文件: Dockerfile"));
    }

    @Test
    void dockerfilePathComesFromStepThenServiceThenDefault() {
        context.getService().setDockerfilePath("docker/Dockerfile.prod");
        executor.execute("docker_build", params("tag", "v1"), context);
        assertTrue(commands.get(0).contains("-f \"$HOME/opsflow/ruoyi-vue/dev/master/docker/Dockerfile.prod\""));

        commands.clear();
        executor.execute("docker_build", params("tag", "v1", "dockerfilePath", "ci/Dockerfile"), context);
        assertTrue(commands.get(0).contains("-f \"$HOME/opsflow/ruoyi-vue/dev/master/ci/Dockerfile\""));

        commands.clear();
        StepExecutionResult blank = executor.execute("docker_build", params("tag", "v1", "dockerfilePath", "  "), context);
        assertEquals("Dockerfile", blank.getOutputData().get("dockerfilePath"));
    }

    @Test
    void namespaceFallsBackToEnvNameThenDefault() {
        context.getEnvironment().setK8sNamespace("  ");
        context.getEnvironment().setName("staging");
        assertEquals("harbor.local/staging/ruoyi-vue:v1",
                executor.execute("docker_build", params("tag", "v1"), context).getOutputData().get("image"));

        context.getEnvironment().setName(null);
        assertEquals("harbor.local/default/ruoyi-vue:v1",
                executor.execute("docker_build", params("tag", "v1"), context).getOutputData().get("image"));
    }

    @Test
    void harborFallsBackToComponentAndFailsWhenNotConfigured() {
        context.setEnvironment(null);
        when(harborCredentialService.getRegistryHost(isNull())).thenReturn(" harbor.corp:8443 ");
        assertEquals("harbor.corp:8443/default/ruoyi-vue:v1",
                executor.execute("docker_build", params("tag", "v1"), context).getOutputData().get("image"));

        commands.clear();
        when(harborCredentialService.getRegistryHost(isNull())).thenReturn(null);
        StepExecutionResult result = executor.execute("docker_build", params("tag", "v1"), context);
        assertFalse(result.getSuccess());
        assertEquals("Harbor 地址未配置，请在组件管理中配置 Harbor 组件", result.getErrorMessage());
        assertTrue(commands.isEmpty(), "没有 Harbor 地址时不应在节点上执行任何命令");
    }

    @Test
    void extraArgsAreInsertedOnlyIntoDefaultCommand() {
        executor.execute("docker_build", params("tag", "v1", "dockerBuildArgs", " --no-cache --build-arg ENV=dev "), context);
        assertEquals("docker build --no-cache --build-arg ENV=dev -f " + DOCKERFILE_SHELL
                + " -t '" + IMAGE + "' " + WORKSPACE_SHELL, commands.get(0));

        commands.clear();
        executor.execute("docker_build", params("tag", "v1",
                "dockerBuildArgs", "--no-cache",
                "dockerBuildCommand", "docker buildx build -t ${image} --push ${workspace}"), context);
        assertEquals("docker buildx build -t '" + IMAGE + "' --push " + WORKSPACE_SHELL, commands.get(0));
    }

    @Test
    void customCommandThatAlreadyQuotesTheImageIsLeftAlone() {
        executor.execute("docker_build", params("tag", "v1",
                "dockerBuildCommand", "docker build -t \"${imageFullName}\" -f ${dockerfilePath} ."), context);
        assertEquals("docker build -t \"" + IMAGE + "\" -f " + DOCKERFILE_SHELL + " .", commands.get(0));
    }

    // ---------- 标签 ----------

    @Test
    void tagDefaultsToTimestampAndSupportsFormat() {
        String tag = executor.execute("docker_build", params(), context).getOutputData().get("tag");
        assertTrue(tag.matches("\\d{14}"), tag);

        String formatted = executor.execute("docker_build", params("tagFormat", "release-%s"), context)
                .getOutputData().get("tag");
        assertTrue(formatted.matches("release-\\d{14}"), formatted);

        // 自定义 tag 优先于 tagFormat
        assertEquals("v2", executor.execute("docker_build", params("tag", " v2 ", "tagFormat", "x-%s"), context)
                .getOutputData().get("tag"));
    }

    @Test
    void customTagIsSanitizedForDocker() {
        assertEquals("feature-login-20261117", executor.execute("docker_build",
                params("tag", "feature/login-20261117"), context).getOutputData().get("tag"));
    }

    @Test
    void sanitizeImageTagRules() {
        assertEquals("feature-login", DockerBuildStepExecutor.sanitizeImageTag("feature/login"));
        assertEquals("a-b", DockerBuildStepExecutor.sanitizeImageTag("a//  b"), "连续的非法字符只留一个 -");
        assertEquals("v1.0_rc", DockerBuildStepExecutor.sanitizeImageTag("..-v1.0_rc-."), "不能以 . 或 - 开头结尾");
        assertEquals("x-id", DockerBuildStepExecutor.sanitizeImageTag("x$(id)"));
        assertTrue(DockerBuildStepExecutor.sanitizeImageTag("///").matches("\\d{14}"), "全是非法字符时退回时间戳");

        String longTag = "a".repeat(150) + "-20261117120000";
        String sanitized = DockerBuildStepExecutor.sanitizeImageTag(longTag);
        assertEquals(128, sanitized.length());
        assertTrue(sanitized.endsWith("-20261117120000"), "超长时保留末尾的时间戳");
    }

    // ---------- 写入 Dockerfile ----------

    @Test
    void dockerfileTemplateIsRenderedAndWrittenBeforeBuild() {
        String template = "FROM eclipse-temurin:17-jre\nLABEL image=${image} svc=${serviceName} tag=${imageTag}\nEXPOSE ${port}\nENV JAVA_OPTS=${JAVA_OPTS} T=${tag}\n";
        StepExecutionResult result = executor.execute("docker_build",
                params("tag", "v1", "dockerfileContent", template), context);

        assertTrue(result.getSuccess());
        assertEquals(3, commands.size());
        String write = commands.get(0);
        assertTrue(write.startsWith("cat > " + DOCKERFILE_SHELL + " <<'__OPSFLOW_EOF__'\n"), write);
        assertTrue(write.contains("LABEL image=" + IMAGE + " svc=ruoyi-vue tag=v1\nEXPOSE 8080\n"), write);
        // Dockerfile 里只替换模版变量（image、imageTag、serviceName、port 等）；
        // ${tag}、${harbor}、${workspace} 这些只在 docker build 命令里可用，这里和未知变量一样原样保留
        assertTrue(write.contains("ENV JAVA_OPTS=${JAVA_OPTS} T=${tag}\n"), write);
        assertTrue(write.contains("\n__OPSFLOW_EOF__\necho "), write);
        assertTrue(commands.get(1).startsWith("docker build "));
    }

    @Test
    void dockerfileWriteFailureStopsBeforeBuild() {
        results.put(0, failed(1, "cat: 权限不够\n", null));
        StepExecutionResult result = executor.execute("docker_build",
                params("tag", "v1", "dockerfileContent", "FROM nginx"), context);

        assertFalse(result.getSuccess());
        assertEquals("写入 Dockerfile 失败，退出码: 1", result.getErrorMessage());
        assertEquals(1, commands.size());
        assertTrue(result.getLog().contains("cat: 权限不够"));
    }

    // ---------- 构建与推送 ----------

    @Test
    void buildFailureReportsExitCodeAndSkipsPush() {
        results.put(0, failed(2, "Step 3/5 : RUN mvn package\nERROR\n", null));
        StepExecutionResult result = executor.execute("docker-build", params("tag", "v1"), context);

        assertFalse(result.getSuccess());
        assertEquals("docker build 失败，退出码: 2", result.getErrorMessage());
        assertEquals(1, commands.size());
        assertTrue(result.getLog().contains("Step 3/5 : RUN mvn package"));
        assertTrue(result.getOutputData().isEmpty(), "构建失败不应向后续步骤输出镜像名");
    }

    @Test
    void nodeErrorMessageWinsOverExitCode() {
        results.put(0, failed(-1, null, "SSH 连接超时"));
        assertEquals("SSH 连接超时", executor.execute("docker_build", params("tag", "v1"), context).getErrorMessage());
    }

    @Test
    void pushAfterBuildDependsOnStepTypeUnlessOverridden() {
        // docker_build（流水线里后面另有 push_image 步骤）默认不推送
        executor.execute("docker_build", params("tag", "v1"), context);
        assertEquals(2, commands.size());

        // 旧的合并步骤 docker-build 默认构建后直接推送
        commands.clear();
        StepExecutionResult merged = executor.execute("docker-build", params("tag", "v1"), context);
        assertTrue(merged.getSuccess());
        assertEquals(3, commands.size());
        assertEquals("LOGIN && docker push '" + IMAGE + "'", commands.get(2));

        commands.clear();
        executor.execute("docker-build", params("tag", "v1", "pushAfterBuild", "false"), context);
        assertEquals(2, commands.size());

        commands.clear();
        executor.execute("docker_build", params("tag", "v1", "pushAfterBuild", "true"), context);
        assertEquals(3, commands.size());
    }

    @Test
    void pushFailureFailsTheStep() {
        results.put(2, failed(1, "unauthorized: authentication required\n", null));
        StepExecutionResult result = executor.execute("docker-build", params("tag", "v1"), context);

        assertFalse(result.getSuccess());
        assertEquals("Docker 镜像推送失败，退出码: 1", result.getErrorMessage());
        assertTrue(result.getLog().contains("unauthorized: authentication required"));
    }

    @Test
    void unexpectedExceptionBecomesFailedResult() {
        when(nodeCommandHelper.runOnBuildNode(any(), anyString(), anyString()))
                .thenThrow(new IllegalStateException("构建节点已下线"));
        StepExecutionResult result = executor.execute("docker_build", params("tag", "v1"), context);
        assertFalse(result.getSuccess());
        assertEquals("Docker 镜像构建或推送失败: 构建节点已下线", result.getErrorMessage());
    }

    @Test
    void supportsAllThreeSpellings() {
        assertTrue(executor.supports("docker-build"));
        assertTrue(executor.supports("dockerbuild"));
        assertTrue(executor.supports("Docker_Build"));
        assertFalse(executor.supports("push_image"));
        assertFalse(executor.supports(null));
        verifyNoMoreInteractions(harborCredentialService);
    }

    // ---------- 日志实时落盘 ----------

    @Test
    void streamingModeWritesStepTextToStageLogAndReturnsNoLog() {
        StringBuilder stageLog = StepTestSupport.streamInto(context);
        results.put(0, ok("Step 1/5 : FROM eclipse-temurin:17\nSuccessfully built abc\n"));
        results.put(1, ok("harbor.local/dev/ruoyi-vue:v1  abc  210MB  1 second ago\n"));

        StepExecutionResult result = executor.execute("docker_build", params("tag", "v1"), context);

        assertTrue(result.getSuccess());
        assertNull(result.getLog());
        String text = stageLog.toString();
        assertTrue(text.contains("执行节点: build-1"));
        assertTrue(text.contains("======== [2/2] docker build ========"));
        assertTrue(text.contains("-------- docker build 完成 --------"));
        assertTrue(text.contains("本地镜像:\nharbor.local/dev/ruoyi-vue:v1  abc  210MB  1 second ago\n"));
        assertFalse(text.contains("Successfully built"), "build 的输出由命令执行层逐行落盘，步骤不应再写一遍");
        assertEquals(1, StepTestSupport.count(text, "======== [2/2] docker build ========"));
    }
}
