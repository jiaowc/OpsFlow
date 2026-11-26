package com.opsflow.web.controller;

import com.opsflow.api.dto.PipelineDTO;
import com.opsflow.api.dto.PipelineStepDTO;
import com.opsflow.common.exception.BusinessException;
import com.opsflow.dao.mapper.PipelineMapper;
import com.opsflow.dao.model.Pipeline;
import com.opsflow.support.MockInjector;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 流水线定义：名称必填，每个步骤都要选步骤定义 */
class PipelineControllerRequiredTest {

    private static String error(Runnable call) {
        return assertThrows(BusinessException.class, call::run).getMessage();
    }

    private static PipelineStepDTO step(Long templateId, String stepType) {
        PipelineStepDTO s = new PipelineStepDTO();
        s.setStepTemplateId(templateId);
        s.setStepType(stepType);
        return s;
    }

    @Test
    void createRequiresNameAndStepDefinitions() {
        PipelineController controller = MockInjector.inject(new PipelineController());
        PipelineMapper mapper = MockInjector.field(controller, "pipelineMapper");

        PipelineDTO noName = new PipelineDTO();
        noName.setPipelineType("ci");
        noName.setName(" ");
        assertEquals("流水线名称不能为空", error(() -> controller.createPipeline(noName)));

        PipelineDTO emptyStep = new PipelineDTO();
        emptyStep.setName("ci");
        emptyStep.setPipelineType("ci");
        emptyStep.setSteps(Arrays.asList(step(7L, null), step(null, null)));
        assertEquals("第 2 个步骤未选择步骤定义", error(() -> controller.createPipeline(emptyStep)));
        verify(mapper, never()).insert(any(Pipeline.class));

        // 引用步骤定义、或直接写 stepType 的内联步骤（接口老用法）都允许
        PipelineDTO ok = new PipelineDTO();
        ok.setName(" backend-ci ");
        ok.setPipelineType("ci");
        ok.setSteps(Arrays.asList(step(7L, null), step(null, "build")));
        controller.createPipeline(ok);
        ArgumentCaptor<Pipeline> captor = ArgumentCaptor.forClass(Pipeline.class);
        verify(mapper).insert(captor.capture());
        assertEquals("backend-ci", captor.getValue().getName());
    }

    @Test
    void updateRejectsBlankNameAndKeepsNameWhenAbsent() {
        PipelineController controller = MockInjector.inject(new PipelineController());
        PipelineMapper mapper = MockInjector.field(controller, "pipelineMapper");
        Pipeline stored = new Pipeline();
        stored.setId(4L);
        stored.setName("backend-ci");
        stored.setPipelineType("ci");
        when(mapper.selectById(4L)).thenReturn(stored);

        PipelineDTO blank = new PipelineDTO();
        blank.setName("");
        assertEquals("流水线名称不能为空", error(() -> controller.updatePipeline(4L, blank)));
        PipelineDTO badStep = new PipelineDTO();
        badStep.setSteps(Collections.singletonList(step(null, " ")));
        assertEquals("第 1 个步骤未选择步骤定义", error(() -> controller.updatePipeline(4L, badStep)));
        verify(mapper, never()).updateById(any(Pipeline.class));

        PipelineDTO partial = new PipelineDTO();
        partial.setDescription("只改描述");
        controller.updatePipeline(4L, partial);
        assertEquals("backend-ci", stored.getName());
    }
}
