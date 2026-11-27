package com.opsflow.service.impl;

import com.opsflow.common.constant.BuildStatus;
import com.opsflow.dao.mapper.BuildJobMapper;
import com.opsflow.dao.mapper.BuildJobStageMapper;
import com.opsflow.dao.mapper.PipelineMapper;
import com.opsflow.dao.mapper.EnvMapper;
import com.opsflow.dao.mapper.ProjectMapper;
import com.opsflow.dao.mapper.ServiceMapper;
import com.opsflow.dao.mapper.UserMapper;
import com.opsflow.dao.model.BuildJob;
import com.opsflow.dao.model.BuildJobStage;
import com.opsflow.dao.model.Pipeline;
import com.opsflow.dao.model.Env;
import com.opsflow.dao.model.Project;
import com.opsflow.dao.model.Service;
import com.opsflow.dao.model.User;
import com.opsflow.service.InboxMessageService;
import com.opsflow.service.NotifyConfigService;
import com.opsflow.service.SystemConfigService;
import com.opsflow.spi.DefaultJobNotifyExtension;
import com.opsflow.spi.JobNotifyContext;
import com.opsflow.integration.notify.NotifyMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 流水线结果通知（核心）：站内信、社区版默认扩展（没有外部通道），以及耗时和失败原因的格式化。
 * 企业版的外部通道在企业版模块里单独测试。
 */
class PipelineNotifyServiceImplTest {

    private static final long PROJECT = 7L;

    private PipelineNotifyServiceImpl service;
    private InboxMessageService inbox;
    private NotifyConfigService notifyConfig;
    private SystemConfigService systemConfig;
    private BuildJobMapper buildJobMapper;
    private BuildJobStageMapper stageMapper;
    private final List<NotifyMessage> sent = new ArrayList<>();

    @BeforeEach
    void setUp() {
        service = new PipelineNotifyServiceImpl();
        inbox = mock(InboxMessageService.class);
        notifyConfig = mock(NotifyConfigService.class);

        ServiceMapper serviceMapper = mock(ServiceMapper.class);
        Service svc = new Service();
        svc.setId(1L);
        svc.setCode("order");
        svc.setName("订单服务");
        svc.setProjectId(PROJECT);
        when(serviceMapper.selectById(1L)).thenReturn(svc);

        EnvMapper envMapper = mock(EnvMapper.class);
        when(envMapper.selectById(10L)).thenReturn(env("test", "nonprod"));
        when(envMapper.selectById(20L)).thenReturn(env("prod", "prod"));

        ProjectMapper projectMapper = mock(ProjectMapper.class);
        Project project = new Project();
        project.setName("商城");
        when(projectMapper.selectById(PROJECT)).thenReturn(project);

        UserMapper userMapper = mock(UserMapper.class);
        User user = new User();
        user.setUsername("alice");
        when(userMapper.selectById(5L)).thenReturn(user);

        PipelineMapper pipelineMapper = mock(PipelineMapper.class);
        when(pipelineMapper.selectById(3L)).thenReturn(pipeline("订单发布", "cd"));
        when(pipelineMapper.selectById(4L)).thenReturn(pipeline("订单构建", "ci"));
        when(pipelineMapper.selectById(6L)).thenReturn(pipeline("构建并发布", "cicd"));
        stageMapper = mock(BuildJobStageMapper.class);
        when(stageMapper.selectList(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class)))
                .thenReturn(Collections.emptyList());
        systemConfig = mock(SystemConfigService.class);
        when(systemConfig.get("notify", "portalUrl")).thenReturn("https://ops.example.com/");

        buildJobMapper = mock(BuildJobMapper.class);
        when(buildJobMapper.selectById(100L)).thenAnswer(inv -> job(BuildStatus.BUILDING, 10L, 5L));

        when(notifyConfig.isInboxEnabled()).thenReturn(true);
        when(notifyConfig.isPipelineInboxEnabled()).thenReturn(true);

        ReflectionTestUtils.setField(service, "buildJobMapper", buildJobMapper);
        ReflectionTestUtils.setField(service, "serviceMapper", serviceMapper);
        ReflectionTestUtils.setField(service, "envMapper", envMapper);
        ReflectionTestUtils.setField(service, "projectMapper", projectMapper);
        ReflectionTestUtils.setField(service, "userMapper", userMapper);
        ReflectionTestUtils.setField(service, "inboxMessageService", inbox);
        ReflectionTestUtils.setField(service, "notifyConfigService", notifyConfig);
        ReflectionTestUtils.setField(service, "systemConfigService", systemConfig);
        ReflectionTestUtils.setField(service, "pipelineMapper", pipelineMapper);
        ReflectionTestUtils.setField(service, "buildJobStageMapper", stageMapper);
        ReflectionTestUtils.setField(service, "jobNotifyExtension", new DefaultJobNotifyExtension() {
            @Override
            public void onJobFinished(JobNotifyContext context, NotifyMessage message) {
                sent.add(message);
            }
        });
    }

    private static Env env(String name, String type) {
        Env env = new Env();
        env.setName(name);
        env.setEnvType(type);
        return env;
    }

    private static Pipeline pipeline(String name, String type) {
        Pipeline pipeline = new Pipeline();
        pipeline.setName(name);
        pipeline.setPipelineType(type);
        return pipeline;
    }

    private static BuildJobStage stage(String name, String status) {
        BuildJobStage stage = new BuildJobStage();
        stage.setStepName(name);
        stage.setStatus(status);
        return stage;
    }

    private NotifyMessage finish(BuildJob job) {
        sent.clear();
        service.doNotifyJobFinished(job);
        assertEquals(1, sent.size());
        return sent.get(0);
    }

    private static BuildJob job(String status, Long envId, Long creatorId) {
        BuildJob job = new BuildJob();
        job.setId(100L);
        job.setJobNumber("J-100");
        job.setServiceId(1L);
        job.setEnvId(envId);
        job.setBranch("main");
        job.setStatus(status);
        job.setCreatorId(creatorId);
        job.setStartTime(LocalDateTime.of(2026, 1, 1, 10, 0, 0));
        job.setEndTime(LocalDateTime.of(2026, 1, 1, 10, 2, 5));
        return job;
    }

    @Test
    void inboxIsSkippedWithoutRecipientOrWhenSwitchedOff() {
        service.doNotifyJobFinished(job(BuildStatus.SUCCESS, 10L, null));
        service.doNotifyJobFinished(job(BuildStatus.SUCCESS, 10L, 404L));
        verify(inbox, never()).sendPipelineResult(any(), any(), any(), any());

        when(notifyConfig.isPipelineInboxEnabled()).thenReturn(false);
        service.doNotifyJobFinished(job(BuildStatus.SUCCESS, 10L, 5L));
        verify(inbox, never()).sendPipelineResult(any(), any(), any(), any());
    }

    @Test
    void failedJobSendsPlainTextInboxWithReason() {
        BuildJob job = job(BuildStatus.FAILED, 20L, 5L);
        job.setErrorMessage("**compile** failed");

        service.doNotifyJobFinished(job);

        ArgumentCaptor<String> title = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> content = ArgumentCaptor.forClass(String.class);
        verify(inbox).sendPipelineResult(eq("alice"), title.capture(), content.capture(), eq("100"));
        assertTrue(title.getValue().contains("流水线失败") && title.getValue().contains("order → prod"), title.getValue());
        assertTrue(content.getValue().contains("服务：订单服务"));
        assertTrue(content.getValue().contains("耗时：2 分 5 秒"), content.getValue());
        assertTrue(content.getValue().contains("失败原因：") && !content.getValue().contains("**"), "站内信是纯文本，不带 Markdown 标记");
    }

    @Test
    void nonTerminalJobIsIgnored() {
        service.doNotifyJobFinished(job(BuildStatus.BUILDING, 10L, 5L));
        verify(inbox, never()).sendPipelineResult(any(), any(), any(), any());
    }

    @Test
    void communityEditionSendsInboxOnlyAndStepReportsNoChannels() {
        service.doNotifyJobFinished(job(BuildStatus.SUCCESS, 10L, 5L));

        verify(inbox).sendPipelineResult(eq("alice"), anyString(), anyString(), eq("100"));
        assertTrue(service.sendStepMessage(100L, "x").contains("社区版"));
        // 社区版默认实现忽略指定的通道
        assertTrue(service.sendStepMessage(100L, "x", java.util.Arrays.asList(1L, 2L)).contains("社区版"));
    }

    @Test
    void failedReleaseCardHasFieldsInOrderFailedStepAndDeepLink() {
        BuildJob job = job(BuildStatus.FAILED, 20L, 5L);
        job.setPipelineTemplateId(3L);
        job.setImageFullName("harbor.example.com/shop/order:1.2.0");
        job.setErrorMessage("push denied");
        when(stageMapper.selectList(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class))).thenReturn(Arrays.asList(
                stage("拉取代码", "SUCCESS"), stage("构建镜像", "SUCCESS"), stage("推送镜像", "FAILURE"), stage("部署", "PENDING")));

        NotifyMessage message = finish(job);

        assertEquals("❌ 发布失败：order → prod", message.getTitle());
        assertEquals(Arrays.asList("项目", "服务", "环境", "流水线", "分支/Tag", "镜像", "任务编号", "触发人", "开始时间", "耗时", "失败步骤"),
                new ArrayList<>(message.getFields().keySet()), "手动触发分不清是否重新构建，不写触发方式");
        assertEquals("订单发布", message.getFields().get("流水线"));
        assertEquals("harbor.example.com/shop/order:1.2.0", message.getFields().get("镜像"));
        assertEquals("2026-01-01 10:00:00", message.getFields().get("开始时间"));
        assertEquals("第 3 步 推送镜像", message.getFields().get("失败步骤"));
        assertTrue(message.getDetail().contains("push denied"), "失败原因仍在正文");
        assertEquals("https://ops.example.com/pipeline?job=100", message.getLinkUrl());
    }

    @Test
    void titleShowsRunTypeAndFallsBackToPipeline() {
        BuildJob ci = job(BuildStatus.SUCCESS, 10L, 5L);
        ci.setPipelineTemplateId(4L);
        assertEquals("✅ 构建成功：order → test", finish(ci).getTitle());

        BuildJob cicd = job(BuildStatus.SUCCESS, 10L, 5L);
        cicd.setPipelineTemplateId(6L);
        assertEquals("✅ 发布成功：order → test", finish(cicd).getTitle());

        BuildJob unknown = job(BuildStatus.FAILED, 10L, 5L);
        unknown.setPipelineTemplateId(404L);
        NotifyMessage message = finish(unknown);
        assertEquals("❌ 流水线失败：order → test", message.getTitle());
        assertFalse(message.getFields().containsKey("流水线"));
        assertFalse(message.getFields().containsKey("失败步骤"), "没有失败阶段记录时不写失败步骤");

        BuildJob rollback = job(BuildStatus.SUCCESS, 20L, 5L);
        rollback.setPipelineTemplateId(3L);
        rollback.setBuildParameters("{\"rollback\":\"true\"}");
        NotifyMessage rolled = finish(rollback);
        assertEquals("✅ 回滚成功：order → prod", rolled.getTitle());
        assertEquals("回滚", rolled.getFields().get("触发方式"));
        assertFalse(rolled.getFields().containsKey("失败步骤"), "成功时不写失败步骤");
    }

    @Test
    void triggerLabelOnlyWhatTheDataCanTell() {
        Map<String, Object> none = Collections.emptyMap();
        BuildJob job = job(BuildStatus.SUCCESS, 10L, 5L);
        assertNull(PipelineNotifyServiceImpl.triggerLabel(job, none, false), "手动 / 重新构建分不清");
        assertEquals("回滚", PipelineNotifyServiceImpl.triggerLabel(job, none, true));

        Map<String, Object> webhook = new HashMap<>();
        webhook.put("creatorUsername", "webhook(bob)");
        assertEquals("Webhook（代码推送）", PipelineNotifyServiceImpl.triggerLabel(job, webhook, false));
        webhook.put("creatorUsername", "webhook");
        assertEquals("Webhook（代码推送）", PipelineNotifyServiceImpl.triggerLabel(job, webhook, false));
        webhook.put("creatorUsername", "webhooker");
        assertNull(PipelineNotifyServiceImpl.triggerLabel(job, webhook, false));

        job.setDeployTaskId(9L);
        assertEquals("上线任务", PipelineNotifyServiceImpl.triggerLabel(job, none, false));

        // 卡片里触发方式紧跟在任务编号（及扩展补充的字段）后面
        BuildJob fromTask = job(BuildStatus.SUCCESS, 10L, 5L);
        fromTask.setDeployTaskId(9L);
        List<String> keys = new ArrayList<>(finish(fromTask).getFields().keySet());
        assertEquals(keys.indexOf("任务编号") + 1, keys.indexOf("触发方式"), keys.toString());
    }

    @Test
    void deepLinkUsesExtensionPortalFirstAndIsOmittedWithoutPortal() {
        assertEquals("https://a.example.com/pipeline?job=7", PipelineNotifyServiceImpl.jobDetailUrl(" https://a.example.com// ", 7L));
        assertNull(PipelineNotifyServiceImpl.jobDetailUrl("  ", 7L));

        ReflectionTestUtils.setField(service, "jobNotifyExtension", new DefaultJobNotifyExtension() {
            @Override
            public void enrich(JobNotifyContext context, NotifyMessage message) {
                message.setLinkUrl("https://feishu-portal.example.com");
            }

            @Override
            public void onJobFinished(JobNotifyContext context, NotifyMessage message) {
                sent.add(message);
            }
        });
        assertEquals("https://feishu-portal.example.com/pipeline?job=100", finish(job(BuildStatus.SUCCESS, 10L, 5L)).getLinkUrl());

        ReflectionTestUtils.setField(service, "jobNotifyExtension", new DefaultJobNotifyExtension() {
            @Override
            public void onJobFinished(JobNotifyContext context, NotifyMessage message) {
                sent.add(message);
            }
        });
        when(systemConfig.get("notify", "portalUrl")).thenReturn(null);
        assertNull(finish(job(BuildStatus.SUCCESS, 10L, 5L)).getLinkUrl(), "没配门户地址时不出「查看详情」");
    }

    @Test
    void renderVariablesKeepsUnknownBlanksEmptyAndNeverReparses() {
        Map<String, String> vars = new HashMap<>();
        vars.put("service", "order");
        vars.put("env", null);
        vars.put("branch", "${env}$1\\");
        assertEquals("order -> [] ${foo} ${service", PipelineNotifyServiceImpl.renderVariables("${service} -> [${env}] ${foo} ${service", vars));
        assertEquals("分支 ${env}$1\\", PipelineNotifyServiceImpl.renderVariables("分支 ${branch}", vars), "替换进去的值原样，不再展开");
        assertNull(PipelineNotifyServiceImpl.renderVariables(null, vars));
    }

    @Test
    void renderStepMessageUsesJobDataAndRuntimeImage() {
        BuildJob job = job(BuildStatus.BUILDING, 20L, 5L);
        job.setPipelineTemplateId(3L);
        when(buildJobMapper.selectById(100L)).thenReturn(job);
        Map<String, String> runtime = Collections.singletonMap("image", "harbor/order:9");

        String text = service.renderStepMessage(100L,
                "${project}/${service}@${env} ${branch} ${image} ${jobNumber} ${trigger} ${pipeline} ${unknown}", runtime);

        assertEquals("商城/order@prod main harbor/order:9 J-100 alice 订单发布 ${unknown}", text);
        assertEquals("无变量", service.renderStepMessage(100L, "无变量", runtime));
    }

    @Test
    void durationAndAbbreviationFormatting() {
        assertEquals("42 秒", PipelineNotifyServiceImpl.formatDuration(Duration.ofSeconds(42)));
        assertEquals("1 分 0 秒", PipelineNotifyServiceImpl.formatDuration(Duration.ofSeconds(60)));
        assertEquals("0 秒", PipelineNotifyServiceImpl.formatDuration(Duration.ofSeconds(-3)));
    }

    @Test
    void errorSummaryStripsAnsiAndKeepsTheTailLines() {
        String esc = "\u001B";
        assertEquals("[INFO] ok", PipelineNotifyServiceImpl.summarizeError("[" + esc + "[1;34mINFO" + esc + "[m] ok\r\n", 300));
        assertEquals("", PipelineNotifyServiceImpl.summarizeError(null, 300));

        StringBuilder output = new StringBuilder("[" + esc + "[1;34mINFO" + esc + "[m] Scanning for projects...\n");
        for (int i = 0; i < 50; i++) {
            output.append("[INFO] Downloading dependency ").append(i).append("\n");
        }
        output.append("\n[" + esc + "[1;31mERROR" + esc + "[m] COMPILATION ERROR :\n");
        output.append("[ERROR] /src/Foo.java:[12,5] cannot find symbol\n");
        String summary = PipelineNotifyServiceImpl.summarizeError(output.toString(), 120);

        assertTrue(summary.endsWith("[ERROR] /src/Foo.java:[12,5] cannot find symbol"), summary);
        assertTrue(summary.contains("[ERROR] COMPILATION ERROR :"), "报错在输出末尾，必须保留");
        assertFalse(summary.contains("Scanning for projects"), "开头的启动信息应被截掉");
        assertFalse(summary.contains(esc), "不能带控制符");
        assertTrue(summary.startsWith("...\n") && summary.length() <= 124);

        // Maven 编译失败：结尾几百字是通用提示，真正的报错在它前面
        StringBuilder maven = new StringBuilder();
        for (int i = 0; i < 50; i++) {
            maven.append("[INFO] Compiling module ").append(i).append("\n");
        }
        maven.append("[ERROR] COMPILATION ERROR : \n")
                .append("[INFO] -------------------------------------------------------------\n")
                .append("[ERROR] /src/Foo.java:[12,5] illegal start of expression\n")
                .append("[INFO] 1 error\n")
                .append("[INFO] BUILD FAILURE\n")
                .append("[INFO] Total time:  3.2 s\n")
                .append("[INFO] Finished at: 2026-11-19T10:00:00+08:00\n")
                .append("[ERROR] Failed to execute goal org.apache.maven.plugins:maven-compiler-plugin:3.11.0:compile (default-compile) on project demo: Compilation failure\n")
                .append("[ERROR] /src/Foo.java:[12,5] illegal start of expression\n")
                .append("[ERROR] \n")
                .append("[ERROR] -> [Help 1]\n")
                .append("[ERROR] \n")
                .append("[ERROR] To see the full stack trace of the errors, re-run Maven with the -e switch.\n")
                .append("[ERROR] Re-run Maven using the -X switch to enable full debug logging.\n")
                .append("[ERROR] \n")
                .append("[ERROR] For more information about the errors and possible solutions, please read the following articles:\n")
                .append("[ERROR] [Help 1] http://cwiki.apache.org/confluence/display/MAVEN/MojoFailureException\n");
        String mavenSummary = PipelineNotifyServiceImpl.summarizeError(maven.toString(), 300);
        assertTrue(mavenSummary.contains("illegal start of expression"), mavenSummary);
        assertTrue(mavenSummary.contains("Compilation failure"), mavenSummary);
        assertFalse(mavenSummary.contains("Re-run Maven") || mavenSummary.contains("[Help 1]")
                || mavenSummary.contains("Finished at"), "通用收尾提示不应占用失败原因: " + mavenSummary);
        assertTrue(mavenSummary.length() <= 304);

        String onlyHints = "[ERROR] -> [Help 1]\n[ERROR] Re-run Maven using the -X switch to enable full debug logging.\n"
                + "[ERROR] For more information about the errors and possible solutions, please read the following articles:\n";
        assertFalse(PipelineNotifyServiceImpl.summarizeError(onlyHints, 60).isEmpty(), "全是提示行时也要有内容");

        StringBuilder oneLine = new StringBuilder();
        for (int i = 0; i < 100; i++) {
            oneLine.append("0123456789");
        }
        String cut = PipelineNotifyServiceImpl.summarizeError(oneLine + "END", 50);
        assertTrue(cut.startsWith("...") && cut.endsWith("END") && cut.length() == 53);
    }
}
