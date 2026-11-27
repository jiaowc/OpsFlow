package com.opsflow.integration.pipeline.step.impl;

import com.opsflow.integration.pipeline.PipelineExecutionContext;
import com.opsflow.integration.pipeline.step.StepExecutionResult;
import com.opsflow.service.PipelineNotifyService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 通知步骤：消息内容和默认文案、指定通道 channelIds 的解析和传递、发送失败不让步骤失败。
 */
class NotifyStepExecutorTest {

    private final PipelineNotifyService notifyService = mock(PipelineNotifyService.class);

    private NotifyStepExecutor executor() {
        NotifyStepExecutor executor = new NotifyStepExecutor();
        ReflectionTestUtils.setField(executor, "pipelineNotifyService", notifyService);
        return executor;
    }

    private PipelineExecutionContext context() {
        PipelineExecutionContext context = StepTestSupport.context();
        context.setBuildJobId(100L);
        return context;
    }

    @Test
    void parseChannelIdsTrimsDeduplicatesAndCollectsInvalid() {
        List<String> invalid = new ArrayList<>();
        assertEquals(Arrays.asList(3L, 1L), NotifyStepExecutor.parseChannelIds(" 3, 1,,3 ,abc,0,-2", invalid));
        assertEquals(Arrays.asList("abc", "0", "-2"), invalid);
        assertEquals(Collections.emptyList(), NotifyStepExecutor.parseChannelIds(null, null));
        assertEquals(Collections.emptyList(), NotifyStepExecutor.parseChannelIds("  ", null));
    }

    @Test
    void selectedChannelsArePassedToTheNotifyService() {
        when(notifyService.sendStepMessage(eq(100L), anyString(), anyList())).thenReturn("已发送 2/2 个通道");

        StepExecutionResult result = executor().execute("notify",
                StepTestSupport.params("message", "订单已发布", "channelIds", "5,2,x"), context());

        verify(notifyService).sendStepMessage(100L, "订单已发布", Arrays.asList(5L, 2L));
        assertTrue(result.getSuccess());
        assertTrue(result.getLog().contains("指定通道") && result.getLog().contains("[5, 2]"), result.getLog());
        assertTrue(result.getLog().contains("忽略无效通道 id") && result.getLog().contains("x"), result.getLog());
        assertTrue(result.getLog().contains("已发送 2/2 个通道"), result.getLog());
    }

    @Test
    void withoutChannelIdsMatchesByRulesAndOldDataUsesDefaultMessage() {
        when(notifyService.sendStepMessage(anyLong(), anyString(), anyList())).thenReturn("没有匹配的通知通道，跳过");

        StepExecutionResult result = executor().execute("notify", StepTestSupport.params(), context());

        verify(notifyService).sendStepMessage(100L, NotifyStepExecutor.DEFAULT_MESSAGE, Collections.emptyList());
        assertFalse(result.getLog().contains("指定通道"), "没有指定通道时不写这一行: " + result.getLog());

        executor().execute("notify", StepTestSupport.params("message", "  "), context());
        verify(notifyService, org.mockito.Mockito.times(2))
                .sendStepMessage(100L, NotifyStepExecutor.DEFAULT_MESSAGE, Collections.emptyList());
    }

    @Test
    void messageVariablesAreRenderedWithTheStepImageBeforeSending() {
        when(notifyService.renderStepMessage(eq(100L), eq("${service} 镜像 ${image}"), any()))
                .thenAnswer(inv -> {
                    java.util.Map<String, String> runtime = inv.getArgument(2);
                    return "order 镜像 " + runtime.get("image");
                });
        when(notifyService.sendStepMessage(anyLong(), anyString(), anyList())).thenReturn("ok");

        StepExecutionResult result = executor().execute("notify",
                StepTestSupport.params("message", "${service} 镜像 ${image}", "imageFullName", "harbor/order:1.2"), context());

        verify(notifyService).sendStepMessage(100L, "order 镜像 harbor/order:1.2", Collections.emptyList());
        assertTrue(result.getLog().contains("order 镜像 harbor/order:1.2"), result.getLog());
    }

    @Test
    void renderFailureFallsBackToTheOriginalText() {
        when(notifyService.renderStepMessage(anyLong(), anyString(), any())).thenThrow(new IllegalStateException("db down"));
        when(notifyService.sendStepMessage(anyLong(), anyString(), anyList())).thenReturn("ok");

        StepExecutionResult result = executor().execute("notify", StepTestSupport.params("message", "${env} 完成"), context());

        verify(notifyService).sendStepMessage(100L, "${env} 完成", Collections.emptyList());
        assertTrue(result.getSuccess());
    }

    @Test
    void sendFailureIsLoggedAndStepStillSucceeds() {
        when(notifyService.sendStepMessage(anyLong(), anyString(), any())).thenThrow(new IllegalStateException("网络不通"));

        StepExecutionResult result = executor().execute("notify",
                StepTestSupport.params("message", "x", "channelIds", "1"), context());

        assertTrue(result.getSuccess());
        assertTrue(result.getLog().contains("发送失败: 网络不通"), result.getLog());
    }
}
