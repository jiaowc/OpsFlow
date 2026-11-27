package com.opsflow.service.impl.pipeline;

import com.opsflow.api.dto.PipelineDTO;
import com.opsflow.api.dto.PipelineStepDTO;
import com.opsflow.dao.mapper.BuildJobStageMapper;
import com.opsflow.dao.mapper.BuildNodeMapper;
import com.opsflow.dao.model.BuildJobStage;
import com.opsflow.integration.pipeline.NodeCommandHelper;
import com.opsflow.integration.pipeline.PipelineExecutionContext;
import com.opsflow.integration.pipeline.PipelineExecutionResult;
import com.opsflow.integration.pipeline.PipelineStageListener;
import com.opsflow.integration.pipeline.StageLogStore;
import com.opsflow.integration.pipeline.impl.NativePipelineExecutor;
import com.opsflow.integration.pipeline.step.StepExecutor;
import com.opsflow.integration.pipeline.step.impl.BuildStepExecutor;
import com.opsflow.integration.pipeline.step.impl.NotifyStepExecutor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * 阶段日志整条链路：真实的流水线执行器 + 步骤执行器 + 本机命令 + 任务执行器里的阶段回调 + 日志文件，
 * 再按页面的方式（带偏移量分块）把日志读回来。保证日志内容完整、失败原因可见、执行中能看到实时输出。
 * 只有数据库写入是 mock 的。
 */
class PipelineStageLogEndToEndTest {

    private static final long JOB_ID = 42L;

    @TempDir
    Path tempDir;

    private StageLogStore stageLogStore;
    private NativePipelineExecutor executor;
    private PipelineStageListener listener;
    private Map<Integer, BuildJobStage> stages;

    @BeforeEach
    void setUp() throws Exception {
        stageLogStore = new StageLogStore();
        ReflectionTestUtils.setField(stageLogStore, "logDir", tempDir.resolve("logs").toString());
        stageLogStore.init();

        NodeCommandHelper nodeCommandHelper = new NodeCommandHelper();
        BuildStepExecutor build = new BuildStepExecutor();
        ReflectionTestUtils.setField(build, "nodeCommandHelper", nodeCommandHelper);
        executor = new NativePipelineExecutor();
        ReflectionTestUtils.setField(executor, "stepExecutors",
                Arrays.<StepExecutor>asList(build, new NotifyStepExecutor()));
        ReflectionTestUtils.setField(executor, "buildNodeMapper", mock(BuildNodeMapper.class));

        PipelineJobRunner runner = new PipelineJobRunner();
        ReflectionTestUtils.setField(runner, "stageLogStore", stageLogStore);
        ReflectionTestUtils.setField(runner, "buildJobStageMapper", mock(BuildJobStageMapper.class));
        stages = new LinkedHashMap<>();
        stages.put(1, stage(101L, 1));
        stages.put(2, stage(102L, 2));
        listener = ReflectionTestUtils.invokeMethod(runner, "buildStageListener", stages);
    }

    private static BuildJobStage stage(long id, int order) {
        BuildJobStage stage = new BuildJobStage();
        stage.setId(id);
        stage.setBuildJobId(JOB_ID);
        stage.setStepOrder(order);
        stage.setStatus("PENDING");
        return stage;
    }

    private static PipelineStepDTO step(int order, String type, String name, int timeoutSeconds, String... params) {
        PipelineStepDTO step = new PipelineStepDTO();
        step.setOrder(order);
        step.setStepType(type);
        step.setStepName(name);
        step.setEnabled(true);
        step.setTimeoutSeconds(timeoutSeconds);
        Map<String, String> map = new HashMap<>();
        for (int i = 0; i < params.length; i += 2) {
            map.put(params[i], params[i + 1]);
        }
        step.setParameters(map);
        return step;
    }

    private PipelineExecutionResult run(PipelineStepDTO... steps) {
        PipelineDTO pipeline = new PipelineDTO();
        pipeline.setSteps(Arrays.asList(steps));
        PipelineExecutionContext context = new PipelineExecutionContext();
        context.setExecutionId(String.valueOf(JOB_ID));
        context.setBuildJobId(JOB_ID);
        // 工作目录按 <workspaceBase>/<服务>/<环境>/<分支> 解析；正式流程里由拉取代码步骤创建，这里提前建好
        Path workspace = tempDir.resolve("ws/demo/dev/default");
        try {
            java.nio.file.Files.createDirectories(workspace);
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
        context.setWorkspace(workspace.toString());
        PipelineExecutionContext.ServiceInfo service = new PipelineExecutionContext.ServiceInfo();
        service.setCode("demo");
        context.setService(service);
        PipelineExecutionContext.EnvironmentInfo env = new PipelineExecutionContext.EnvironmentInfo();
        env.setName("dev");
        context.setEnvironment(env);
        Map<String, String> parameters = new HashMap<>();
        parameters.put("workspaceBase", tempDir.resolve("ws").toString());
        context.setParameters(parameters);
        context.setStageListener(listener);
        return executor.execute(pipeline, context);
    }

    /** 像页面那样从偏移量 0 开始一块一块读，直到读完 */
    private String readLikeThePage(BuildJobStage stage) {
        StringBuilder all = new StringBuilder();
        long offset = 0;
        for (int i = 0; i < 10000; i++) {
            StageLogStore.ReadResult read = stageLogStore.readFromOffset(
                    stage.getLogPath(), offset, StageLogStore.DEFAULT_READ_CHUNK_BYTES);
            assertFalse(read.isReset());
            all.append(read.getContent());
            offset = read.getNextOffset();
            if (read.isEof()) {
                return all.toString();
            }
        }
        throw new AssertionError("日志一直读不到结尾");
    }

    @Test
    void successfulRunKeepsTheWholeLogOfEveryStage() {
        // 5000 行、约 500KB，含中文；超过内存里保留的 80KB，验证日志文件里一行不少
        String command = "echo '开始构建 订单服务'; "
                + "awk 'BEGIN{for(i=0;i<5000;i++) printf \"line-%d %090d\\n\", i, 0}'; echo '构建完成'";
        PipelineExecutionResult result = run(
                step(1, "build", "编译", 60, "buildCommand", command),
                step(2, "notify", "通知", 60, "message", "发布完成"));

        assertTrue(result.getSuccess(), result.getErrorMessage());
        BuildJobStage buildStage = stages.get(1);
        assertEquals("SUCCESS", buildStage.getStatus());
        assertNotNull(buildStage.getEndTime());

        String log = readLikeThePage(buildStage);
        assertTrue(log.startsWith("==== 开始执行: 编译 (build) ===="), log.substring(0, 80));
        assertTrue(log.contains("==== 代码构建 ===="));
        assertTrue(log.contains("$ " + command));
        assertTrue(log.contains("---- 命令输出 ----\n开始构建 订单服务\nline-0 "));
        int previous = -1;
        for (int i = 0; i < 5000; i++) {
            int at = log.indexOf("\nline-" + i + " ", previous + 1);
            assertTrue(at > previous, "缺少或乱序: line-" + i);
            previous = at;
        }
        assertTrue(log.endsWith("构建完成\n退出码: 0 (成功)\n"), log.substring(log.length() - 60));
        assertFalse(log.contains("已省略"));
        assertEquals(stageLogStore.size(buildStage.getLogPath()),
                log.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
        // 列表页用的末尾预览
        assertTrue(buildStage.getLogPreview().endsWith("构建完成\n退出码: 0 (成功)\n"));

        BuildJobStage notifyStage = stages.get(2);
        assertEquals("SUCCESS", notifyStage.getStatus());
        String notifyLog = readLikeThePage(notifyStage);
        assertTrue(notifyLog.contains("==== 发送通知 ===="));
        assertTrue(notifyLog.contains("消息: 发布完成"));

        assertEquals(0, stageLogStore.openLogCount(), "阶段结束后日志文件应已关闭");
    }

    @Test
    void failedStepShowsOutputExitCodeAndReasonAndStopsThePipeline() {
        PipelineExecutionResult result = run(
                step(1, "build", "编译", 60, "buildCommand", "echo '编译报错: 找不到符号'; exit 7"),
                step(2, "notify", "通知", 60));

        assertFalse(result.getSuccess());
        assertEquals("命令执行失败，退出码: 7", result.getErrorMessage());
        BuildJobStage buildStage = stages.get(1);
        assertEquals("FAILURE", buildStage.getStatus());
        assertEquals("命令执行失败，退出码: 7", buildStage.getErrorMessage());

        String log = readLikeThePage(buildStage);
        assertTrue(log.contains("---- 命令输出 ----\n编译报错: 找不到符号\n退出码: 7 (失败)\n"), log);
        assertTrue(log.endsWith("\n[错误] 命令执行失败，退出码: 7\n"), log);
        assertTrue(buildStage.getLogPreview().contains("[错误] 命令执行失败，退出码: 7"));

        assertEquals("PENDING", stages.get(2).getStatus(), "失败后不应继续执行后面的步骤");
        assertEquals(0, stageLogStore.openLogCount());
    }

    @Test
    void timedOutStepKeepsWhatWasPrintedAndSaysItTimedOut() {
        PipelineExecutionResult result = run(
                step(1, "build", "编译", 1, "buildCommand", "echo '已经输出的内容'; sleep 30"));

        assertFalse(result.getSuccess());
        BuildJobStage buildStage = stages.get(1);
        assertEquals("FAILURE", buildStage.getStatus());
        String log = readLikeThePage(buildStage);
        assertTrue(log.contains("已经输出的内容\n"), log);
        assertTrue(buildStage.getErrorMessage().contains("超时"), buildStage.getErrorMessage());
        // 被中断的步骤在阶段结束前收完尾：错误说明是最后一行，之后不会再有内容写进来（也不会重新创建已关闭的日志文件）
        String lastLine = log.substring(log.lastIndexOf('\n', log.length() - 2) + 1);
        assertEquals("[错误] " + buildStage.getErrorMessage() + "\n", lastLine, log);
        assertEquals(0, stageLogStore.openLogCount());
    }

    @Test
    void outputIsReadableWhileTheStepIsStillRunning() throws Exception {
        // 命令打印第一段后一直等，直到测试读到第一段、创建放行文件才继续，不靠固定的 sleep 时长
        Path go = tempDir.resolve("go");
        CompletableFuture<PipelineExecutionResult> running = CompletableFuture.supplyAsync(() -> run(
                step(1, "build", "编译", 60, "buildCommand",
                        "echo '第一段输出'; while [ ! -f '" + go + "' ]; do sleep 0.05; done; echo '第二段输出'")));

        // 步骤还在等待时，页面轮询应该已经能读到第一段
        BuildJobStage buildStage = stages.get(1);
        String partial = "";
        long deadline = System.currentTimeMillis() + 30000;
        while (System.currentTimeMillis() < deadline && !partial.contains("\n第一段输出\n")) {
            Thread.sleep(50);
            if (buildStage.getLogPath() != null) {
                partial = readLikeThePage(buildStage);
            }
        }
        assertTrue(partial.contains("\n第一段输出\n"), "执行中读到的内容: " + partial);
        // 命令行本身也会记进日志，所以按「独占一行」判断是不是命令的输出
        assertFalse(partial.contains("\n第二段输出\n"));
        assertFalse(running.isDone());
        assertEquals("RUNNING", buildStage.getStatus());

        java.nio.file.Files.createFile(go);
        assertTrue(running.get(30, TimeUnit.SECONDS).getSuccess());
        String log = readLikeThePage(buildStage);
        assertTrue(log.startsWith(partial), "执行中读到的内容应是最终日志的开头，页面才能按偏移量续读");
        assertTrue(log.endsWith("第二段输出\n退出码: 0 (成功)\n"), log);
    }

    @Test
    void unsupportedStepTypeDoesNotLeaveTheLogFileOpenAfterTheJobEnds() {
        // 找不到步骤执行器时不会走到阶段结束回调，任务结束时的兜底要把文件关掉
        PipelineExecutionResult result = run(step(1, "no_such_step", "未知步骤", 60));

        assertFalse(result.getSuccess());
        stageLogStore.closeJobLogs(JOB_ID);
        assertEquals(0, stageLogStore.openLogCount());
    }
}
