package com.opsflow.integration.pipeline.step.impl;

import com.opsflow.integration.k8s.K8sClient;
import com.opsflow.integration.k8s.K8sCredentialService;
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

import static com.opsflow.integration.pipeline.step.impl.StepTestSupport.failed;
import static com.opsflow.integration.pipeline.step.impl.StepTestSupport.ok;
import static com.opsflow.integration.pipeline.step.impl.StepTestSupport.params;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 回滚步骤：有部署节点时在节点上跑 kubectl rollout undo，没有时走内置 K8s 客户端。
 */
class RollbackStepExecutorTest {

    private static final String KUBE_SETUP = "export KUBECONFIG=/tmp/kc-exec-1 && ";

    private K8sClient k8sClient;
    private NodeCommandHelper nodeCommandHelper;
    private K8sCredentialService k8sCredentialService;
    private RollbackStepExecutor executor;
    private PipelineExecutionContext context;
    private List<String> commands;
    private Map<Integer, NodeCommandHelper.CommandResult> results;

    @BeforeEach
    void setUp() {
        k8sClient = mock(K8sClient.class);
        nodeCommandHelper = mock(NodeCommandHelper.class);
        k8sCredentialService = mock(K8sCredentialService.class);
        executor = new RollbackStepExecutor();
        ReflectionTestUtils.setField(executor, "k8sClient", k8sClient);
        ReflectionTestUtils.setField(executor, "nodeCommandHelper", nodeCommandHelper);
        ReflectionTestUtils.setField(executor, "k8sCredentialService", k8sCredentialService);
        context = StepTestSupport.context();
        commands = new ArrayList<>();
        results = new HashMap<>();

        when(nodeCommandHelper.describeDeployNode(any())).thenReturn("deploy-1");
        // 默认没有部署节点（Mockito 对 Long 返回值默认给 0，这里要显式给 null）
        when(nodeCommandHelper.resolveDeployNodeId(any())).thenReturn(null);
        when(nodeCommandHelper.runOnDeployNode(any(), anyString())).thenAnswer(invocation -> {
            commands.add(invocation.getArgument(1));
            NodeCommandHelper.CommandResult configured = results.get(commands.size() - 1);
            return configured != null ? configured : ok("");
        });
        when(k8sCredentialService.buildRemoteKubeconfigSetup("exec-1", 7L, null)).thenReturn(KUBE_SETUP);
    }

    private void useDeployNode() {
        when(nodeCommandHelper.resolveDeployNodeId(any())).thenReturn(5L);
    }

    // ---------- 经部署节点 ----------

    @Test
    void onDeployNodeUndoesThenWaitsForRollout() {
        useDeployNode();
        results.put(0, ok("deployment.apps/ruoyi-vue rolled back\n"));
        results.put(1, ok("deployment \"ruoyi-vue\" successfully rolled out\n"));

        StepExecutionResult result = executor.execute("rollback", params(), context);

        assertTrue(result.getSuccess());
        assertEquals(2, commands.size());
        assertEquals(KUBE_SETUP + "kubectl -n 'dev' rollout undo 'deployment/ruoyi-vue'", commands.get(0));
        assertEquals(KUBE_SETUP + "kubectl -n 'dev' rollout status 'deployment/ruoyi-vue' --timeout=300s", commands.get(1));
        assertTrue(result.getLog().startsWith("执行节点: deploy-1\n"));
        assertTrue(result.getLog().contains("rolled back"));
        assertTrue(result.getLog().contains("successfully rolled out"));
        verifyNoInteractions(k8sClient);
    }

    @Test
    void stepParamsOverrideNamespaceDeploymentRevisionAndTimeout() {
        useDeployNode();
        executor.execute("rollback", params("namespace", "prod", "deployment", "order-svc",
                "toRevision", " 12 ", "timeoutSeconds", " 60 "), context);

        assertEquals(KUBE_SETUP + "kubectl -n 'prod' rollout undo 'deployment/order-svc' --to-revision=12", commands.get(0));
        assertEquals(KUBE_SETUP + "kubectl -n 'prod' rollout status 'deployment/order-svc' --timeout=60s", commands.get(1));
    }

    @Test
    void zeroRevisionMeansPreviousRevision() {
        useDeployNode();
        executor.execute("rollback", params("toRevision", "0", "waitRollout", "false"), context);

        assertEquals(1, commands.size(), "waitRollout=false 时不等待滚动完成");
        assertFalse(commands.get(0).contains("--to-revision"));
    }

    @Test
    void namesAreShellQuoted() {
        useDeployNode();
        executor.execute("rollback", params("namespace", "dev; id", "deployment", "a'b", "waitRollout", "false"), context);
        assertEquals(KUBE_SETUP + "kubectl -n 'dev; id' rollout undo 'deployment/a'\\''b'", commands.get(0));
    }

    @Test
    void undoFailureStopsBeforeWaiting() {
        useDeployNode();
        results.put(0, failed(1, "error: no rollout history found\n", null));
        StepExecutionResult result = executor.execute("rollback", params(), context);

        assertFalse(result.getSuccess());
        assertEquals("回滚失败", result.getErrorMessage());
        assertEquals(1, commands.size());

        commands.clear();
        results.put(0, failed(1, null, "error: no rollout history found"));
        assertEquals("error: no rollout history found", executor.execute("rollback", params(), context).getErrorMessage());
    }

    @Test
    void rolloutCheckFailureFailsTheStep() {
        useDeployNode();
        results.put(1, failed(1, "error: timed out waiting for the condition\n", null));
        StepExecutionResult result = executor.execute("rollback", params(), context);

        assertFalse(result.getSuccess());
        assertEquals("回滚后检查失败", result.getErrorMessage());
    }

    // ---------- 内置客户端 ----------

    @Test
    void withoutDeployNodeUsesBuiltInClient() {
        when(k8sClient.rollbackDeployment("dev", "ruoyi-vue", 3)).thenReturn("已回滚到 revision 3");

        StepExecutionResult result = executor.execute("rollback", params("toRevision", "3", "timeoutSeconds", "120"), context);

        assertTrue(result.getSuccess());
        verify(k8sClient).waitForDeploymentRollout("dev", "ruoyi-vue", 120);
        assertTrue(result.getLog().contains("已回滚到 revision 3"));
        assertTrue(result.getLog().endsWith("Deployment 回滚后滚动更新已完成"));
        assertTrue(commands.isEmpty());
    }

    @Test
    void builtInClientSkipsWaitWhenDisabled() {
        when(k8sClient.rollbackDeployment(anyString(), anyString(), isNull())).thenReturn("ok");
        StepExecutionResult result = executor.execute("rollback", params("waitRollout", "FALSE"), context);

        assertTrue(result.getSuccess());
        verify(k8sClient).rollbackDeployment("dev", "ruoyi-vue", null);
        verify(k8sClient, never()).waitForDeploymentRollout(anyString(), anyString(), anyInt());
    }

    @Test
    void builtInClientErrorBecomesFailedResult() {
        when(k8sClient.rollbackDeployment(anyString(), anyString(), isNull())).thenReturn("ok");
        doThrow(new IllegalStateException("等待滚动更新超时"))
                .when(k8sClient).waitForDeploymentRollout("dev", "ruoyi-vue", 300);

        StepExecutionResult result = executor.execute("rollback", params(), context);
        assertFalse(result.getSuccess());
        assertEquals("回滚失败: 等待滚动更新超时", result.getErrorMessage());
    }

    // ---------- 参数校验 ----------

    @Test
    void namespaceAndDeploymentAreRequired() {
        context.getEnvironment().setK8sNamespace("");
        assertEquals("K8s 命名空间不能为空", executor.execute("rollback", params(), context).getErrorMessage());

        context.getEnvironment().setK8sNamespace("dev");
        context.getService().setK8sDeployment(null);
        StepExecutionResult result = executor.execute("rollback", params(), context);
        assertFalse(result.getSuccess());
        assertEquals("K8s Deployment 名称不能为空", result.getErrorMessage());
        verifyNoInteractions(k8sClient);
        assertTrue(commands.isEmpty());
    }

    @Test
    void nonNumericRevisionOrTimeoutIsAParameterError() {
        StepExecutionResult revision = executor.execute("rollback", params("toRevision", "latest"), context);
        assertFalse(revision.getSuccess());
        assertTrue(revision.getErrorMessage().startsWith("步骤参数格式错误: "), revision.getErrorMessage());

        StepExecutionResult timeout = executor.execute("rollback", params("timeoutSeconds", "5m"), context);
        assertTrue(timeout.getErrorMessage().startsWith("步骤参数格式错误: "), timeout.getErrorMessage());
        verifyNoInteractions(k8sClient);
    }

    @Test
    void supportsOnlyRollback() {
        assertTrue(executor.supports("rollback"));
        assertTrue(executor.supports("ROLLBACK"));
        assertFalse(executor.supports("deploy"));
        assertFalse(executor.supports(null));
    }
}
