package com.opsflow.service.impl;

import com.opsflow.api.dto.BuildRequest;
import com.opsflow.api.dto.EnvDTO;
import com.opsflow.common.exception.BusinessException;
import com.opsflow.dao.mapper.BuildJobMapper;
import com.opsflow.dao.mapper.EnvMapper;
import com.opsflow.dao.mapper.PipelineMapper;
import com.opsflow.dao.mapper.ServiceMapper;
import com.opsflow.dao.model.BuildJob;
import com.opsflow.dao.model.Pipeline;
import com.opsflow.dao.model.Service;
import com.opsflow.service.PipelineRunService;
import com.opsflow.service.PipelineViewService;
import com.opsflow.service.ProjectAccessService;
import com.opsflow.spi.ProdDeployPolicy;
import com.opsflow.spi.ProdDeployTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import static com.opsflow.spi.ProdDeployTestSupport.env;
import static com.opsflow.spi.ProdDeployTestSupport.loginWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 生产环境直接构建（POST /api/build/start）和可构建环境列表（GET /api/build/envs）按生产部署策略放行。
 */
class BuildServiceImplProdDeployTest {

    private BuildServiceImpl service;
    private BuildJobMapper buildJobMapper;
    private PipelineRunService pipelineRunService;
    private EnvMapper envMapper;

    @BeforeEach
    void setUp() {
        service = new BuildServiceImpl();
        buildJobMapper = mock(BuildJobMapper.class);
        pipelineRunService = mock(PipelineRunService.class);
        envMapper = mock(EnvMapper.class);
        ServiceMapper serviceMapper = mock(ServiceMapper.class);
        PipelineMapper pipelineMapper = mock(PipelineMapper.class);

        Service svc = new Service();
        svc.setId(1L);
        svc.setName("order");
        when(serviceMapper.selectById(1L)).thenReturn(svc);
        when(envMapper.selectById(10L)).thenReturn(env(10L, "test", "nonprod"));
        when(envMapper.selectById(20L)).thenReturn(env(20L, "prod", "prod"));
        when(envMapper.selectList(any())).thenReturn(Arrays.asList(env(10L, "test", "nonprod"), env(20L, "prod", "prod")));
        Pipeline pipeline = new Pipeline();
        pipeline.setId(3L);
        pipeline.setStatus(1);
        when(pipelineMapper.selectById(3L)).thenReturn(pipeline);

        ReflectionTestUtils.setField(service, "buildJobMapper", buildJobMapper);
        ReflectionTestUtils.setField(service, "serviceMapper", serviceMapper);
        ReflectionTestUtils.setField(service, "envMapper", envMapper);
        ReflectionTestUtils.setField(service, "pipelineMapper", pipelineMapper);
        ReflectionTestUtils.setField(service, "pipelineRunService", pipelineRunService);
        ReflectionTestUtils.setField(service, "pipelineViewService", mock(PipelineViewService.class));
        ReflectionTestUtils.setField(service, "projectAccessService", mock(ProjectAccessService.class));
        ReflectionTestUtils.setField(service, "prodDeployPolicy", ProdDeployTestSupport.communityPolicy());
    }

    @AfterEach
    void tearDown() {
        ProdDeployTestSupport.logout();
    }

    private static BuildRequest request(long envId) {
        BuildRequest request = new BuildRequest();
        request.setServiceId(1L);
        request.setEnvId(envId);
        request.setBranch("main");
        request.setPipelineTemplateId(3L);
        return request;
    }

    private List<String> buildableEnvNames() {
        return service.getBuildableEnvs().stream().map(EnvDTO::getName).collect(Collectors.toList());
    }

    @Test
    void communityBuildsProdWithTheProdPermission() {
        loginWith("pipeline:run", ProdDeployPolicy.PROD_DEPLOY_PERMISSION);

        service.startBuild(request(20L));

        verify(buildJobMapper).insert(any(BuildJob.class));
        verify(pipelineRunService).prepareAndExecuteAsync(any());
        assertEquals(Arrays.asList("test", "prod"), buildableEnvNames());
    }

    @Test
    void communityRejectsProdWithoutThePermission() {
        loginWith("pipeline:run");

        BusinessException e = assertThrows(BusinessException.class, () -> service.startBuild(request(20L)));

        assertEquals(true, e.getMessage().contains(ProdDeployPolicy.PROD_DEPLOY_PERMISSION), e.getMessage());
        verify(buildJobMapper, never()).insert(any(BuildJob.class));
        assertEquals(Arrays.asList("test"), buildableEnvNames());

        service.startBuild(request(10L));
        verify(buildJobMapper).insert(any(BuildJob.class));
    }

}
