package com.opsflow.integration.pipeline.step.impl;

import com.opsflow.integration.pipeline.NodeCommandHelper;
import com.opsflow.integration.pipeline.PipelineExecutionContext;
import com.opsflow.integration.pipeline.step.StepExecutionResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import static com.opsflow.integration.pipeline.step.impl.StepTestSupport.failed;
import static com.opsflow.integration.pipeline.step.impl.StepTestSupport.ok;
import static com.opsflow.integration.pipeline.step.impl.StepTestSupport.params;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CleanWorkspaceStepExecutorTest {

    private NodeCommandHelper nodeCommandHelper;
    private CleanWorkspaceStepExecutor executor;
    private PipelineExecutionContext context;

    @BeforeEach
    void setUp() {
        nodeCommandHelper = mock(NodeCommandHelper.class);
        executor = new CleanWorkspaceStepExecutor();
        ReflectionTestUtils.setField(executor, "nodeCommandHelper", nodeCommandHelper);
        context = StepTestSupport.context();
        context.setWorkspace(null);
        when(nodeCommandHelper.describeBuildNode(any())).thenReturn("build-1");
        when(nodeCommandHelper.runOnBuildNode(any(), anyString())).thenReturn(ok("done\n"));
    }

    private String executedCommand() {
        ArgumentCaptor<String> command = ArgumentCaptor.forClass(String.class);
        verify(nodeCommandHelper).runOnBuildNode(any(), command.capture());
        return command.getValue();
    }

    @Test
    void recreatesResolvedWorkspaceAndPublishesIt() {
        StepExecutionResult result = executor.execute("clean_workspace", params(), context);

        assertTrue(result.getSuccess());
        assertEquals("rm -rf " + StepTestSupport.WORKSPACE_SHELL + " && mkdir -p " + StepTestSupport.WORKSPACE_SHELL,
                executedCommand());
        // 后续步骤都从 context 取工作目录
        assertEquals(StepTestSupport.WORKSPACE, context.getWorkspace());
        assertEquals(StepTestSupport.WORKSPACE, result.getOutputData().get("workspace"));
        assertTrue(result.getLog().contains("执行节点: build-1"));
        assertTrue(result.getLog().contains("清理 Docker 缓存: 否"));
    }

    @Test
    void cleanDockerAppendsPruneThatNeverFailsTheStep() {
        executor.execute("clean_workspace", params("cleanDocker", "TRUE"), context);
        assertTrue(executedCommand().endsWith(" && docker system prune -f 2>/dev/null || true"));
    }

    @Test
    void explicitWorkspacePathIsQuotedAsAWhole() {
        executor.execute("clean_workspace", params("workspacePath", "/data/my ws/svc"), context);
        assertEquals("rm -rf '/data/my ws/svc' && mkdir -p '/data/my ws/svc'", executedCommand());
        assertEquals("/data/my ws/svc", context.getWorkspace());
    }

    @Test
    void branchNameCannotEscapeTheWorkspaceInRmCommand() {
        // 分支名来自 Webhook；rm -rf 的目标必须仍在工作目录下
        context.getService().setBranch("x/../../..; rm -rf /");
        executor.execute("clean_workspace", params(), context);

        // 斜杠、分号、空格都被替换成下划线：只剩一级目录名，仍在 ~/opsflow/ruoyi-vue/dev/ 下
        String dir = "\"$HOME/opsflow/ruoyi-vue/dev/x_.._.._..__rm_-rf__\"";
        assertEquals("rm -rf " + dir + " && mkdir -p " + dir, executedCommand());
    }

    @Test
    void commandFailureIsReportedWithNodeMessageOrDefault() {
        when(nodeCommandHelper.runOnBuildNode(any(), anyString())).thenReturn(failed(1, "rm: 权限不够\n", "SSH 执行失败"));
        StepExecutionResult result = executor.execute("clean_workspace", params(), context);
        assertFalse(result.getSuccess());
        assertEquals("SSH 执行失败", result.getErrorMessage());
        assertTrue(result.getLog().contains("rm: 权限不够"));
        assertFalse(result.getOutputData().containsKey("workspace"));

        when(nodeCommandHelper.runOnBuildNode(any(), anyString())).thenReturn(failed(1, null, null));
        assertEquals("清理工作目录失败", executor.execute("clean_workspace", params(), context).getErrorMessage());
    }

    @Test
    void unexpectedExceptionBecomesFailedResult() {
        when(nodeCommandHelper.runOnBuildNode(any(), anyString())).thenThrow(new IllegalStateException("节点不存在"));
        StepExecutionResult result = executor.execute("clean_workspace", params(), context);
        assertFalse(result.getSuccess());
        assertEquals("清理工作目录失败: 节点不存在", result.getErrorMessage());
    }

    @Test
    void supportsOnlyCleanWorkspace() {
        assertTrue(executor.supports("clean_workspace"));
        assertTrue(executor.supports("CLEAN_WORKSPACE"));
        assertFalse(executor.supports("clean"));
        assertFalse(executor.supports(null));
    }
}
