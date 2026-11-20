package com.opsflow.service.impl.deploy;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.opsflow.api.dto.DeployModuleItemDTO;
import com.opsflow.api.dto.DeployTaskDTO;
import com.opsflow.common.constant.DeployModes;
import com.opsflow.common.exception.BusinessException;
import com.opsflow.common.util.DeployModuleJson;
import com.opsflow.dao.mapper.ApprovalFlowMapper;
import com.opsflow.dao.mapper.ApprovalRecordMapper;
import com.opsflow.dao.mapper.BuildJobMapper;
import com.opsflow.dao.mapper.BuildJobStageMapper;
import com.opsflow.dao.mapper.ClusterMapper;
import com.opsflow.dao.mapper.DeployTaskMapper;
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
import com.opsflow.license.LicenseFeatures;
import com.opsflow.service.DeployImageVerifyService;
import com.opsflow.service.DeployTaskEditService;
import com.opsflow.service.DeployTaskOperator;
import com.opsflow.service.LicenseService;
import com.opsflow.service.ProjectAccessService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 上线任务写操作：创建校验链、初始状态、更新时创建人与协作者的差异、删除限制。
 * 错误文案是前端直接展示给用户的，这里按原文断言，防止搬移时走样。
 */
@SuppressWarnings("unchecked")
class DeployTaskCommandServiceTest {

    private static final DeployTaskOperator CREATOR = new DeployTaskOperator("alice", 7L, true, false, false, false);
    private static final DeployTaskOperator OTHER = new DeployTaskOperator("bob", 8L, true, false, false, false);
    private static final String IMAGE = "harbor.example.com/prod/order:1.2.0";

    private DeployTaskCommandService service;
    private DeployTaskMapper deployTaskMapper;
    private EnvMapper envMapper;
    private ClusterMapper clusterMapper;
    private ApprovalFlowMapper approvalFlowMapper;
    private ApprovalRecordMapper approvalRecordMapper;
    private PipelineMapper pipelineMapper;
    private BuildJobMapper buildJobMapper;
    private BuildJobStageMapper buildJobStageMapper;
    private ProjectMapper projectMapper;
    private LicenseService licenseService;
    private DeployImageVerifyService deployImageVerifyService;
    private DeployTaskEditService deployTaskEditService;
    private ProjectAccessService projectAccessService;
    private DeployModuleImageResolver imageResolver;
    private DeployTaskAccessGuard accessGuard;

    @BeforeEach
    void setUp() {
        service = new DeployTaskCommandService();
        deployTaskMapper = inject("deployTaskMapper", DeployTaskMapper.class);
        envMapper = inject("envMapper", EnvMapper.class);
        clusterMapper = inject("clusterMapper", ClusterMapper.class);
        approvalFlowMapper = inject("approvalFlowMapper", ApprovalFlowMapper.class);
        approvalRecordMapper = inject("approvalRecordMapper", ApprovalRecordMapper.class);
        pipelineMapper = inject("pipelineMapper", PipelineMapper.class);
        buildJobMapper = inject("buildJobMapper", BuildJobMapper.class);
        buildJobStageMapper = inject("buildJobStageMapper", BuildJobStageMapper.class);
        projectMapper = inject("projectMapper", ProjectMapper.class);
        licenseService = inject("licenseService", LicenseService.class);
        deployImageVerifyService = inject("deployImageVerifyService", DeployImageVerifyService.class);
        deployTaskEditService = inject("deployTaskEditService", DeployTaskEditService.class);
        projectAccessService = inject("projectAccessService", ProjectAccessService.class);
        imageResolver = inject("deployModuleImageResolver", DeployModuleImageResolver.class);
        accessGuard = inject("deployTaskAccessGuard", DeployTaskAccessGuard.class);

        // 默认是一套能创建成功的环境，各用例只改自己关心的那一项
        when(projectMapper.selectById(1L)).thenReturn(new Project());
        when(clusterMapper.selectById(2L)).thenReturn(cluster(1));
        when(envMapper.selectList(any(Wrapper.class))).thenReturn(Collections.singletonList(env(9L, "prod")));
        when(envMapper.selectBatchIds(any())).thenReturn(Collections.singletonList(env(9L, "prod")));
        when(approvalFlowMapper.selectById(3L)).thenReturn(flow(1));
        when(pipelineMapper.selectById(4L)).thenReturn(pipeline(1, "cd"));
        when(imageResolver.normalizeDeployModules(anyList())).thenAnswer(inv -> inv.getArgument(0));
    }

    private <T> T inject(String field, Class<T> type) {
        T mock = mock(type);
        ReflectionTestUtils.setField(service, field, mock);
        return mock;
    }

    private static Cluster cluster(int status) {
        Cluster c = new Cluster();
        c.setStatus(status);
        return c;
    }

    private static Env env(Long id, String type) {
        Env e = new Env();
        e.setId(id);
        e.setName("env-" + id);
        e.setEnvType(type);
        return e;
    }

    private static ApprovalFlow flow(int status) {
        ApprovalFlow f = new ApprovalFlow();
        f.setStatus(status);
        return f;
    }

    private static Pipeline pipeline(Integer status, String type) {
        Pipeline p = new Pipeline();
        p.setStatus(status);
        p.setPipelineType(type);
        return p;
    }

    private static DeployTaskDTO validRequest() {
        DeployTaskDTO r = new DeployTaskDTO();
        r.setProjectId(1L);
        r.setTaskName("  订单服务上线 ");
        r.setDeployModules(Collections.singletonList(IMAGE));
        r.setClusterId(2L);
        r.setK8sNamespace(" prod ");
        r.setApprovalFlowId(3L);
        r.setPipelineTemplateId(4L);
        r.setNotifyChannels(Collections.singletonList("inbox"));
        return r;
    }

    private String createError(DeployTaskDTO request) {
        return assertThrows(BusinessException.class, () -> service.create(request, CREATOR)).getMessage();
    }

    // ---------- 创建：校验链 ----------

    @Test
    void createRequiresLicenseBeforeAnythingElse() {
        doThrow(new BusinessException("未开通")).when(licenseService).requireFeature(LicenseFeatures.DEPLOY_APPROVAL);
        assertEquals("未开通", createError(new DeployTaskDTO()));
        verify(projectMapper, never()).selectById(any());
    }

    @Test
    void createRequiresExistingProject() {
        DeployTaskDTO noProject = validRequest();
        noProject.setProjectId(null);
        assertEquals("请选择所属项目", createError(noProject));

        DeployTaskDTO unknown = validRequest();
        unknown.setProjectId(99L);
        assertEquals("请选择所属项目", createError(unknown));
    }

    @Test
    void createChecksProjectAccessBeforeFieldValidation() {
        doThrow(new BusinessException("无权操作该项目")).when(projectAccessService).assertCanOperateProject(1L);
        DeployTaskDTO r = validRequest();
        r.setTaskName(null);
        assertEquals("无权操作该项目", createError(r));
    }

    @Test
    void createRequiresNameModulesClusterAndNamespace() {
        DeployTaskDTO r = validRequest();
        r.setTaskName("   ");
        assertEquals("任务名称不能为空", createError(r));

        r = validRequest();
        r.setDeployModules(Collections.emptyList());
        assertEquals("请至少选择一个上线模块", createError(r));

        r = validRequest();
        r.setClusterId(null);
        assertEquals("请选择上线集群", createError(r));

        r = validRequest();
        r.setK8sNamespace(" ");
        assertEquals("请选择上线 Namespace", createError(r));
    }

    @Test
    void createRejectsMissingOrDisabledCluster() {
        when(clusterMapper.selectById(2L)).thenReturn(null);
        assertEquals("上线集群不存在", createError(validRequest()));

        when(clusterMapper.selectById(2L)).thenReturn(cluster(0));
        assertEquals("上线集群已禁用", createError(validRequest()));
    }

    @Test
    void createRequiresProdEnvironmentBoundToClusterAndNamespace() {
        when(envMapper.selectList(any(Wrapper.class))).thenReturn(Collections.emptyList());
        assertEquals("所选集群和 Namespace 未关联生产环境，不能创建上线任务", createError(validRequest()));

        when(envMapper.selectList(any(Wrapper.class))).thenReturn(Collections.singletonList(env(9L, "prod")));
        when(envMapper.selectBatchIds(any())).thenReturn(Collections.emptyList());
        assertEquals("部署环境不存在", createError(validRequest()));

        when(envMapper.selectBatchIds(any())).thenReturn(Arrays.asList(env(9L, "prod"), env(10L, "nonprod")));
        assertEquals("创建上线任务只能选择生产环境，请检查该集群关联的环境类型", createError(validRequest()));
    }

    @Test
    void createRejectsMissingOrDisabledApprovalFlow() {
        DeployTaskDTO r = validRequest();
        r.setApprovalFlowId(null);
        assertEquals("请选择审批流", createError(r));

        when(approvalFlowMapper.selectById(3L)).thenReturn(null);
        assertEquals("审批流不存在", createError(validRequest()));

        when(approvalFlowMapper.selectById(3L)).thenReturn(flow(0));
        assertEquals("审批流已禁用", createError(validRequest()));
    }

    @Test
    void createRequiresEnabledCdPipelineTemplate() {
        DeployTaskDTO r = validRequest();
        r.setPipelineTemplateId(null);
        assertEquals("请选择 CD 流水线模版", createError(r));

        when(pipelineMapper.selectById(4L)).thenReturn(null);
        assertEquals("CD 流水线模版不存在或已禁用", createError(validRequest()));

        when(pipelineMapper.selectById(4L)).thenReturn(pipeline(0, "cd"));
        assertEquals("CD 流水线模版不存在或已禁用", createError(validRequest()));

        when(pipelineMapper.selectById(4L)).thenReturn(pipeline(1, "ci"));
        assertEquals("上线任务只能选择类型为 CD 的流水线模版", createError(validRequest()));
    }

    @Test
    void createStopsWhenImageDoesNotExist() {
        doThrow(new BusinessException("镜像不存在")).when(deployImageVerifyService).assertImagesExist(anyList());
        assertEquals("镜像不存在", createError(validRequest()));
        verify(deployTaskMapper, never()).insert(any());
    }

    @Test
    void createRejectsMalformedModuleAddressBeforeTouchingHarborOrDatabase() {
        doThrow(new BusinessException("上线模块格式无效: order:1.0")).when(imageResolver).assertValidModules(anyList());
        assertEquals("上线模块格式无效: order:1.0", createError(validRequest()));
        verify(imageResolver, never()).normalizeDeployModules(anyList());
        verify(deployImageVerifyService, never()).assertImagesExist(anyList());
        verify(deployTaskMapper, never()).insert(any());
    }

    @Test
    void createValidatesTheAddressesAsSubmitted() {
        service.create(validRequest(), CREATOR);
        verify(imageResolver).assertValidModules(Collections.singletonList(IMAGE));
    }

    // ---------- 创建：成功路径 ----------

    @Test
    void createPersistsTaskInCollaborativeEditingState() throws Exception {
        DeployTask result = service.create(validRequest(), CREATOR);

        ArgumentCaptor<DeployTask> captor = ArgumentCaptor.forClass(DeployTask.class);
        verify(deployTaskMapper).insert(captor.capture());
        DeployTask saved = captor.getValue();
        assertNotNull(result);
        assertEquals("pending", saved.getTaskStatus());
        assertEquals("none", saved.getApprovalStatus());
        assertEquals(0, saved.getLocked());
        assertEquals("alice", saved.getCreatorName());
        assertEquals(7L, saved.getCreatorId());
        assertEquals("prod", saved.getK8sNamespace(), "Namespace 入库前去掉首尾空格");
        assertEquals("inbox", saved.getNotifyChannels());
        assertEquals("[9]", saved.getDeployEnvs(), "未传环境时按集群 + Namespace 自动匹配");
        assertEquals(DeployModes.normalize(null), saved.getDeployMode());
        assertTrue(saved.getTaskNumber().matches("TASK-\\d{14}"));

        List<DeployModuleItemDTO> items = DeployModuleJson.parseItems(saved.getDeployModules());
        assertEquals(1, items.size());
        assertEquals(IMAGE, items.get(0).getImage());
        assertEquals("alice", items.get(0).getOwnerName(), "创建时的模块归属创建人");
    }

    @Test
    void createDropsFeishuChannelWhenIntegrationIsNotLicensed() {
        when(licenseService.isFeatureEnabled(LicenseFeatures.NOTIFY_INTEGRATION)).thenReturn(false);
        DeployTaskDTO r = validRequest();
        r.setNotifyChannels(Arrays.asList("feishu", "inbox"));
        service.create(r, CREATOR);

        ArgumentCaptor<DeployTask> captor = ArgumentCaptor.forClass(DeployTask.class);
        verify(deployTaskMapper).insert(captor.capture());
        assertEquals("inbox", captor.getValue().getNotifyChannels());
    }

    @Test
    void createKeepsFeishuChannelWhenIntegrationIsLicensed() {
        when(licenseService.isFeatureEnabled(LicenseFeatures.NOTIFY_INTEGRATION)).thenReturn(true);
        DeployTaskDTO r = validRequest();
        r.setNotifyChannels(Arrays.asList("feishu", "inbox"));
        service.create(r, CREATOR);

        ArgumentCaptor<DeployTask> captor = ArgumentCaptor.forClass(DeployTask.class);
        verify(deployTaskMapper).insert(captor.capture());
        assertEquals("inbox,feishu", captor.getValue().getNotifyChannels());
    }

    // ---------- 更新 ----------

    private DeployTask existingTask() {
        DeployTask task = new DeployTask();
        task.setId(5L);
        task.setTaskName("旧名称");
        task.setClusterId(2L);
        task.setK8sNamespace("prod");
        when(deployTaskMapper.selectById(5L)).thenReturn(task);
        return task;
    }

    @Test
    void updateFailsWhenTaskIsMissing() {
        assertEquals("任务不存在", assertThrows(BusinessException.class,
                () -> service.update(404L, new DeployTaskDTO(), CREATOR)).getMessage());
    }

    @Test
    void updateChecksEditableAndProjectAccessFirst() {
        DeployTask task = existingTask();
        doThrow(new BusinessException("任务已锁定")).when(deployTaskEditService).assertEditable(task);
        assertEquals("任务已锁定", assertThrows(BusinessException.class,
                () -> service.update(5L, new DeployTaskDTO(), CREATOR)).getMessage());
        verify(accessGuard, never()).assertTaskOperable(any(DeployTask.class));
        verify(deployTaskMapper, never()).updateById(any());
    }

    @Test
    void creatorCanChangeTaskMetadata() {
        DeployTask task = existingTask();
        when(deployTaskEditService.isCreator(task, "alice", 7L)).thenReturn(true);
        DeployTaskDTO r = new DeployTaskDTO();
        r.setTaskName(" 新名称 ");
        r.setK8sNamespace(" prod-b ");
        r.setDeployMode(DeployModes.PARALLEL);
        r.setDeployParallelism(5);

        service.update(5L, r, CREATOR);

        assertEquals("新名称", task.getTaskName());
        assertEquals("prod-b", task.getK8sNamespace());
        assertEquals(DeployModes.PARALLEL, task.getDeployMode());
        assertEquals(5, task.getDeployParallelism());
        verify(deployTaskMapper).updateById(task);
    }

    @Test
    void collaboratorCannotChangeTaskMetadataButRequestIsNotRejected() {
        DeployTask task = existingTask();
        when(deployTaskEditService.isCreator(task, "bob", 8L)).thenReturn(false);
        DeployTaskDTO r = new DeployTaskDTO();
        r.setTaskName("被协作者改名");
        r.setClusterId(99L);

        service.update(5L, r, OTHER);

        assertEquals("旧名称", task.getTaskName());
        assertEquals(2L, task.getClusterId());
        verify(deployTaskMapper).updateById(task);
    }

    @Test
    void updateMergesModulesByOwnershipAndVerifiesImages() throws Exception {
        DeployTask task = existingTask();
        DeployModuleItemDTO merged = new DeployModuleItemDTO();
        merged.setImage("prod/order:1.3.0");
        merged.setOwnerName("bob");
        when(deployTaskEditService.mergeModulesByOwnership(any(), any(), any(), any(), any()))
                .thenReturn(Collections.singletonList(merged));
        when(imageResolver.normalizeDeployModules(anyList()))
                .thenReturn(Collections.singletonList("harbor.example.com/prod/order:1.3.0"));
        DeployTaskDTO r = new DeployTaskDTO();
        r.setDeployModules(Collections.singletonList("prod/order:1.3.0"));

        service.update(5L, r, OTHER);

        verify(deployImageVerifyService).assertImagesExist(Collections.singletonList("harbor.example.com/prod/order:1.3.0"));
        List<DeployModuleItemDTO> stored = DeployModuleJson.parseItems(task.getDeployModules());
        assertEquals("harbor.example.com/prod/order:1.3.0", stored.get(0).getImage(), "入库的是规范化后的完整地址");
        assertEquals("bob", stored.get(0).getOwnerName());
    }

    @Test
    void updateValidatesOnlyTheOperatorsOwnModules() {
        existingTask();
        DeployModuleItemDTO mine = new DeployModuleItemDTO();
        mine.setImage("prod/order:1.3.0");
        mine.setOwnerName("bob");
        mine.setOwnerId(8L);
        DeployModuleItemDTO legacyOfSomeoneElse = new DeployModuleItemDTO();
        legacyOfSomeoneElse.setImage("legacy-without-tag");
        legacyOfSomeoneElse.setOwnerName("alice");
        legacyOfSomeoneElse.setOwnerId(7L);
        when(deployTaskEditService.mergeModulesByOwnership(any(), any(), any(), any(), any()))
                .thenReturn(Arrays.asList(legacyOfSomeoneElse, mine));
        when(imageResolver.normalizeDeployModules(anyList())).thenAnswer(inv -> inv.getArgument(0));
        DeployTaskDTO r = new DeployTaskDTO();
        r.setDeployModules(Collections.singletonList("prod/order:1.3.0"));

        service.update(5L, r, OTHER);

        verify(imageResolver).assertValidModules(Collections.singletonList("prod/order:1.3.0"));
    }

    @Test
    void updateRejectsMalformedModuleAddressWithoutSaving() {
        existingTask();
        DeployModuleItemDTO mine = new DeployModuleItemDTO();
        mine.setImage("order");
        mine.setOwnerName("bob");
        when(deployTaskEditService.mergeModulesByOwnership(any(), any(), any(), any(), any()))
                .thenReturn(Collections.singletonList(mine));
        doThrow(new BusinessException("上线模块格式无效: order")).when(imageResolver).assertValidModules(anyList());
        DeployTaskDTO r = new DeployTaskDTO();
        r.setDeployModules(Collections.singletonList("order"));

        assertEquals("上线模块格式无效: order", assertThrows(BusinessException.class,
                () -> service.update(5L, r, OTHER)).getMessage());
        verify(deployTaskMapper, never()).updateById(any());
    }

    // ---------- 删除 ----------

    @Test
    void deleteIsRefusedWhileTaskIsRunning() {
        for (String status : new String[]{"deploying", " Building "}) {
            DeployTask task = existingTask();
            task.setTaskStatus(status);
            assertEquals("任务发布中，无法删除", assertThrows(BusinessException.class,
                    () -> service.delete(5L)).getMessage());
        }
        verify(deployTaskMapper, never()).deleteById(anyLong());
    }

    @Test
    void deleteRunsInOneTransactionThatRollsBackOnAnyFailure() throws Exception {
        Transactional tx = DeployTaskCommandService.class.getMethod("delete", Long.class).getAnnotation(Transactional.class);
        assertNotNull(tx, "删除涉及 4 张表，必须在同一个事务里");
        assertEquals(Collections.singletonList(Exception.class), Arrays.asList(tx.rollbackFor()));
    }

    @Test
    void deleteFailsWhenTaskIsMissing() {
        assertEquals("任务不存在", assertThrows(BusinessException.class, () -> service.delete(404L)).getMessage());
    }

    @Test
    void deleteRemovesJobsStagesApprovalRecordsThenTask() {
        DeployTask task = existingTask();
        task.setTaskStatus("success");
        BuildJob job = new BuildJob();
        job.setId(31L);
        when(buildJobMapper.selectList(any(Wrapper.class))).thenReturn(Collections.singletonList(job));
        when(deployTaskMapper.deleteById(5L)).thenReturn(1);

        assertTrue(service.delete(5L));

        InOrder order = inOrder(accessGuard, buildJobStageMapper, buildJobMapper, approvalRecordMapper, deployTaskMapper);
        order.verify(accessGuard).assertTaskOperable(task);
        order.verify(buildJobStageMapper).delete(any(Wrapper.class));
        order.verify(buildJobMapper).deleteById(31L);
        order.verify(approvalRecordMapper).delete(any(Wrapper.class));
        order.verify(deployTaskMapper).deleteById(5L);
    }
}
