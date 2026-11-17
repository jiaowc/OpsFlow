package com.opsflow.integration.pipeline.step.impl;

import com.opsflow.integration.harbor.HarborCredentialService;
import com.opsflow.integration.pipeline.NodeCommandHelper;
import com.opsflow.integration.pipeline.PipelineExecutionContext;
import com.opsflow.integration.pipeline.step.StepExecutionResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

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
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 上传镜像步骤：先单独 docker login 再 docker push，登录凭据不能进阶段日志。
 */
class PushImageStepExecutorTest {

    private static final String IMAGE = "harbor.local/dev/ruoyi-vue:v1";
    /** 与 HarborCredentialService.buildDockerLoginPrefix 的返回形态一致 */
    private static final String LOGIN_PREFIX =
            "echo 'S3cret-Value' | docker login -u 'robot' --password-stdin 'harbor.local' && ";

    private NodeCommandHelper nodeCommandHelper;
    private HarborCredentialService harborCredentialService;
    private PushImageStepExecutor executor;
    private PipelineExecutionContext context;
    private List<String> commands;
    private Map<Integer, NodeCommandHelper.CommandResult> results;

    @BeforeEach
    void setUp() {
        nodeCommandHelper = mock(NodeCommandHelper.class);
        harborCredentialService = mock(HarborCredentialService.class);
        executor = new PushImageStepExecutor();
        ReflectionTestUtils.setField(executor, "nodeCommandHelper", nodeCommandHelper);
        ReflectionTestUtils.setField(executor, "harborCredentialService", harborCredentialService);
        context = StepTestSupport.context();
        commands = new ArrayList<>();
        results = new HashMap<>();

        when(nodeCommandHelper.describeBuildNode(any())).thenReturn("build-1");
        when(nodeCommandHelper.runOnBuildNode(any(), anyString(), eq(WORKSPACE))).thenAnswer(invocation -> {
            commands.add(invocation.getArgument(1));
            NodeCommandHelper.CommandResult configured = results.get(commands.size() - 1);
            return configured != null ? configured : ok("");
        });
        when(harborCredentialService.buildDockerLoginPrefix("harbor.local", null)).thenReturn(LOGIN_PREFIX);
    }

    @Test
    void logsInThenPushesAndPassesImageOn() {
        results.put(0, ok("Login Succeeded\ndocker login 成功: harbor.local\n"));
        results.put(1, ok("v1: digest: sha256:abc size: 1234\n"));

        StepExecutionResult result = executor.execute("push_image",
                params("imageFullName", IMAGE, "imageTag", "v1", "namespace", "dev", "serviceName", "ruoyi-vue"), context);

        assertTrue(result.getSuccess());
        assertEquals(2, commands.size());
        assertEquals("echo 'S3cret-Value' | docker login -u 'robot' --password-stdin 'harbor.local'"
                + " && echo 'docker login 成功: harbor.local'", commands.get(0));
        assertEquals("docker push '" + IMAGE + "'", commands.get(1));

        assertEquals(IMAGE, result.getOutputData().get("imageFullName"));
        assertEquals(IMAGE, result.getOutputData().get("image"));
        assertEquals("v1", result.getOutputData().get("imageTag"));
        assertEquals("v1", result.getOutputData().get("tag"));
        assertTrue(result.getLog().contains("已推送镜像: " + IMAGE));
        assertTrue(result.getLog().contains("v1: digest: sha256:abc"));
    }

    @Test
    void passwordNeverAppearsInStageLog() {
        results.put(0, ok("Login Succeeded\n"));
        StepExecutionResult result = executor.execute("push_image", params("imageFullName", IMAGE), context);

        assertFalse(result.getLog().contains("S3cret-Value"), "日志里只能出现打码后的 login 命令");
        assertTrue(result.getLog().contains("$ docker login harbor.local (凭据已注入，密码不落日志)"));
    }

    @Test
    void loginOutputMentioningPasswordIsMasked() {
        results.put(0, ok("WARNING! password=S3cret-Value stored unencrypted\nLogin Succeeded\n"));
        StepExecutionResult result = executor.execute("push_image", params("imageFullName", IMAGE), context);

        assertFalse(result.getLog().contains("S3cret-Value"));
        assertTrue(result.getLog().contains("WARNING! password=*** stored unencrypted"));
    }

    @Test
    void imageAndTagFallBackToAlternativeKeys() {
        StepExecutionResult result = executor.execute("push_image",
                params("imageFullName", "  ", "image", " " + IMAGE + " ", "tag", "v1"), context);

        assertTrue(result.getSuccess());
        assertEquals("docker push '" + IMAGE + "'", commands.get(1));
        assertEquals("v1", result.getOutputData().get("imageTag"));
    }

    @Test
    void missingImageFailsWithoutRunningAnything() {
        StepExecutionResult result = executor.execute("push_image", params("imageTag", "v1"), context);

        assertFalse(result.getSuccess());
        assertEquals("镜像名称不能为空，请先执行镜像制作步骤（需输出 imageFullName）", result.getErrorMessage());
        assertTrue(commands.isEmpty());
    }

    @Test
    void withoutCredentialsPushesDirectlyAndWarns() {
        when(harborCredentialService.buildDockerLoginPrefix("harbor.local", null)).thenReturn("");
        StepExecutionResult result = executor.execute("push_image", params("imageFullName", IMAGE), context);

        assertTrue(result.getSuccess());
        assertEquals(1, commands.size());
        assertEquals("docker push '" + IMAGE + "'", commands.get(0));
        assertTrue(result.getLog().contains("警告: 未配置 Harbor 登录凭据"));
        assertFalse(result.getOutputData().containsKey("imageTag"), "没有标签时不输出空标签");
    }

    @Test
    void imageWithoutRegistryUsesConfiguredHarbor() {
        when(harborCredentialService.getRegistryHost(isNull())).thenReturn("harbor.corp");
        when(harborCredentialService.buildDockerLoginPrefix("harbor.corp", null)).thenReturn("");
        StepExecutionResult result = executor.execute("push_image", params("imageFullName", "nginx:1.27"), context);

        assertTrue(result.getSuccess());
        assertTrue(result.getLog().contains("Harbor 地址: harbor.corp"));
    }

    @Test
    void loginFailureStopsBeforePush() {
        results.put(0, failed(1, "Error response from daemon: unauthorized\n", null));
        StepExecutionResult result = executor.execute("push_image", params("imageFullName", IMAGE), context);

        assertFalse(result.getSuccess());
        assertEquals("docker login 失败，退出码: 1", result.getErrorMessage());
        assertEquals(1, commands.size(), "登录失败后不应再 push");
        assertTrue(result.getLog().contains("Error response from daemon: unauthorized"));

        commands.clear();
        results.put(0, failed(-1, null, "SSH 连接超时"));
        assertEquals("docker login 失败: SSH 连接超时",
                executor.execute("push_image", params("imageFullName", IMAGE), context).getErrorMessage());
    }

    @Test
    void pushFailureReportsExitCode() {
        results.put(1, failed(1, "denied: requested access to the resource is denied\n", null));
        StepExecutionResult result = executor.execute("push_image", params("imageFullName", IMAGE), context);

        assertFalse(result.getSuccess());
        assertEquals("docker push 失败，退出码: 1", result.getErrorMessage());
        assertTrue(result.getLog().contains("denied: requested access"));
        assertTrue(result.getOutputData().isEmpty());
    }

    @Test
    void extraArgsAndCustomCommand() {
        executor.execute("push_image", params("imageFullName", IMAGE, "dockerPushArgs", " --quiet "), context);
        assertEquals("docker push --quiet '" + IMAGE + "'", commands.get(1));

        // 自定义命令优先，额外参数被忽略；${image} 与 ${imageFullName} 都会被替换并加引号
        commands.clear();
        executor.execute("push_image", params("imageFullName", IMAGE,
                "dockerPushArgs", "--quiet",
                "dockerPushCommand", "docker push ${image} && docker rmi ${imageFullName}"), context);
        assertEquals("docker push '" + IMAGE + "' && docker rmi '" + IMAGE + "'", commands.get(1));
    }

    @Test
    void imageNameCannotInjectIntoPushCommand() {
        when(harborCredentialService.buildDockerLoginPrefix(anyString(), isNull())).thenReturn("");
        executor.execute("push_image", params("imageFullName", "harbor.local/dev/app:v1'; rm -rf ~; '"), context);
        assertEquals("docker push 'harbor.local/dev/app:v1'\\''; rm -rf ~; '\\'''", commands.get(0));
    }

    @Test
    void unexpectedExceptionBecomesFailedResult() {
        when(nodeCommandHelper.runOnBuildNode(any(), anyString(), anyString()))
                .thenThrow(new IllegalStateException("构建节点已下线"));
        StepExecutionResult result = executor.execute("push_image", params("imageFullName", IMAGE), context);
        assertFalse(result.getSuccess());
        assertEquals("镜像上传失败: 构建节点已下线", result.getErrorMessage());
    }

    @Test
    void supportsOnlyPushImage() {
        assertTrue(executor.supports("push_image"));
        assertTrue(executor.supports("PUSH_IMAGE"));
        assertFalse(executor.supports("docker-build"));
        assertFalse(executor.supports(null));
    }
}
