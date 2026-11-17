package com.opsflow.service.impl.deploy;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.opsflow.common.exception.BusinessException;
import com.opsflow.dao.mapper.DeployTaskMapper;
import com.opsflow.dao.model.DeployTask;
import com.opsflow.service.DeployTaskOperator;
import com.opsflow.service.ProjectAccessService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 上线任务查询的项目可见范围，以及项目级访问控制。
 * 这里用真实的 {@link DeployTaskAccessGuard}，两者一起验证。
 */
@SuppressWarnings("unchecked")
class DeployTaskQueryServiceTest {

    private static final DeployTaskOperator MEMBER = new DeployTaskOperator("alice", 7L, true, false, false, false);
    private static final DeployTaskOperator APPROVER = new DeployTaskOperator("carol", 9L, false, false, false, true);

    private DeployTaskQueryService service;
    private DeployTaskAccessGuard guard;
    private DeployTaskMapper deployTaskMapper;
    private ProjectAccessService projectAccessService;

    @BeforeEach
    void setUp() {
        deployTaskMapper = mock(DeployTaskMapper.class);
        projectAccessService = mock(ProjectAccessService.class);
        guard = new DeployTaskAccessGuard();
        ReflectionTestUtils.setField(guard, "deployTaskMapper", deployTaskMapper);
        ReflectionTestUtils.setField(guard, "projectAccessService", projectAccessService);
        service = new DeployTaskQueryService();
        ReflectionTestUtils.setField(service, "deployTaskMapper", deployTaskMapper);
        ReflectionTestUtils.setField(service, "projectAccessService", projectAccessService);
        ReflectionTestUtils.setField(service, "deployTaskAccessGuard", guard);
        when(deployTaskMapper.selectList(any(Wrapper.class))).thenReturn(Collections.emptyList());
    }

    private QueryWrapper<DeployTask> capturedWrapper() {
        ArgumentCaptor<Wrapper<DeployTask>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(deployTaskMapper).selectList(captor.capture());
        return (QueryWrapper<DeployTask>) captor.getValue();
    }

    private static String sql(QueryWrapper<DeployTask> wrapper) {
        return wrapper.getSqlSegment().replaceAll("#\\{[^}]*}", "?").replaceAll("\\s+", " ").trim();
    }

    // ---------- 列表 ----------

    @Test
    void listWithProjectChecksThatProjectIsViewable() {
        service.list(null, 3L, MEMBER);

        verify(projectAccessService).assertCanViewProject(3L);
        QueryWrapper<DeployTask> wrapper = capturedWrapper();
        assertEquals("(project_id = ?) ORDER BY create_time DESC", sql(wrapper));
        assertTrue(wrapper.getParamNameValuePairs().containsValue(3L));
    }

    @Test
    void listWithProjectIsRefusedWhenProjectIsNotViewable() {
        doThrow(new BusinessException("无权查看该项目")).when(projectAccessService).assertCanViewProject(3L);
        assertThrows(BusinessException.class, () -> service.list(null, 3L, MEMBER));
        verify(deployTaskMapper, never()).selectList(any(Wrapper.class));
    }

    @Test
    void memberSeesAccessibleProjectsPlusLegacyTasksWithoutProject() {
        when(projectAccessService.accessibleProjectIds()).thenReturn(new LinkedHashSet<>(Arrays.asList(1L, 2L)));
        service.list(null, null, MEMBER);
        assertEquals("((project_id IS NULL OR project_id IN (?,?))) ORDER BY create_time DESC", sql(capturedWrapper()));
    }

    @Test
    void memberWithoutAnyProjectOnlySeesLegacyTasks() {
        when(projectAccessService.accessibleProjectIds()).thenReturn(new HashSet<>());
        service.list(null, null, MEMBER);
        assertEquals("((project_id IS NULL)) ORDER BY create_time DESC", sql(capturedWrapper()));
    }

    @Test
    void approverAndPlatformAdminSeeAllProjects() {
        service.list("pending", null, APPROVER);
        QueryWrapper<DeployTask> wrapper = capturedWrapper();
        assertEquals("(task_status = ?) ORDER BY create_time DESC", sql(wrapper));
        assertTrue(wrapper.getParamNameValuePairs().containsValue("pending"));
        verify(projectAccessService, never()).accessibleProjectIds();
    }

    @Test
    void platformAdminIsNotFilteredByProject() {
        when(projectAccessService.isPlatformAdmin()).thenReturn(true);
        service.list(null, null, MEMBER);
        assertEquals("ORDER BY create_time DESC", sql(capturedWrapper()));
    }

    // ---------- 详情 ----------

    @Test
    void getReturnsNullWhenTaskIsMissing() {
        assertNull(service.get(404L, MEMBER));
    }

    @Test
    void getChecksProjectOnlyForTasksThatBelongToOne() {
        DeployTask legacy = new DeployTask();
        when(deployTaskMapper.selectById(1L)).thenReturn(legacy);
        assertSame(legacy, service.get(1L, MEMBER));
        verify(projectAccessService, never()).assertCanViewProject(any());

        DeployTask inProject = new DeployTask();
        inProject.setProjectId(3L);
        when(deployTaskMapper.selectById(2L)).thenReturn(inProject);
        assertSame(inProject, service.get(2L, MEMBER));
        verify(projectAccessService).assertCanViewProject(3L);
    }

    @Test
    void approverCanOpenTaskOfAnyProject() {
        DeployTask inProject = new DeployTask();
        inProject.setProjectId(3L);
        when(deployTaskMapper.selectById(2L)).thenReturn(inProject);
        assertSame(inProject, service.get(2L, APPROVER));
        verify(projectAccessService, never()).assertCanViewProject(any());
    }

    // ---------- 访问控制 ----------

    @Test
    void canViewAllOnlyForPlatformAdminOrApprover() {
        assertFalse(guard.canViewAllTasks(MEMBER));
        assertTrue(guard.canViewAllTasks(APPROVER));
        when(projectAccessService.isPlatformAdmin()).thenReturn(true);
        assertTrue(guard.canViewAllTasks(MEMBER));
    }

    @Test
    void operableCheckLoadsTaskAndSkipsLegacyTasks() {
        assertEquals("任务不存在",
                assertThrows(BusinessException.class, () -> guard.assertTaskOperable(404L)).getMessage());

        when(deployTaskMapper.selectById(1L)).thenReturn(new DeployTask());
        guard.assertTaskOperable(1L);
        verify(projectAccessService, never()).assertCanOperateProject(any());

        DeployTask inProject = new DeployTask();
        inProject.setProjectId(3L);
        when(deployTaskMapper.selectById(2L)).thenReturn(inProject);
        guard.assertTaskOperable(2L);
        verify(projectAccessService).assertCanOperateProject(3L);
    }
}
