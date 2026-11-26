package com.opsflow.service.impl;

import com.opsflow.common.constant.BuildStatus;
import com.opsflow.dao.mapper.BuildJobMapper;
import com.opsflow.dao.mapper.EnvMapper;
import com.opsflow.dao.mapper.ProjectMapper;
import com.opsflow.dao.mapper.ServiceMapper;
import com.opsflow.dao.mapper.UserMapper;
import com.opsflow.dao.model.BuildJob;
import com.opsflow.dao.model.Env;
import com.opsflow.dao.model.Project;
import com.opsflow.dao.model.Service;
import com.opsflow.dao.model.User;
import com.opsflow.service.InboxMessageService;
import com.opsflow.service.NotifyConfigService;
import com.opsflow.service.SystemConfigService;
import com.opsflow.spi.DefaultJobNotifyExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

        BuildJobMapper buildJobMapper = mock(BuildJobMapper.class);
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
        ReflectionTestUtils.setField(service, "systemConfigService", mock(SystemConfigService.class));
        ReflectionTestUtils.setField(service, "jobNotifyExtension", new DefaultJobNotifyExtension());
    }

    private static Env env(String name, String type) {
        Env env = new Env();
        env.setName(name);
        env.setEnvType(type);
        return env;
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
