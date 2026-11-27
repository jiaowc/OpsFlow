package com.opsflow.service.impl;

import com.opsflow.dao.mapper.InboxMessageMapper;
import com.opsflow.dao.model.InboxMessage;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 流水线结果站内信：点开直接到这次 Job 的详情（/pipeline?job=<id>），没有 Job id 时退回流水线页。
 */
class InboxMessageServiceImplTest {

    @Test
    void pipelineResultLinksToTheJobDetail() {
        InboxMessageMapper mapper = mock(InboxMessageMapper.class);
        InboxMessageServiceImpl service = new InboxMessageServiceImpl();
        ReflectionTestUtils.setField(service, "inboxMessageMapper", mapper);

        service.sendPipelineResult("alice", "❌ 发布失败：order → prod", "服务：order", " 100 ");
        service.sendPipelineResult("alice", "t", "c", null);

        ArgumentCaptor<InboxMessage> sent = ArgumentCaptor.forClass(InboxMessage.class);
        verify(mapper, times(2)).insert(sent.capture());
        assertEquals("/pipeline?job=100", sent.getAllValues().get(0).getLinkPath());
        assertEquals("/pipeline", sent.getAllValues().get(1).getLinkPath());
    }
}
