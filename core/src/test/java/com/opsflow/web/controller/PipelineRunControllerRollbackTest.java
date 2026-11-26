package com.opsflow.web.controller;

import com.opsflow.api.dto.BuildResponse;
import com.opsflow.api.dto.RollbackRequest;
import com.opsflow.common.exception.BusinessException;
import com.opsflow.service.PermissionService;
import com.opsflow.service.PipelineRunService;
import com.opsflow.web.security.SessionPermissionResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collection;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 生产回滚已放回社区版：不再要求 License，只看「回滚部署」权限。
 */
class PipelineRunControllerRollbackTest {

    private PipelineRunController controller;
    private PipelineRunService runService;
    private SessionPermissionResolver resolver;
    private final MockHttpSession session = new MockHttpSession();

    @BeforeEach
    void setUp() {
        controller = new PipelineRunController();
        runService = mock(PipelineRunService.class);
        resolver = mock(SessionPermissionResolver.class);
        ReflectionTestUtils.setField(controller, "pipelineRunService", runService);
        ReflectionTestUtils.setField(controller, "sessionPermissionResolver", resolver);
        ReflectionTestUtils.setField(controller, "permissionService", new SimplePermissionService());
    }

    private void owns(String... permissions) {
        List<String> list = java.util.Arrays.asList(permissions);
        when(resolver.resolvePermissions(session)).thenReturn(list);
    }

    @Test
    void controllerNoLongerDependsOnLicense() {
        for (java.lang.reflect.Field f : PipelineRunController.class.getDeclaredFields()) {
            assertTrue(!f.getType().getSimpleName().contains("License"), f.getName());
        }
    }

    @Test
    void prodRollbackWithRollbackPermissionNeedsNoLicense() {
        owns("pipeline:rollback");
        when(runService.isProdEnvJob(7L)).thenReturn(true);
        BuildResponse expected = new BuildResponse();
        RollbackRequest request = new RollbackRequest();
        when(runService.rollbackPipelineJob(7L, request)).thenReturn(expected);

        assertSame(expected, controller.rollbackJob(7L, request, session));
    }

    @Test
    void prodRollbackWithOnlyNonprodPermissionIsRejected() {
        owns("pipeline:rollback:nonprod");
        when(runService.isProdEnvJob(7L)).thenReturn(true);

        BusinessException e = assertThrows(BusinessException.class,
                () -> controller.rollbackJob(7L, new RollbackRequest(), session));
        assertTrue(e.getMessage().contains("回滚部署"));
        verify(runService, never()).rollbackPipelineJob(anyLong(), any());
    }

    @Test
    void nonprodRollbackWithNonprodPermissionIsAllowed() {
        owns("pipeline:rollback:nonprod");
        when(runService.isProdEnvJob(8L)).thenReturn(false);
        RollbackRequest request = new RollbackRequest();

        controller.rollbackJob(8L, request, session);

        verify(runService).rollbackPipelineJob(8L, request);
    }

    @Test
    void rollbackWithoutAnyRollbackPermissionIsRejected() {
        owns("pipeline:view");
        when(runService.isProdEnvJob(8L)).thenReturn(false);

        assertThrows(BusinessException.class, () -> controller.rollbackJob(8L, new RollbackRequest(), session));
        verify(runService, never()).rollbackPipelineJob(anyLong(), any());
    }

    /** 只实现 hasAnyPermission，其余方法本测试用不到 */
    private static class SimplePermissionService implements PermissionService {
        @Override
        public List<String> listPermissionCodesByUserId(Long userId) {
            return Collections.emptyList();
        }

        @Override
        public List<String> listRoleCodesByUserId(Long userId) {
            return Collections.emptyList();
        }

        @Override
        public boolean hasAnyPermission(Collection<String> owned, String... required) {
            for (String r : required) {
                if (owned.contains(r) || owned.contains(WILDCARD)) {
                    return true;
                }
            }
            return false;
        }
    }
}
