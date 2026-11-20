package com.opsflow.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.opsflow.api.dto.ApprovalActionRequest;
import com.opsflow.api.dto.ApprovalRecordDTO;
import com.opsflow.common.exception.BusinessException;
import com.opsflow.dao.mapper.ApprovalFlowMapper;
import com.opsflow.dao.mapper.ApprovalRecordMapper;
import com.opsflow.dao.mapper.DeployTaskMapper;
import com.opsflow.dao.model.ApprovalFlow;
import com.opsflow.dao.model.ApprovalRecord;
import com.opsflow.dao.model.DeployTask;
import com.opsflow.service.ApprovalNotifyDispatchService;
import com.opsflow.service.DeployTaskCdService;
import com.opsflow.service.FeishuUserResolveService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 审批流转：发起 → 逐步审批 → 全部通过 / 任一步驳回 / 解锁撤回。
 */
@SuppressWarnings("unchecked")
class ApprovalServiceImplTest {

    private ApprovalServiceImpl service;
    private ApprovalFlowMapper approvalFlowMapper;
    private ApprovalRecordMapper approvalRecordMapper;
    private DeployTaskMapper deployTaskMapper;
    private ApprovalNotifyDispatchService notifyService;
    private FeishuUserResolveService feishuUserResolveService;
    private DeployTaskCdService deployTaskCdService;
    private DeployTask task;

    @BeforeEach
    void setUp() {
        service = new ApprovalServiceImpl();
        approvalFlowMapper = mock(ApprovalFlowMapper.class);
        approvalRecordMapper = mock(ApprovalRecordMapper.class);
        deployTaskMapper = mock(DeployTaskMapper.class);
        notifyService = mock(ApprovalNotifyDispatchService.class);
        feishuUserResolveService = mock(FeishuUserResolveService.class);
        deployTaskCdService = mock(DeployTaskCdService.class);
        ReflectionTestUtils.setField(service, "approvalFlowMapper", approvalFlowMapper);
        ReflectionTestUtils.setField(service, "approvalRecordMapper", approvalRecordMapper);
        ReflectionTestUtils.setField(service, "deployTaskMapper", deployTaskMapper);
        ReflectionTestUtils.setField(service, "approvalNotifyDispatchService", notifyService);
        ReflectionTestUtils.setField(service, "feishuUserResolveService", feishuUserResolveService);
        ReflectionTestUtils.setField(service, "deployTaskCdService", deployTaskCdService);

        task = new DeployTask();
        task.setId(1L);
        task.setTaskNumber("TASK-1");
        task.setTaskName("订单上线");
        task.setApprovalFlowId(3L);
        task.setApprovalStatus("none");
        when(deployTaskMapper.selectById(1L)).thenReturn(task);
    }

    private void givenFlow(Integer status, String stepsJson) {
        ApprovalFlow flow = new ApprovalFlow();
        flow.setId(3L);
        flow.setStatus(status);
        flow.setSteps(stepsJson);
        when(approvalFlowMapper.selectById(3L)).thenReturn(flow);
    }

    private static ApprovalRecord record(long id, int step, String approver, String status) {
        ApprovalRecord r = new ApprovalRecord();
        r.setId(id);
        r.setTaskId(1L);
        r.setCurrentStep(step);
        r.setApprover(approver);
        r.setStatus(status);
        return r;
    }

    private static ApprovalActionRequest comment(String text) {
        ApprovalActionRequest request = new ApprovalActionRequest();
        request.setComment(text);
        return request;
    }

    private String error(Runnable action) {
        return assertThrows(BusinessException.class, action::run).getMessage();
    }

    private List<ApprovalRecord> insertedRecords(int expected) {
        ArgumentCaptor<ApprovalRecord> captor = ArgumentCaptor.forClass(ApprovalRecord.class);
        verify(approvalRecordMapper, times(expected)).insert(captor.capture());
        return captor.getAllValues();
    }

    // ---------- 发起审批 ----------

    @Test
    void startCreatesOneRecordPerStepInOrderAndNotifiesFirstApprover() {
        // 步骤故意倒着写：必须按 step 排序，第一步待审批，其余等待
        givenFlow(1, "[{\"step\":2,\"approver\":\"leader\",\"approverName\":\"负责人\"},{\"step\":1,\"approver\":\" jack \"}]");
        when(feishuUserResolveService.resolveFeishuUserId("jack")).thenReturn("ou_jack");

        service.startApproval(task);

        List<ApprovalRecord> records = insertedRecords(2);
        assertEquals(1, records.get(0).getCurrentStep());
        assertEquals("jack", records.get(0).getApprover(), "审批人用户名去掉首尾空格");
        assertEquals("ou_jack", records.get(0).getApproverFeishuId());
        assertEquals(ApprovalServiceImpl.STATUS_PENDING, records.get(0).getStatus());
        assertEquals(2, records.get(1).getCurrentStep());
        assertEquals("负责人", records.get(1).getApproverName());
        assertEquals(ApprovalServiceImpl.STATUS_WAITING, records.get(1).getStatus());
        assertEquals("TASK-1", records.get(0).getTaskNumber());
        assertEquals("pending", task.getApprovalStatus());
        verify(approvalRecordMapper).delete(any(Wrapper.class));
        verify(notifyService).notifyPending(records.get(0), task);
        verifyNoInteractions(deployTaskCdService);
    }

    @Test
    void stepWithSeveralApproversStoresCandidates() {
        givenFlow(1, "[{\"step\":1,\"approver\":\"ignored\",\"approvers\":[\"jack\",\"leader\"]}]");
        service.startApproval(task);
        ApprovalRecord record = insertedRecords(1).get(0);
        assertEquals(",jack,leader,", record.getApproverCandidates());
        assertNull(record.getApprover(), "有多个候选人时单个审批人字段不再生效");
    }

    @Test
    void stepWithoutNumberUsesItsPosition() {
        givenFlow(1, "[{\"approver\":\"jack\"},{\"approver\":\"leader\"}]");
        service.startApproval(task);
        List<ApprovalRecord> records = insertedRecords(2);
        assertEquals(Arrays.asList(1, 2), Arrays.asList(records.get(0).getCurrentStep(), records.get(1).getCurrentStep()));
    }

    @Test
    void taskWithoutFlowOrWithEmptyFlowIsApprovedImmediately() {
        task.setApprovalFlowId(null);
        service.startApproval(task);
        assertEquals("approved", task.getApprovalStatus());

        task.setApprovalFlowId(3L);
        task.setApprovalStatus("none");
        givenFlow(1, "[]");
        service.startApproval(task);
        assertEquals("approved", task.getApprovalStatus());

        verify(approvalRecordMapper, never()).insert(any());
        verifyNoInteractions(notifyService);
        // 免审通过不会自动开始部署，仍需要点「发布」
        verifyNoInteractions(deployTaskCdService);
    }

    @Test
    void startFailsForInvalidFlow() {
        assertEquals("任务无效", error(() -> service.startApproval(null)));
        assertEquals("任务无效", error(() -> service.startApproval(new DeployTask())));

        when(approvalFlowMapper.selectById(3L)).thenReturn(null);
        assertEquals("审批流不存在", error(() -> service.startApproval(task)));

        givenFlow(0, "[{\"step\":1,\"approver\":\"jack\"}]");
        assertEquals("审批流已禁用", error(() -> service.startApproval(task)));

        givenFlow(1, "{not-an-array");
        assertEquals("审批步骤配置无效", error(() -> service.startApproval(task)));
        verify(approvalRecordMapper, never()).insert(any());
    }

    @Test
    void restartingApprovalDiscardsOldRecordsFirst() {
        givenFlow(1, "[{\"step\":1,\"approver\":\"jack\"}]");
        service.startApproval(task);
        org.mockito.InOrder order = org.mockito.Mockito.inOrder(approvalRecordMapper);
        order.verify(approvalRecordMapper).delete(any(Wrapper.class));
        order.verify(approvalRecordMapper).insert(any());
    }

    // ---------- 通过 ----------

    @Test
    void approvingAMiddleStepActivatesTheNextOne() {
        ApprovalRecord first = record(11L, 1, "jack", "pending");
        ApprovalRecord second = record(12L, 2, "leader", "waiting");
        when(approvalRecordMapper.selectById(11L)).thenReturn(first);
        when(approvalRecordMapper.selectList(any(Wrapper.class))).thenReturn(Arrays.asList(first, second));
        when(feishuUserResolveService.resolveFeishuUserId("leader")).thenReturn("ou_leader");

        ApprovalRecordDTO dto = service.approve(11L, comment("  同意  "), "jack");

        assertEquals("approved", first.getStatus());
        assertEquals("同意", first.getComment());
        assertTrue(first.getApproveTime() != null);
        assertEquals("pending", second.getStatus());
        assertEquals("ou_leader", second.getApproverFeishuId(), "轮到下一步时补上它的飞书账号");
        assertEquals("pending", task.getApprovalStatus(), "还有步骤没批完，任务仍是审批中");
        assertFalse(dto.getActionable(), "自己那一步已处理，不再可操作");
        assertEquals("订单上线", dto.getTaskName());
        verify(notifyService).notifyApproved(first, task, "jack");
        verify(notifyService).notifyPending(second, task);
    }

    @Test
    void approvingTheLastStepApprovesTheTaskButDoesNotDeploy() {
        ApprovalRecord last = record(12L, 2, "leader", "pending");
        when(approvalRecordMapper.selectById(12L)).thenReturn(last);
        when(approvalRecordMapper.selectList(any(Wrapper.class)))
                .thenReturn(Arrays.asList(record(11L, 1, "jack", "approved"), last));

        service.approve(12L, null, "leader");

        assertEquals("approved", task.getApprovalStatus());
        assertNull(last.getComment());
        verify(notifyService).notifyApproved(last, task, "leader");
        verify(notifyService, never()).notifyPending(any(), any());
        // 审批通过后由创建人点「发布」才开始部署
        verifyNoInteractions(deployTaskCdService);
    }

    @Test
    void onlyThePendingStepsApproverCanAct() {
        when(approvalRecordMapper.selectById(404L)).thenReturn(null);
        assertEquals("审批记录不存在", error(() -> service.approve(404L, null, "jack")));

        when(approvalRecordMapper.selectById(12L)).thenReturn(record(12L, 2, "leader", "waiting"));
        assertEquals("当前步骤不是待审批状态", error(() -> service.approve(12L, null, "leader")), "不能越过前面的步骤");

        when(approvalRecordMapper.selectById(11L)).thenReturn(record(11L, 1, "jack", "pending"));
        assertEquals("您不是当前步骤的审批人", error(() -> service.approve(11L, null, "leader")));
        assertEquals("您不是当前步骤的审批人", error(() -> service.approve(11L, null, "  ")));
        assertEquals("您不是当前步骤的审批人", error(() -> service.reject(11L, null, null)));
        verify(approvalRecordMapper, never()).updateById(any());
    }

    @Test
    void approverMatchingRules() {
        when(approvalRecordMapper.selectList(any(Wrapper.class))).thenReturn(Collections.emptyList());

        // 用户名不区分大小写
        when(approvalRecordMapper.selectById(11L)).thenReturn(record(11L, 1, "jack", "pending"));
        service.approve(11L, null, " JACK ");

        // 候选人任一人即可，名单外的人不行
        ApprovalRecord shared = record(12L, 1, null, "pending");
        shared.setApproverCandidates(",jack,leader,");
        when(approvalRecordMapper.selectById(12L)).thenReturn(shared);
        assertEquals("您不是当前步骤的审批人", error(() -> service.approve(12L, null, "bob")));
        service.approve(12L, null, "Leader");

        // 没有指定审批人的步骤，任何登录用户都能批（接口层另有 deploy:approve 权限把关）
        when(approvalRecordMapper.selectById(13L)).thenReturn(record(13L, 1, " ", "pending"));
        service.approve(13L, null, "anyone");

        // 从飞书卡片点进来的身份按飞书账号匹配
        ApprovalRecord viaFeishu = record(14L, 1, "jack", "pending");
        viaFeishu.setApproverFeishuId("ou_jack");
        when(approvalRecordMapper.selectById(14L)).thenReturn(viaFeishu);
        assertEquals("您不是当前步骤的审批人", error(() -> service.approve(14L, null, "feishu:ou_other")));
        service.approve(14L, null, "feishu:OU_JACK");

        verify(notifyService, times(4)).notifyApproved(any(), any(), any());
    }

    // ---------- 驳回 ----------

    @Test
    void rejectingCancelsRemainingStepsAndRejectsTheTask() {
        ApprovalRecord first = record(11L, 1, "jack", "pending");
        ApprovalRecord second = record(12L, 2, "leader", "waiting");
        when(approvalRecordMapper.selectById(11L)).thenReturn(first);
        when(approvalRecordMapper.selectList(any(Wrapper.class))).thenReturn(Collections.singletonList(second));

        ApprovalRecordDTO dto = service.reject(11L, comment(" 镜像版本不对 "), "jack");

        assertEquals("rejected", first.getStatus());
        assertEquals("镜像版本不对", first.getComment());
        assertEquals("cancelled", second.getStatus());
        assertEquals("rejected", task.getApprovalStatus());
        assertEquals("rejected", dto.getStatus());
        verify(notifyService).notifyRejected(first, task, "jack");
        verifyNoInteractions(deployTaskCdService);
    }

    @Test
    void actionFailsWhenTaskWasDeleted() {
        // 每次调用用一条新的待审批记录：生产环境里前一次失败会随事务回滚，这里用 mock 没有回滚
        when(approvalRecordMapper.selectById(11L))
                .thenReturn(record(11L, 1, "jack", "pending"))
                .thenReturn(record(11L, 1, "jack", "pending"));
        when(approvalRecordMapper.selectList(any(Wrapper.class))).thenReturn(Collections.emptyList());
        when(deployTaskMapper.selectById(1L)).thenReturn(null);
        assertEquals("关联任务不存在", error(() -> service.approve(11L, null, "jack")));
        assertEquals("关联任务不存在", error(() -> service.reject(11L, null, "jack")));
        verifyNoInteractions(notifyService);
    }

    // ---------- 解锁撤回 ----------

    @Test
    void cancelForEditClosesOpenStepsAndResetsTask() {
        ApprovalRecord pending = record(11L, 1, "jack", "pending");
        ApprovalRecord waiting = record(12L, 2, "leader", "waiting");
        when(approvalRecordMapper.selectList(any(Wrapper.class))).thenReturn(Arrays.asList(pending, waiting));
        task.setApprovalStatus("pending");

        service.cancelApprovalForEdit(task);

        assertEquals("cancelled", pending.getStatus());
        assertEquals("cancelled", waiting.getStatus());
        assertEquals("none", task.getApprovalStatus(), "回到未提审，可以重新编辑后再提交");
        verify(deployTaskMapper).updateById(task);
        verifyNoInteractions(notifyService);

        service.cancelApprovalForEdit(null);
        service.cancelApprovalForEdit(new DeployTask());
    }

    // ---------- 查询 ----------

    @Test
    void listByTaskMarksOnlyMyPendingStepAsActionable() {
        when(approvalRecordMapper.selectList(any(Wrapper.class))).thenReturn(Arrays.asList(
                record(11L, 1, "jack", "approved"), record(12L, 2, "leader", "pending"), record(13L, 3, "leader", "waiting")));

        List<ApprovalRecordDTO> forLeader = service.listByTask(1L, "leader");
        assertEquals(Arrays.asList(false, true, false),
                Arrays.asList(forLeader.get(0).getActionable(), forLeader.get(1).getActionable(), forLeader.get(2).getActionable()));

        List<ApprovalRecordDTO> forJack = service.listByTask(1L, "jack");
        assertFalse(forJack.get(1).getActionable());
    }

    @Test
    void myPendingListNeedsAUser() {
        assertTrue(service.listMyPending(null).isEmpty());
        assertTrue(service.listMyPending("  ").isEmpty());
        verify(approvalRecordMapper, never()).selectList(any(Wrapper.class));
    }

    @Test
    void myPendingListFiltersOutStepsOfOthersAndAddsTaskInfo() {
        when(approvalRecordMapper.selectList(any(Wrapper.class))).thenReturn(Arrays.asList(
                record(11L, 1, "jack", "pending"), record(12L, 1, "leader", "pending")));

        List<ApprovalRecordDTO> mine = service.listMyPending("jack");

        assertEquals(1, mine.size());
        assertEquals(11L, mine.get(0).getId());
        assertEquals("订单上线", mine.get(0).getTaskName());
        assertEquals("TASK-1", mine.get(0).getTaskNumber());
        assertTrue(mine.get(0).getActionable());
    }
}
