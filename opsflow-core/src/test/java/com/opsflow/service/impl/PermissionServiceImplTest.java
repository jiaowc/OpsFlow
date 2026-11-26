package com.opsflow.service.impl;

import com.opsflow.dao.mapper.UserRoleMapper;
import com.opsflow.service.PermissionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PermissionServiceImplTest {

    private PermissionServiceImpl service;
    private UserRoleMapper userRoleMapper;

    @BeforeEach
    void setUp() {
        service = new PermissionServiceImpl();
        userRoleMapper = mock(UserRoleMapper.class);
        ReflectionTestUtils.setField(service, "userRoleMapper", userRoleMapper);
    }

    // ---------- 权限匹配 ----------

    @Test
    void anyOfTheRequiredPermissionsIsEnough() {
        assertTrue(service.hasAnyPermission(Arrays.asList("deploy:view", "deploy:create"), "deploy:delete", "deploy:create"));
        assertFalse(service.hasAnyPermission(Arrays.asList("deploy:view"), "deploy:delete", "deploy:create"));
    }

    @Test
    void wildcardGrantsEverything() {
        assertTrue(service.hasAnyPermission(Collections.singletonList(PermissionService.WILDCARD), "anything:at-all"));
    }

    @Test
    void wildcardMustBeOwnedNotRequired() {
        // 接口声明需要 "*" 不等于谁都能进：只有拥有通配符的人才匹配
        assertFalse(service.hasAnyPermission(Arrays.asList("deploy:view"), PermissionService.WILDCARD));
    }

    @Test
    void noRequirementMeansAllowed() {
        assertTrue(service.hasAnyPermission(null));
        assertTrue(service.hasAnyPermission(Collections.emptyList()));
        assertTrue(service.hasAnyPermission(Collections.emptyList(), new String[0]));
    }

    @Test
    void nothingOwnedMeansDenied() {
        assertFalse(service.hasAnyPermission(null, "deploy:view"));
        assertFalse(service.hasAnyPermission(Collections.emptyList(), "deploy:view"));
    }

    @Test
    void matchingIsExactAndCaseSensitive() {
        assertFalse(service.hasAnyPermission(Arrays.asList("deploy:view"), "deploy"), "不做前缀匹配");
        assertFalse(service.hasAnyPermission(Arrays.asList("deploy:view"), "Deploy:View"));
        assertTrue(service.hasAnyPermission(Arrays.asList("deploy:view"), " deploy:view "), "声明里的首尾空格被忽略");
        assertFalse(service.hasAnyPermission(Arrays.asList("deploy:view"), "", null, "  "), "空白的声明不匹配任何权限");
    }

    // ---------- 从数据库加载 ----------

    @Test
    void permissionCodesAreTrimmedDeduplicatedAndKeepOrder() {
        when(userRoleMapper.selectPermissionCodesByUserId(7L))
                .thenReturn(Arrays.asList("deploy:view", " deploy:view ", null, "", "pipeline:run"));
        assertEquals(Arrays.asList("deploy:view", "pipeline:run"), service.listPermissionCodesByUserId(7L));
    }

    @Test
    void roleCodesAreTrimmedAndDeduplicated() {
        when(userRoleMapper.selectRoleCodesByUserId(7L)).thenReturn(Arrays.asList("ADMIN", "ADMIN ", "  "));
        assertEquals(Collections.singletonList("ADMIN"), service.listRoleCodesByUserId(7L));
    }

    @Test
    void missingUserOrEmptyResultYieldsEmptyList() {
        assertTrue(service.listPermissionCodesByUserId(null).isEmpty());
        assertTrue(service.listRoleCodesByUserId(null).isEmpty());
        verify(userRoleMapper, never()).selectPermissionCodesByUserId(null);
        when(userRoleMapper.selectPermissionCodesByUserId(8L)).thenReturn(null);
        assertTrue(service.listPermissionCodesByUserId(8L).isEmpty());
    }
}
