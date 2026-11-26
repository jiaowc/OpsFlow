package com.opsflow.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.opsflow.api.dto.BuildJobDTO;
import com.opsflow.dao.mapper.BuildJobMapper;
import com.opsflow.dao.mapper.EnvMapper;
import com.opsflow.dao.mapper.ServiceMapper;
import com.opsflow.dao.model.BuildJob;
import com.opsflow.dao.model.Env;
import com.opsflow.service.ProjectAccessService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 构建任务列表（GET /api/build/jobs）：返回内容不变，但不查大字段，服务/环境名称不重复查。
 */
class BuildServiceImplJobListTest {

    private BuildServiceImpl service;
    private BuildJobMapper buildJobMapper;
    private ServiceMapper serviceMapper;
    private EnvMapper envMapper;

    @BeforeEach
    void setUp() {
        service = new BuildServiceImpl();
        buildJobMapper = mock(BuildJobMapper.class);
        serviceMapper = mock(ServiceMapper.class);
        envMapper = mock(EnvMapper.class);
        ProjectAccessService projectAccessService = mock(ProjectAccessService.class);
        when(projectAccessService.accessibleServiceIds(null)).thenReturn(null);
        ReflectionTestUtils.setField(service, "buildJobMapper", buildJobMapper);
        ReflectionTestUtils.setField(service, "serviceMapper", serviceMapper);
        ReflectionTestUtils.setField(service, "envMapper", envMapper);
        ReflectionTestUtils.setField(service, "projectAccessService", projectAccessService);
    }

    private static BuildJob job(long id, long serviceId, long envId) {
        BuildJob job = new BuildJob();
        job.setId(id);
        job.setJobNumber("BUILD-" + id);
        job.setTaskName("任务" + id);
        job.setServiceId(serviceId);
        job.setEnvId(envId);
        job.setBranch("master");
        job.setStatus("SUCCESS");
        job.setBuildNumber((int) id);
        job.setCreateTime(LocalDateTime.of(2026, 11, 18, 10, 0));
        job.setStartTime(LocalDateTime.of(2026, 11, 18, 10, 1));
        job.setEndTime(LocalDateTime.of(2026, 11, 18, 10, 2));
        return job;
    }

    @Test
    void listKeepsEveryFieldAndLooksUpEachNameOnce() {
        when(buildJobMapper.selectList(any())).thenReturn(Arrays.asList(job(3, 10, 20), job(2, 10, 20), job(1, 11, 21)));
        com.opsflow.dao.model.Service order = new com.opsflow.dao.model.Service();
        order.setName("订单服务");
        when(serviceMapper.selectById(10L)).thenReturn(order);
        when(serviceMapper.selectById(11L)).thenReturn(null);
        Env dev = new Env();
        dev.setName("dev");
        when(envMapper.selectById(20L)).thenReturn(dev);
        when(envMapper.selectById(21L)).thenReturn(null);

        List<BuildJobDTO> jobs = service.getBuildJobs(20L);

        assertEquals(3, jobs.size());
        BuildJobDTO first = jobs.get(0);
        assertEquals(3L, first.getId());
        assertEquals("BUILD-3", first.getJobNumber());
        assertEquals("任务3", first.getTaskName());
        assertEquals(10L, first.getServiceId());
        assertEquals(20L, first.getEnvId());
        assertEquals("master", first.getBranch());
        assertEquals("SUCCESS", first.getStatus());
        assertEquals(3, first.getBuildNumber());
        assertEquals(LocalDateTime.of(2026, 11, 18, 10, 0), first.getCreateTime());
        assertEquals(LocalDateTime.of(2026, 11, 18, 10, 1), first.getStartTime());
        assertEquals(LocalDateTime.of(2026, 11, 18, 10, 2), first.getEndTime());
        assertEquals("订单服务", first.getServiceName());
        assertEquals("dev", first.getEnvName());
        assertEquals("订单服务", jobs.get(1).getServiceName());
        // 服务或环境已被删除：名称为空，不报错
        assertNull(jobs.get(2).getServiceName());
        assertNull(jobs.get(2).getEnvName());

        // 两条记录共用的服务、环境只查一次；查不到的也只查一次
        verify(serviceMapper, times(1)).selectById(10L);
        verify(serviceMapper, times(1)).selectById(11L);
        verify(envMapper, times(1)).selectById(20L);
        verify(envMapper, times(1)).selectById(21L);
    }

    @Test
    @SuppressWarnings("unchecked")
    void listDoesNotSelectLargeColumns() {
        when(buildJobMapper.selectList(any())).thenReturn(Arrays.asList());

        service.getBuildJobs(null);

        ArgumentCaptor<QueryWrapper<BuildJob>> captor = ArgumentCaptor.forClass(QueryWrapper.class);
        verify(buildJobMapper).selectList(captor.capture());
        String columns = captor.getValue().getSqlSelect();
        assertTrue(columns.contains("job_number"), columns);
        assertTrue(columns.contains("end_time"), columns);
        assertFalse(columns.contains("build_parameters"), columns);
        assertFalse(columns.contains("error_message"), columns);
    }
}
