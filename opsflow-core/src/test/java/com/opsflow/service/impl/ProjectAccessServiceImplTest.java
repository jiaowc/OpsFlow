package com.opsflow.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.opsflow.common.exception.BusinessException;
import com.opsflow.dao.mapper.ProjectMemberMapper;
import com.opsflow.dao.mapper.ServiceMapper;
import com.opsflow.dao.mapper.UserMapper;
import com.opsflow.dao.mapper.UserRoleMapper;
import com.opsflow.dao.model.ProjectMember;
import com.opsflow.dao.model.Service;
import com.opsflow.service.ProjectAccessService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 项目隔离：平台管理员不受限，其余人按项目成员角色（ADMIN / DEVELOPER / VIEWER）决定能看、能操作什么。
 */
@SuppressWarnings("unchecked")
class ProjectAccessServiceImplTest {

    private ProjectAccessServiceImpl service;
    private ProjectMemberMapper projectMemberMapper;
    private ServiceMapper serviceMapper;

    @BeforeAll
    static void registerTableInfo() {
        // 生产代码用了 LambdaQueryWrapper.select(ProjectMember::getProjectId)，脱离 Spring 时需要手工注册实体的表信息
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), ProjectMember.class);
    }

    @BeforeEach
    void setUp() {
        service = new ProjectAccessServiceImpl();
        projectMemberMapper = mock(ProjectMemberMapper.class);
        serviceMapper = mock(ServiceMapper.class);
        PermissionServiceImpl permissionService = new PermissionServiceImpl();
        ReflectionTestUtils.setField(permissionService, "userRoleMapper", mock(UserRoleMapper.class));
        ReflectionTestUtils.setField(service, "projectMemberMapper", projectMemberMapper);
        ReflectionTestUtils.setField(service, "serviceMapper", serviceMapper);
        ReflectionTestUtils.setField(service, "userMapper", mock(UserMapper.class));
        ReflectionTestUtils.setField(service, "permissionService", permissionService);
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }

    /** 以某个登录用户的身份发起请求 */
    private void loginAs(Long userId, String... permissions) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.getSession(true).setAttribute("userId", userId);
        request.getSession().setAttribute("permissions", Arrays.asList(permissions));
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    private void memberOf(String role) {
        ProjectMember member = new ProjectMember();
        member.setRole(role);
        when(projectMemberMapper.selectOne(any(Wrapper.class))).thenReturn(member);
    }

    private void serviceInProject(Long serviceId, Long projectId) {
        Service s = new Service();
        s.setId(serviceId);
        s.setProjectId(projectId);
        when(serviceMapper.selectById(serviceId)).thenReturn(s);
    }

    // ---------- 谁是平台管理员 ----------

    @Test
    void platformPermissionOrWildcardMakesPlatformAdmin() {
        loginAs(7L, ProjectAccessService.PLATFORM_PERMISSION);
        assertTrue(service.isPlatformAdmin());
        loginAs(7L, "*");
        assertTrue(service.isPlatformAdmin());
        loginAs(7L, "deploy:view", "pipeline:run");
        assertFalse(service.isPlatformAdmin());
    }

    @Test
    void backgroundThreadsWithoutRequestAreNotRestricted() {
        // 定时任务、流水线执行线程没有请求上下文，按系统身份处理
        RequestContextHolder.resetRequestAttributes();
        assertTrue(service.isPlatformAdmin());
        assertNull(service.accessibleProjectIds(), "null 表示不限制");
        assertEquals(ProjectMember.ROLE_ADMIN, service.roleOf(1L));
    }

    @Test
    void requestWithoutSessionHasNoAccess() {
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
        assertFalse(service.isPlatformAdmin());
        assertNull(service.roleOf(1L));
        assertTrue(service.accessibleProjectIds().isEmpty());
    }

    // ---------- 项目级 ----------

    @Test
    void platformAdminIsAdminOfEveryProject() {
        loginAs(7L, "*");
        assertEquals(ProjectMember.ROLE_ADMIN, service.roleOf(99L));
        assertNull(service.accessibleProjectIds());
        assertDoesNotThrow(() -> service.assertProjectAdmin(99L));
    }

    @Test
    void nonMemberCannotViewOrOperate() {
        loginAs(7L, "deploy:view");
        when(projectMemberMapper.selectOne(any(Wrapper.class))).thenReturn(null);
        assertEquals("无权访问该项目，请联系项目管理员添加成员",
                assertThrows(BusinessException.class, () -> service.assertCanViewProject(1L)).getMessage());
        assertEquals("无权访问该项目，请联系项目管理员添加成员",
                assertThrows(BusinessException.class, () -> service.assertCanOperateProject(1L)).getMessage());
        assertEquals("仅项目管理员可以执行此操作",
                assertThrows(BusinessException.class, () -> service.assertProjectAdmin(1L)).getMessage());
    }

    @Test
    void viewerCanViewButNotOperate() {
        loginAs(7L, "deploy:view");
        memberOf(ProjectMember.ROLE_VIEWER);
        assertDoesNotThrow(() -> service.assertCanViewProject(1L));
        assertEquals("你在该项目中是只读成员，不能执行此操作",
                assertThrows(BusinessException.class, () -> service.assertCanOperateProject(1L)).getMessage());
        assertThrows(BusinessException.class, () -> service.assertProjectAdmin(1L));
    }

    @Test
    void developerCanOperateButIsNotProjectAdmin() {
        loginAs(7L, "deploy:view");
        memberOf(ProjectMember.ROLE_DEVELOPER);
        assertDoesNotThrow(() -> service.assertCanOperateProject(1L));
        assertThrows(BusinessException.class, () -> service.assertProjectAdmin(1L));
    }

    @Test
    void projectAdminCanDoEverythingInTheProject() {
        loginAs(7L, "deploy:view");
        memberOf(ProjectMember.ROLE_ADMIN);
        assertDoesNotThrow(() -> service.assertCanOperateProject(1L));
        assertDoesNotThrow(() -> service.assertProjectAdmin(1L));
    }

    @Test
    void accessibleProjectsComeFromMemberships() {
        loginAs(7L, "deploy:view");
        ProjectMember a = new ProjectMember();
        a.setProjectId(3L);
        ProjectMember b = new ProjectMember();
        b.setProjectId(5L);
        when(projectMemberMapper.selectList(any(Wrapper.class))).thenReturn(Arrays.asList(a, b));
        assertEquals(new HashSet<>(Arrays.asList(3L, 5L)), service.accessibleProjectIds());
    }

    // ---------- 服务级 ----------

    @Test
    void servicesWithoutProjectAreOpenToEveryone() {
        // 历史数据：服务没有归属项目时不做项目校验
        loginAs(7L, "deploy:view");
        serviceInProject(10L, null);
        assertTrue(service.canViewService(10L));
        assertTrue(service.canOperateService(10L));
        assertDoesNotThrow(() -> service.assertCanOperateService(10L));
        assertTrue(service.canViewService(404L), "服务不存在时同样不拦，由调用方报不存在");
    }

    @Test
    void serviceAccessFollowsProjectRole() {
        loginAs(7L, "deploy:view");
        serviceInProject(10L, 1L);

        when(projectMemberMapper.selectOne(any(Wrapper.class))).thenReturn(null);
        assertFalse(service.canViewService(10L));
        assertFalse(service.canOperateService(10L));
        assertEquals("无权访问该服务所属项目，请联系项目管理员添加成员",
                assertThrows(BusinessException.class, () -> service.assertCanViewService(10L)).getMessage());

        memberOf(ProjectMember.ROLE_VIEWER);
        assertTrue(service.canViewService(10L));
        assertFalse(service.canOperateService(10L));
        assertThrows(BusinessException.class, () -> service.assertCanOperateService(10L));

        memberOf(ProjectMember.ROLE_DEVELOPER);
        assertTrue(service.canOperateService(10L));
    }

    @Test
    void accessibleServicesAreLimitedToAccessibleProjects() {
        loginAs(7L, "deploy:view");
        when(projectMemberMapper.selectList(any(Wrapper.class))).thenReturn(Collections.emptyList());
        assertTrue(service.accessibleServiceIds(null).isEmpty(), "不属于任何项目时看不到任何服务");

        loginAs(7L, "*");
        assertNull(service.accessibleServiceIds(null), "平台管理员不限制");

        loginAs(7L, "deploy:view");
        when(projectMemberMapper.selectOne(any(Wrapper.class))).thenReturn(null);
        assertThrows(BusinessException.class, () -> service.accessibleServiceIds(1L), "指定了无权访问的项目时直接拒绝");
    }
}
