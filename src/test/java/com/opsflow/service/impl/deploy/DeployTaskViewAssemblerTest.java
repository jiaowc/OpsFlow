package com.opsflow.service.impl.deploy;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.opsflow.api.dto.DeployModuleDetailDTO;
import com.opsflow.api.dto.DeployModuleItemDTO;
import com.opsflow.api.dto.DeployTaskDTO;
import com.opsflow.common.util.DeployModuleJson;
import com.opsflow.dao.mapper.ApprovalFlowMapper;
import com.opsflow.dao.mapper.BuildJobMapper;
import com.opsflow.dao.mapper.ClusterMapper;
import com.opsflow.dao.mapper.EnvMapper;
import com.opsflow.dao.mapper.PipelineMapper;
import com.opsflow.dao.mapper.ProjectMapper;
import com.opsflow.dao.model.ApprovalFlow;
import com.opsflow.dao.model.BuildJob;
import com.opsflow.dao.model.Cluster;
import com.opsflow.dao.model.DeployTask;
import com.opsflow.dao.model.Env;
import com.opsflow.dao.model.Pipeline;
import com.opsflow.dao.model.Project;
import com.opsflow.dao.model.Service;
import com.opsflow.service.ApprovalService;
import com.opsflow.service.DeployImageVerifyService;
import com.opsflow.service.DeployTaskEditService;
import com.opsflow.service.DeployTaskOperator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 上线任务实体 → 展示 DTO：关联名称、模块详情、容错行为。
 */
@SuppressWarnings("unchecked")
class DeployTaskViewAssemblerTest {

    private static final DeployTaskOperator OPERATOR = new DeployTaskOperator("alice", 7L, true, false, true, false);
    private static final String IMAGE = "harbor.example.com/prod/order:1.2.0";

    private DeployTaskViewAssembler assembler;
    private ProjectMapper projectMapper;
    private EnvMapper envMapper;
    private ApprovalFlowMapper approvalFlowMapper;
    private PipelineMapper pipelineMapper;
    private BuildJobMapper buildJobMapper;
    private ClusterMapper clusterMapper;
    private ApprovalService approvalService;
    private DeployTaskEditService deployTaskEditService;
    private DeployModuleImageResolver imageResolver;

    @BeforeEach
    void setUp() {
        assembler = new DeployTaskViewAssembler();
        projectMapper = inject("projectMapper", ProjectMapper.class);
        envMapper = inject("envMapper", EnvMapper.class);
        approvalFlowMapper = inject("approvalFlowMapper", ApprovalFlowMapper.class);
        pipelineMapper = inject("pipelineMapper", PipelineMapper.class);
        buildJobMapper = inject("buildJobMapper", BuildJobMapper.class);
        clusterMapper = inject("clusterMapper", ClusterMapper.class);
        approvalService = inject("approvalService", ApprovalService.class);
        deployTaskEditService = inject("deployTaskEditService", DeployTaskEditService.class);
        inject("deployImageVerifyService", DeployImageVerifyService.class);
        imageResolver = inject("deployModuleImageResolver", DeployModuleImageResolver.class);
        when(imageResolver.normalizeDeployModules(anyList())).thenAnswer(inv -> inv.getArgument(0));
        when(imageResolver.extractServiceCode(IMAGE)).thenReturn("order");
        when(buildJobMapper.selectList(any(Wrapper.class))).thenReturn(Collections.emptyList());
    }

    private <T> T inject(String field, Class<T> type) {
        T mock = mock(type);
        ReflectionTestUtils.setField(assembler, field, mock);
        return mock;
    }

    private static DeployTask task() throws Exception {
        DeployTask task = new DeployTask();
        task.setId(5L);
        task.setTaskName("订单服务上线");
        task.setCreatorName("alice");
        task.setCreatorId(7L);
        // 历史数据：模块没有归属人
        task.setDeployModules(DeployModuleJson.writeItems(DeployModuleJson.fromImages(
                Collections.singletonList(IMAGE), null, null)));
        return task;
    }

    @Test
    void fillsRelatedNamesAndBuildJobIds() throws Exception {
        DeployTask task = task();
        task.setProjectId(1L);
        task.setClusterId(2L);
        task.setApprovalFlowId(3L);
        task.setPipelineTemplateId(4L);
        task.setDeployEnvs("[9,10]");
        Project project = new Project();
        project.setName("电商");
        Cluster cluster = new Cluster();
        cluster.setName("prod-cluster");
        ApprovalFlow flow = new ApprovalFlow();
        flow.setName("生产审批流");
        Pipeline pipeline = new Pipeline();
        pipeline.setName("通用 CD");
        Env env = new Env();
        env.setName("prod");
        BuildJob job = new BuildJob();
        job.setId(31L);
        when(projectMapper.selectById(1L)).thenReturn(project);
        when(clusterMapper.selectById(2L)).thenReturn(cluster);
        when(approvalFlowMapper.selectById(3L)).thenReturn(flow);
        when(pipelineMapper.selectById(4L)).thenReturn(pipeline);
        when(envMapper.selectById(9L)).thenReturn(env);
        when(buildJobMapper.selectList(any(Wrapper.class))).thenReturn(Collections.singletonList(job));

        DeployTaskDTO dto = assembler.toDTO(task, OPERATOR);

        assertEquals("电商", dto.getProjectName());
        assertEquals("prod-cluster", dto.getClusterName());
        assertEquals("生产审批流", dto.getApprovalFlowName());
        assertEquals("通用 CD", dto.getPipelineTemplateName());
        assertEquals(Arrays.asList(9L, 10L), dto.getDeployEnvIds());
        assertEquals(Collections.singletonList("prod"), dto.getDeployEnvNames(), "找不到的环境不出现在名称列表里");
        assertEquals(Collections.singletonList(31L), dto.getBuildJobIds());
        verify(approvalService).listByTask(5L, "alice");
        verify(deployTaskEditService).fillActionFlags(dto, task, "alice", 7L, true, false, true);
    }

    @Test
    void modulesWithoutOwnerFallBackToTaskCreator() throws Exception {
        DeployTaskDTO dto = assembler.toDTO(task(), OPERATOR);

        DeployModuleItemDTO item = dto.getDeployModuleItems().get(0);
        assertEquals("alice", item.getOwnerName());
        assertEquals(7L, item.getOwnerId());
        assertEquals(Collections.singletonList(IMAGE), dto.getDeployModules());
    }

    @Test
    void moduleDetailUsesServiceNameAndPort() throws Exception {
        Service service = new Service();
        service.setName("订单服务");
        service.setCode("order");
        service.setServicePort(9000);
        when(imageResolver.findServiceByCode("order")).thenReturn(service);

        DeployModuleDetailDTO detail = assembler.toDTO(task(), OPERATOR).getDeployModuleDetails().get(0);

        assertEquals(IMAGE, detail.getImageFullName());
        assertEquals("订单服务", detail.getServiceName());
        assertEquals("order", detail.getServiceCode());
        assertEquals(9000, detail.getServicePort());
    }

    @Test
    void moduleDetailDefaultsWhenServiceIsUnknownOrPortUnset() throws Exception {
        DeployModuleDetailDTO unknown = assembler.toDTO(task(), OPERATOR).getDeployModuleDetails().get(0);
        assertEquals("order", unknown.getServiceName(), "找不到服务时用服务标识充当名称");
        assertEquals(8080, unknown.getServicePort());

        Service noPort = new Service();
        noPort.setName("订单服务");
        when(imageResolver.findServiceByCode("order")).thenReturn(noPort);
        DeployModuleDetailDTO detail = assembler.toDTO(task(), OPERATOR).getDeployModuleDetails().get(0);
        assertEquals(8080, detail.getServicePort());
        assertEquals("order", detail.getServiceCode(), "服务未配置 code 时保留镜像里的标识");
    }

    @Test
    void notifyChannelsAreSplitAndDefaultToInbox() throws Exception {
        DeployTask task = task();
        task.setNotifyChannels("inbox, feishu");
        assertEquals(Arrays.asList("inbox", "feishu"), assembler.toDTO(task, OPERATOR).getNotifyChannels());

        task.setNotifyChannels(null);
        assertEquals(Collections.singletonList("inbox"), assembler.toDTO(task, OPERATOR).getNotifyChannels());
    }

    @Test
    void brokenJsonDoesNotBreakTheWholeTask() throws Exception {
        DeployTask task = task();
        task.setDeployEnvs("not-json");
        task.setDeployModules("{broken");

        DeployTaskDTO dto = assembler.toDTO(task, OPERATOR);

        assertEquals("订单服务上线", dto.getTaskName());
        assertNull(dto.getDeployEnvIds());
        verify(approvalService).listByTask(5L, "alice");
    }

    // ---------- 批量组装（列表） ----------

    private static DeployTask taskInProject(long id, long projectId, long clusterId, String envs) throws Exception {
        DeployTask task = task();
        task.setId(id);
        task.setProjectId(projectId);
        task.setClusterId(clusterId);
        task.setApprovalFlowId(3L);
        task.setPipelineTemplateId(4L);
        task.setDeployEnvs(envs);
        return task;
    }

    private void givenRelatedData() {
        Project p1 = new Project();
        p1.setId(1L);
        p1.setName("电商");
        Project p2 = new Project();
        p2.setId(2L);
        p2.setName("支付");
        Cluster cluster = new Cluster();
        cluster.setId(2L);
        cluster.setName("prod-cluster");
        ApprovalFlow flow = new ApprovalFlow();
        flow.setId(3L);
        flow.setName("生产审批流");
        Pipeline pipeline = new Pipeline();
        pipeline.setId(4L);
        pipeline.setName("通用 CD");
        Env env = new Env();
        env.setId(9L);
        env.setName("prod");
        BuildJob j1 = new BuildJob();
        j1.setId(31L);
        j1.setDeployTaskId(5L);
        BuildJob j2 = new BuildJob();
        j2.setId(32L);
        j2.setDeployTaskId(6L);
        BuildJob j3 = new BuildJob();
        j3.setId(33L);
        j3.setDeployTaskId(5L);
        // 批量接口
        when(projectMapper.selectBatchIds(any())).thenReturn(Arrays.asList(p1, p2));
        when(clusterMapper.selectBatchIds(any())).thenReturn(Collections.singletonList(cluster));
        when(approvalFlowMapper.selectBatchIds(any())).thenReturn(Collections.singletonList(flow));
        when(pipelineMapper.selectBatchIds(any())).thenReturn(Collections.singletonList(pipeline));
        when(envMapper.selectBatchIds(any())).thenReturn(Collections.singletonList(env));
        when(buildJobMapper.selectList(any(Wrapper.class))).thenReturn(Arrays.asList(j1, j2, j3));
        // 逐条接口（单个组装时用），返回同样的数据
        when(projectMapper.selectById(1L)).thenReturn(p1);
        when(projectMapper.selectById(2L)).thenReturn(p2);
        when(clusterMapper.selectById(2L)).thenReturn(cluster);
        when(approvalFlowMapper.selectById(3L)).thenReturn(flow);
        when(pipelineMapper.selectById(4L)).thenReturn(pipeline);
        when(envMapper.selectById(9L)).thenReturn(env);
    }

    @Test
    void batchLoadsRelatedDataOnceForTheWholeList() throws Exception {
        givenRelatedData();
        java.util.List<DeployTask> tasks = Arrays.asList(
                taskInProject(5L, 1L, 2L, "[9,10]"), taskInProject(6L, 2L, 2L, "[9]"), taskInProject(7L, 1L, 2L, null));

        java.util.List<DeployTaskDTO> dtos = assembler.toDTOs(tasks, OPERATOR);

        assertEquals(3, dtos.size());
        assertEquals("电商", dtos.get(0).getProjectName());
        assertEquals("支付", dtos.get(1).getProjectName());
        assertEquals("prod-cluster", dtos.get(2).getClusterName());
        assertEquals("生产审批流", dtos.get(1).getApprovalFlowName());
        assertEquals("通用 CD", dtos.get(1).getPipelineTemplateName());
        assertEquals(Collections.singletonList("prod"), dtos.get(0).getDeployEnvNames(), "环境 10 不存在，不出现在名称里");
        assertEquals(Arrays.asList(31L, 33L), dtos.get(0).getBuildJobIds(), "关联 Job 按任务分组、保持 id 顺序");
        assertEquals(Collections.singletonList(32L), dtos.get(1).getBuildJobIds());
        assertNull(dtos.get(2).getBuildJobIds(), "没有关联 Job 的任务保持为 null");

        // 每类关联数据只查一次，且不再逐条查询
        verify(projectMapper, org.mockito.Mockito.times(1)).selectBatchIds(any());
        verify(envMapper, org.mockito.Mockito.times(1)).selectBatchIds(any());
        verify(clusterMapper, org.mockito.Mockito.times(1)).selectBatchIds(any());
        verify(approvalFlowMapper, org.mockito.Mockito.times(1)).selectBatchIds(any());
        verify(pipelineMapper, org.mockito.Mockito.times(1)).selectBatchIds(any());
        verify(buildJobMapper, org.mockito.Mockito.times(1)).selectList(any(Wrapper.class));
        verify(projectMapper, org.mockito.Mockito.never()).selectById(any());
        verify(envMapper, org.mockito.Mockito.never()).selectById(any());
        verify(clusterMapper, org.mockito.Mockito.never()).selectById(any());
        verify(approvalFlowMapper, org.mockito.Mockito.never()).selectById(any());
        verify(pipelineMapper, org.mockito.Mockito.never()).selectById(any());
        // 三个任务用的是同一个服务，只查一次
        verify(imageResolver, org.mockito.Mockito.times(1)).findServiceByCode("order");
    }

    @Test
    void batchResultEqualsAssemblingOneByOne() throws Exception {
        givenRelatedData();
        java.util.List<DeployTask> tasks = Arrays.asList(
                taskInProject(5L, 1L, 2L, "[9,10]"), taskInProject(6L, 2L, 2L, "[9]"));
        java.util.List<DeployTaskDTO> batch = assembler.toDTOs(tasks, OPERATOR);

        // 逐个组装时 Job 按任务查询
        BuildJob j1 = new BuildJob();
        j1.setId(31L);
        BuildJob j3 = new BuildJob();
        j3.setId(33L);
        BuildJob j2 = new BuildJob();
        j2.setId(32L);
        when(buildJobMapper.selectList(any(Wrapper.class)))
                .thenReturn(Arrays.asList(j1, j3))
                .thenReturn(Collections.singletonList(j2));
        for (int i = 0; i < tasks.size(); i++) {
            DeployTaskDTO single = assembler.toDTO(tasks.get(i), OPERATOR);
            assertEquals(single, batch.get(i), "第 " + (i + 1) + " 个任务：批量与逐个组装的结果应完全相同");
        }
    }

    @Test
    void emptyListDoesNotQueryAnything() {
        assertEquals(0, assembler.toDTOs(Collections.emptyList(), OPERATOR).size());
        assertEquals(0, assembler.toDTOs(null, OPERATOR).size());
        verify(projectMapper, org.mockito.Mockito.never()).selectBatchIds(any());
        verify(buildJobMapper, org.mockito.Mockito.never()).selectList(any(Wrapper.class));
    }
}
