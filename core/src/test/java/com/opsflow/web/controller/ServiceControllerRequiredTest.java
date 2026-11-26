package com.opsflow.web.controller;

import com.opsflow.api.dto.ServiceDTO;
import com.opsflow.common.exception.BusinessException;
import com.opsflow.dao.mapper.ProjectMapper;
import com.opsflow.dao.mapper.ServiceMapper;
import com.opsflow.dao.model.Project;
import com.opsflow.dao.model.Service;
import com.opsflow.support.MockInjector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 服务表单：名称、服务类型、Git 组件必填（以前名称可以为空，服务类型不填默认 backend） */
class ServiceControllerRequiredTest {

    private ServiceController controller;
    private ServiceMapper serviceMapper;

    @BeforeEach
    void setUp() {
        controller = MockInjector.inject(new ServiceController());
        serviceMapper = MockInjector.field(controller, "serviceMapper");
        ProjectMapper projectMapper = MockInjector.field(controller, "projectMapper");
        when(projectMapper.selectById(1L)).thenReturn(new Project());
    }

    private static ServiceDTO valid() {
        ServiceDTO r = new ServiceDTO();
        r.setProjectId(1L);
        r.setName(" order ");
        r.setServiceType("backend");
        r.setServicePort(8080);
        r.setGitRepo("https://git.example.com/a/order.git");
        return r;
    }

    private String createError(ServiceDTO r) {
        return assertThrows(BusinessException.class, () -> controller.createService(r)).getMessage();
    }

    @Test
    void createRejectsEmptyRequiredFields() {
        ServiceDTO r = valid();
        r.setName("  ");
        assertEquals("服务名称不能为空", createError(r));

        r = valid();
        r.setName(null);
        assertEquals("服务名称不能为空", createError(r));

        r = valid();
        r.setServiceType("  ");
        assertEquals("服务类型不能为空", createError(r), "显式传空值拒绝");

        r = valid();
        r.setGitRepo(null);
        assertEquals("请选择 Git 组件", createError(r));

        verify(serviceMapper, never()).insert(any(Service.class));
    }

    @Test
    void createWithoutServiceTypeDefaultsToBackend() {
        ServiceDTO r = valid();
        r.setServiceType(null);
        ArgumentCaptor<Service> captor = ArgumentCaptor.forClass(Service.class);
        controller.createService(r);
        verify(serviceMapper).insert(captor.capture());
        assertEquals("backend", captor.getValue().getServiceType(), "没带服务类型沿用默认 backend");
    }

    @Test
    void createSavesTrimmedName() {
        ArgumentCaptor<Service> captor = ArgumentCaptor.forClass(Service.class);
        controller.createService(valid());
        verify(serviceMapper).insert(captor.capture());
        assertEquals("order", captor.getValue().getName());
        assertEquals("backend", captor.getValue().getServiceType());
    }

    @Test
    void updateRejectsBlankButAllowsAbsentFields() {
        Service stored = new Service();
        stored.setId(5L);
        stored.setName("order");
        stored.setServiceType("frontend");
        when(serviceMapper.selectById(5L)).thenReturn(stored);

        ServiceDTO blank = new ServiceDTO();
        blank.setName(" ");
        assertEquals("服务名称不能为空", assertThrows(BusinessException.class,
                () -> controller.updateService(5L, blank)).getMessage());
        ServiceDTO blankType = new ServiceDTO();
        blankType.setServiceType("");
        assertEquals("服务类型不能为空", assertThrows(BusinessException.class,
                () -> controller.updateService(5L, blankType)).getMessage());
        verify(serviceMapper, never()).updateById(any(Service.class));

        ServiceDTO partial = new ServiceDTO();
        partial.setServicePort(9090);
        controller.updateService(5L, partial);
        assertEquals("order", stored.getName());
        assertEquals("frontend", stored.getServiceType());
        verify(serviceMapper).updateById(stored);
    }
}
