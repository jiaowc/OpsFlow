package com.opsflow.integration.pipeline;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 工作目录的解析与转成 shell 路径（WorkspacePathHelper、PipelineWorkspaceResolver）。
 */
class WorkspacePathTest {

    private static PipelineExecutionContext context(String serviceCode, String branch, String env) {
        PipelineExecutionContext context = new PipelineExecutionContext();
        PipelineExecutionContext.ServiceInfo service = new PipelineExecutionContext.ServiceInfo();
        service.setCode(serviceCode);
        service.setBranch(branch);
        context.setService(service);
        PipelineExecutionContext.EnvironmentInfo environment = new PipelineExecutionContext.EnvironmentInfo();
        environment.setName(env);
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

    // ---------- 工作目录解析 ----------

    @Test
    void workspaceIsBaseServiceEnvBranch() {
        assertEquals("~/opsflow/ruoyi-vue/dev/master",
                PipelineWorkspaceResolver.resolve(context("ruoyi-vue", "master", "dev"), null));
    }

    @Test
    void pathSegmentsAreSanitized() {
        // 分支名来自外部（Webhook），不能让它跳出工作目录或带进 shell 特殊字符
        assertEquals("~/opsflow/order_svc/prod_cn/feature_x_.._.._etc",
                PipelineWorkspaceResolver.resolve(context("order svc", "feature/x/../../etc", "prod cn"), null));
        assertEquals("~/opsflow/svc/dev/a__rm_-rf___b",
                PipelineWorkspaceResolver.resolve(context("svc", "a; rm -rf ~;b", "dev"), null));
    }

    @Test
    void missingPartsUseDefaults() {
        PipelineExecutionContext empty = new PipelineExecutionContext();
        assertEquals("~/opsflow/service/env/default", PipelineWorkspaceResolver.resolve(empty, null));
    }

    @Test
    void basePrecedenceIsStepThenPipelineThenDefault() {
        PipelineExecutionContext context = context("svc", "main", "dev");
        context.getParameters().put("workspaceBase", " /data/pipeline ");
        assertEquals("/data/pipeline/svc/dev/main", PipelineWorkspaceResolver.resolve(context, params()));
        assertEquals("/mnt/node/svc/dev/main",
                PipelineWorkspaceResolver.resolve(context, params("workspaceBase", " /mnt/node ")));
        assertEquals("/data/pipeline/svc/dev/main",
                PipelineWorkspaceResolver.resolve(context, params("workspaceBase", "  ")), "步骤里留空则回退到流水线级");
    }

    @Test
    void explicitWorkspacePathWinsOverEverything() {
        PipelineExecutionContext context = context("svc", "main", "dev");
        assertEquals("/srv/fixed",
                PipelineWorkspaceResolver.resolve(context, params("workspacePath", " /srv/fixed ", "workspaceBase", "/mnt")));
    }

    // ---------- 路径形态转换 ----------

    @Test
    void defaultWorkDir() {
        assertEquals("~/opsflow", WorkspacePathHelper.normalizeDefault(null));
        assertEquals("~/opsflow", WorkspacePathHelper.normalizeDefault("  "));
        assertEquals("/data/ws", WorkspacePathHelper.normalizeDefault(" /data/ws "));
    }

    @Test
    void localPathExpandsTilde() {
        String home = System.getProperty("user.home");
        assertEquals(home + "/opsflow/a", WorkspacePathHelper.toLocalPath("~/opsflow/a"));
        assertEquals(home, WorkspacePathHelper.toLocalPath("~"));
        assertEquals("/data/ws", WorkspacePathHelper.toLocalPath(" /data/ws "));
        assertNull(WorkspacePathHelper.toLocalPath(null));
        assertEquals("", WorkspacePathHelper.toLocalPath(""));
    }

    @Test
    void remoteShellPathKeepsHomeExpandableAndQuotesTheRest() {
        // ~ 必须留给远端 shell 展开，所以用双引号 + $HOME；其余路径用单引号整体引用
        assertEquals("\"$HOME/opsflow/svc/dev/main\"", WorkspacePathHelper.toRemoteShellPath("~/opsflow/svc/dev/main"));
        assertEquals("\"$HOME\"", WorkspacePathHelper.toRemoteShellPath("~"));
        assertEquals("'/data/my ws/svc'", WorkspacePathHelper.toRemoteShellPath("/data/my ws/svc"));
        assertEquals("'/data/it'\\''s'", WorkspacePathHelper.toRemoteShellPath("/data/it's"));
        assertEquals("''", WorkspacePathHelper.toRemoteShellPath(null));
        assertEquals("''", WorkspacePathHelper.toRemoteShellPath(""));
    }

    @Test
    void remoteShellPathEscapesQuotesAndBackslashesInsideDoubleQuotes() {
        assertEquals("\"$HOME/a\\\"b\\\\c\"", WorkspacePathHelper.toRemoteShellPath("~/a\"b\\c"));
    }
}
