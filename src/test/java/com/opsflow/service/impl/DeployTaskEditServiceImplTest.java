package com.opsflow.service.impl;

import com.opsflow.api.dto.DeployModuleItemDTO;
import com.opsflow.api.dto.DeployTaskDTO;
import com.opsflow.common.exception.BusinessException;
import com.opsflow.common.util.DeployModuleJson;
import com.opsflow.dao.mapper.DeployTaskMapper;
import com.opsflow.dao.model.DeployTask;
import com.opsflow.service.ApprovalService;
import com.opsflow.service.DeployTaskCdService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 上线任务的协作编辑：多人各管各的模块 → 创建人锁定 → 提审 → 审批通过后发布。
 * 状态规则直接决定谁在什么时候能点哪个按钮，这里按业务规则逐条固定下来。
 */
class DeployTaskEditServiceImplTest {

    private static final String A1 = "harbor/prod/order:1.0";
    private static final String B1 = "harbor/prod/pay:1.0";

    private DeployTaskEditServiceImpl service;
    private DeployTaskMapper deployTaskMapper;
    private ApprovalService approvalService;
    private DeployTaskCdService deployTaskCdService;
    private DeployTask task;

    @BeforeEach
    void setUp() {
        service = new DeployTaskEditServiceImpl();
        deployTaskMapper = mock(DeployTaskMapper.class);
        approvalService = mock(ApprovalService.class);
        deployTaskCdService = mock(DeployTaskCdService.class);
        ReflectionTestUtils.setField(service, "deployTaskMapper", deployTaskMapper);
        ReflectionTestUtils.setField(service, "approvalService", approvalService);
        ReflectionTestUtils.setField(service, "deployTaskCdService", deployTaskCdService);

        // 默认：alice(7) 创建的任务，里面有 alice 的一个模块和 bob(8) 的一个模块，未锁定、未提审
        task = new DeployTask();
        task.setId(1L);
        task.setCreatorId(7L);
        task.setCreatorName("alice");
        task.setLocked(0);
        task.setApprovalStatus("none");
        task.setTaskStatus("pending");
        task.setApprovalFlowId(3L);
        task.setDeployModules(DeployModuleJson.writeItems(Arrays.asList(item(A1, "alice", 7L), item(B1, "bob", 8L))));
        when(deployTaskMapper.selectById(1L)).thenReturn(task);
    }

    private static DeployModuleItemDTO item(String image, String owner, Long ownerId) {
        DeployModuleItemDTO dto = new DeployModuleItemDTO();
        dto.setImage(image);
        dto.setOwnerName(owner);
        dto.setOwnerId(ownerId);
        return dto;
    }

    private static String describe(List<DeployModuleItemDTO> items) {
        return items.stream().map(i -> i.getImage() + "@" + i.getOwnerName()).collect(Collectors.joining(", "));
    }

    private String error(Runnable action) {
        return assertThrows(BusinessException.class, action::run).getMessage();
    }

    // ---------- 创建人判断 ----------

    @Test
    void creatorIsMatchedByIdOrByNameIgnoringCase() {
        assertTrue(service.isCreator(task, "someone-else", 7L), "ID 相同即可，哪怕用户名改过");
        assertTrue(service.isCreator(task, " ALICE ", null), "历史任务没有 creatorId 时按用户名");
        assertFalse(service.isCreator(task, "bob", 8L));
        assertFalse(service.isCreator(task, null, null));
        assertFalse(service.isCreator(null, "alice", 7L));
    }

    // ---------- 能否编辑 ----------

    @Test
    void editableOnlyBeforeLockApprovalAndDeploy() {
        assertDoesNotThrow(() -> service.assertEditable(task));

        task.setLocked(1);
        assertEquals("任务已锁定，请先解锁后再编辑", error(() -> service.assertEditable(task)));
        task.setLocked(0);

        for (String approval : new String[]{"pending", " Approved "}) {
            task.setApprovalStatus(approval);
            assertEquals("任务已进入审批或发布流程，无法编辑", error(() -> service.assertEditable(task)));
        }
        task.setApprovalStatus("rejected");
        assertDoesNotThrow(() -> service.assertEditable(task), "被驳回后可以修改再提审");

        for (String status : new String[]{"deploying", "building", "success"}) {
            task.setTaskStatus(status);
            assertEquals("任务已在部署中或已结束，无法编辑", error(() -> service.assertEditable(task)));
        }
        task.setTaskStatus("failed");
        assertDoesNotThrow(() -> service.assertEditable(task));
        assertEquals("任务不存在", error(() -> service.assertEditable(null)));
    }

    // ---------- 按归属合并模块 ----------

    @Test
    void memberCanAddOwnModuleWhileKeepingOthers() {
        List<DeployModuleItemDTO> merged = service.mergeModulesByOwnership(task, null,
                Arrays.asList(A1, B1, "harbor/prod/stock:2.0"), "bob", 8L);
        assertEquals(A1 + "@alice, " + B1 + "@bob, harbor/prod/stock:2.0@bob", describe(merged));
    }

    @Test
    void memberCanReplaceOrRemoveOnlyOwnModules() {
        // bob 把自己的 pay 升到 1.1：旧版本消失、新版本归 bob；alice 的模块原样保留
        List<DeployModuleItemDTO> merged = service.mergeModulesByOwnership(task, null,
                Arrays.asList(A1, "harbor/prod/pay:1.1"), "bob", 8L);
        assertEquals(A1 + "@alice, harbor/prod/pay:1.1@bob", describe(merged));
    }

    @Test
    void cannotRemoveOrChangeModulesOfOthers() {
        assertEquals("不能删除他人添加的模块: " + A1,
                error(() -> service.mergeModulesByOwnership(task, null, Collections.singletonList(B1), "bob", 8L)));
        // 改别人模块的版本，等同于删掉原来那条
        assertEquals("不能删除他人添加的模块: " + A1,
                error(() -> service.mergeModulesByOwnership(task, null,
                        Arrays.asList("harbor/prod/order:9.9", B1), "bob", 8L)));
    }

    @Test
    void resubmittingOthersModuleDoesNotTransferOwnership() {
        // 前端会把整张清单提交回来，其中别人的模块必须保持原归属
        List<DeployModuleItemDTO> merged = service.mergeModulesByOwnership(task,
                Arrays.asList(item(A1, "bob", 8L), item(B1, "bob", 8L)), null, "bob", 8L);
        assertEquals(A1 + "@alice, " + B1 + "@bob", describe(merged));
    }

    @Test
    void imageComparisonIgnoresCaseAndSpaces() {
        List<DeployModuleItemDTO> merged = service.mergeModulesByOwnership(task, null,
                Arrays.asList("  HARBOR/prod/Order:1.0 ", B1), "bob", 8L);
        assertEquals(A1 + "@alice, " + B1 + "@bob", describe(merged), "别人的模块按原样保留，不采用请求里的写法");
    }

    @Test
    void duplicateModulesAreRejected() {
        assertEquals("模块重复: " + B1.toUpperCase(),
                error(() -> service.mergeModulesByOwnership(task, null, Arrays.asList(A1, B1, B1.toUpperCase()), "bob", 8L)));
    }

    @Test
    void legacyModulesWithoutOwnerBelongToCreator() {
        task.setDeployModules("[\"" + A1 + "\", \"" + B1 + "\"]");
        // 历史数据没有归属人：都算创建人 alice 的，bob 不能动
        assertTrue(error(() -> service.mergeModulesByOwnership(task, null, Collections.singletonList(A1), "bob", 8L))
                .startsWith("不能删除他人添加的模块"));
        // alice 自己可以删
        assertEquals(A1 + "@alice",
                describe(service.mergeModulesByOwnership(task, null, Collections.singletonList(A1), "alice", 7L)));
    }

    @Test
    void mergeRequiresAModuleList() {
        assertEquals("模块列表不能为空", error(() -> service.mergeModulesByOwnership(task, null, null, "alice", 7L)));
        task.setDeployModules(DeployModuleJson.writeItems(Collections.singletonList(item(A1, "alice", 7L))));
        assertEquals("请至少保留一个上线模块",
                error(() -> service.mergeModulesByOwnership(task, null, Collections.emptyList(), "alice", 7L)));
    }

    // ---------- 锁定 ----------

    @Test
    void creatorLocksTask() {
        DeployTask locked = service.lock(1L, "alice", 7L, false);
        assertEquals(1, locked.getLocked());
        assertEquals("alice", locked.getLockedBy());
        assertTrue(locked.getLockedAt() != null);
        verify(deployTaskMapper).updateById(task);
    }

    @Test
    void lockRules() {
        assertEquals("仅创建人或有解锁权限的人可锁定任务", error(() -> service.lock(1L, "bob", 8L, false)));
        assertDoesNotThrow(() -> service.lock(1L, "bob", 8L, true));
        assertEquals("任务已锁定", error(() -> service.lock(1L, "alice", 7L, false)));

        task.setLocked(0);
        task.setApprovalStatus("pending");
        assertEquals("任务已进入审批或发布，无需再次锁定", error(() -> service.lock(1L, "alice", 7L, false)));

        task.setApprovalStatus("none");
        task.setTaskStatus("success");
        assertEquals("任务已在部署中或已结束，无法锁定", error(() -> service.lock(1L, "alice", 7L, false)));

        task.setTaskStatus("pending");
        task.setDeployModules("[]");
        assertEquals("请先添加上线模块再锁定", error(() -> service.lock(1L, "alice", 7L, false)));

        assertEquals("任务不存在", error(() -> service.lock(404L, "alice", 7L, false)));
    }

    // ---------- 解锁 ----------

    @Test
    void unlockBeforeApprovalJustReleasesTheLock() {
        task.setLocked(1);
        task.setLockedBy("alice");
        DeployTask unlocked = service.unlock(1L, "alice", 7L, false);
        assertEquals(0, unlocked.getLocked());
        assertNull(unlocked.getLockedBy());
        verify(approvalService, never()).cancelApprovalForEdit(any());
    }

    @Test
    void unlockDuringOrAfterApprovalCancelsTheApproval() {
        for (String approval : new String[]{"pending", "rejected", "approved"}) {
            task.setLocked(1);
            task.setApprovalStatus(approval);
            task.setTaskStatus("pending");
            service.unlock(1L, "alice", 7L, false);
        }
        verify(approvalService, org.mockito.Mockito.times(3)).cancelApprovalForEdit(task);
    }

    @Test
    void unlockAfterFailedDeployResetsTaskToPending() {
        task.setLocked(1);
        task.setApprovalStatus("approved");
        task.setTaskStatus("failed");
        assertEquals("pending", service.unlock(1L, "alice", 7L, false).getTaskStatus(), "失败后解锁，任务回到可编辑的待发布状态");
    }

    @Test
    void unlockRules() {
        task.setLocked(1);
        assertEquals("无权解锁：仅创建人或拥有 deploy:unlock 权限的人可解除锁定", error(() -> service.unlock(1L, "bob", 8L, false)));
        assertDoesNotThrow(() -> service.unlock(1L, "bob", 8L, true));
        assertEquals("任务未锁定", error(() -> service.unlock(1L, "alice", 7L, false)));

        for (String status : new String[]{"deploying", "building", "success"}) {
            task.setLocked(1);
            task.setTaskStatus(status);
            assertEquals("任务已在部署中或已发布成功，无法解锁", error(() -> service.unlock(1L, "alice", 7L, false)));
        }
    }

    // ---------- 提交审批 ----------

    @Test
    void submitApprovalStartsTheFlow() {
        task.setLocked(1);
        service.submitApproval(1L, "alice", 7L, false);
        verify(approvalService).startApproval(task);
    }

    @Test
    void submitApprovalRules() {
        assertEquals("请先锁定任务后再提交审批", error(() -> service.submitApproval(1L, "alice", 7L, false)));
        task.setLocked(1);
        assertEquals("仅创建人或有权限的人可提交审批", error(() -> service.submitApproval(1L, "bob", 8L, false)));

        task.setApprovalStatus("pending");
        assertEquals("任务已在审批中", error(() -> service.submitApproval(1L, "alice", 7L, false)));
        task.setApprovalStatus("approved");
        assertEquals("任务已审批通过，请点击「发布」开始部署", error(() -> service.submitApproval(1L, "alice", 7L, false)));

        task.setApprovalStatus("rejected");
        task.setTaskStatus("deploying");
        assertEquals("任务已在部署中或已结束", error(() -> service.submitApproval(1L, "alice", 7L, false)));

        task.setTaskStatus("pending");
        task.setApprovalFlowId(null);
        assertEquals("请先配置审批流", error(() -> service.submitApproval(1L, "alice", 7L, false)));

        task.setApprovalFlowId(3L);
        task.setDeployModules("[]");
        assertEquals("请至少添加一个上线模块", error(() -> service.submitApproval(1L, "alice", 7L, false)));
        verify(approvalService, never()).startApproval(any());
    }

    // ---------- 发布 ----------

    @Test
    void publishStartsCdOnlyAfterApproval() {
        task.setLocked(1);
        assertEquals("审批通过后才能发布", error(() -> service.publish(1L, "alice", 7L, false)));
        task.setApprovalStatus("pending");
        assertEquals("审批通过后才能发布", error(() -> service.publish(1L, "alice", 7L, false)));
        verify(deployTaskCdService, never()).startCdAfterApproved(any());

        task.setApprovalStatus("approved");
        service.publish(1L, "alice", 7L, false);
        verify(deployTaskCdService).startCdAfterApproved(task);
    }

    @Test
    void publishRules() {
        task.setApprovalStatus("approved");
        assertEquals("任务未锁定，无法发布", error(() -> service.publish(1L, "alice", 7L, false)));
        task.setLocked(1);
        assertEquals("仅创建人或有权限的人可发布任务", error(() -> service.publish(1L, "bob", 8L, false)));

        task.setTaskStatus("deploying");
        assertEquals("任务已在发布中", error(() -> service.publish(1L, "alice", 7L, false)));
        task.setTaskStatus("success");
        assertEquals("任务已发布成功", error(() -> service.publish(1L, "alice", 7L, false)));

        task.setTaskStatus("failed");
        assertDoesNotThrow(() -> service.publish(1L, "alice", 7L, false), "发布失败后可以重新发布");
    }

    // ---------- 页面按钮（操作标志） ----------

    private DeployTaskDTO flags(String username, Long userId, boolean hasCreate, boolean hasUnlock, boolean hasDelete) {
        DeployTaskDTO dto = new DeployTaskDTO();
        service.fillActionFlags(dto, task, username, userId, hasCreate, hasUnlock, hasDelete);
        return dto;
    }

    private static String buttons(DeployTaskDTO dto) {
        StringBuilder sb = new StringBuilder();
        if (Boolean.TRUE.equals(dto.getCanEdit())) sb.append("edit ");
        if (Boolean.TRUE.equals(dto.getCanLock())) sb.append("lock ");
        if (Boolean.TRUE.equals(dto.getCanUnlock())) sb.append("unlock ");
        if (Boolean.TRUE.equals(dto.getCanSubmitApproval())) sb.append("submit ");
        if (Boolean.TRUE.equals(dto.getCanPublish())) sb.append("publish ");
        if (Boolean.TRUE.equals(dto.getCanDelete())) sb.append("delete ");
        return sb.toString().trim();
    }

    @Test
    void buttonsFollowTheLifecycleForCreator() {
        assertEquals("edit lock delete", buttons(flags("alice", 7L, true, false, true)), "协作编辑中");

        task.setLocked(1);
        assertEquals("unlock submit delete", buttons(flags("alice", 7L, true, false, true)), "已锁定，待提审");

        task.setApprovalStatus("pending");
        assertEquals("unlock delete", buttons(flags("alice", 7L, true, false, true)), "审批中：只能解锁撤回");

        task.setApprovalStatus("rejected");
        assertEquals("unlock submit delete", buttons(flags("alice", 7L, true, false, true)), "被驳回：可重新提审或解锁修改");

        task.setApprovalStatus("approved");
        assertEquals("unlock publish delete", buttons(flags("alice", 7L, true, false, true)), "审批通过，待发布");

        task.setTaskStatus("deploying");
        assertEquals("", buttons(flags("alice", 7L, true, false, true)), "发布中：什么都不能点，包括删除");

        task.setTaskStatus("failed");
        assertEquals("unlock publish delete", buttons(flags("alice", 7L, true, false, true)), "发布失败：可重发或解锁修改");

        task.setTaskStatus("success");
        assertEquals("delete", buttons(flags("alice", 7L, true, false, true)), "发布成功：只读");
    }

    @Test
    void collaboratorCanEditButNotDriveTheFlow() {
        assertEquals("edit", buttons(flags("bob", 8L, true, false, false)), "协作者能编辑自己的模块，但不能锁定");
        task.setLocked(1);
        assertEquals("", buttons(flags("bob", 8L, true, false, false)));
        task.setApprovalStatus("approved");
        assertEquals("", buttons(flags("bob", 8L, true, false, false)));
    }

    @Test
    void unlockPermissionActsLikeCreatorForFlowButtons() {
        assertEquals("edit lock", buttons(flags("ops", 9L, true, true, false)));
        task.setLocked(1);
        assertEquals("unlock submit", buttons(flags("ops", 9L, true, true, false)));
    }

    @Test
    void lockAndSubmitNeedAtLeastOneModule() {
        task.setDeployModules("[]");
        assertEquals("edit delete", buttons(flags("alice", 7L, true, false, true)));
        task.setLocked(1);
        assertEquals("unlock delete", buttons(flags("alice", 7L, true, false, true)));
    }

    @Test
    void lockStateIsCopiedToDto() {
        task.setLocked(1);
        task.setLockedBy("alice");
        DeployTaskDTO dto = flags("alice", 7L, true, false, true);
        assertEquals(1, dto.getLocked());
        assertEquals("alice", dto.getLockedBy());
        assertTrue(dto.getShowPublish());
        assertFalse(flags("viewer", 99L, false, false, false).getShowPublish(), "只读用户不显示发布入口");
    }
}
