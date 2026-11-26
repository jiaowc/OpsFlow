package com.opsflow.service.impl.pipeline;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.opsflow.api.dto.BuildResponse;
import com.opsflow.common.exception.BusinessException;
import com.opsflow.common.util.GitRepoKey;
import com.opsflow.dao.mapper.BuildJobMapper;
import com.opsflow.dao.mapper.EnvMapper;
import com.opsflow.dao.mapper.PipelineWebhookTriggerMapper;
import com.opsflow.dao.mapper.ServiceMapper;
import com.opsflow.dao.model.BuildJob;
import com.opsflow.dao.model.PipelineWebhookTrigger;
import com.opsflow.dao.model.Service;
import com.opsflow.service.ProjectAccessService;
import com.opsflow.spi.ProdDeployPolicy;
import com.opsflow.spi.ProdDeployTestSupport;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.opsflow.spi.ProdDeployTestSupport.env;
import static com.opsflow.spi.ProdDeployTestSupport.loginWith;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 生产环境的 Git 推送自动构建：社区版开启推送要「部署生产环境」权限、推送时直接触发。
 */
class PipelineWebhookProdDeployTest {

    private static final String REPO = "https://git.example.com/shop/order.git";

    private PipelineWebhookTriggerService service;
    private PipelineWebhookTriggerMapper triggerMapper;
    private PipelineRebuildService rebuildService;
    private BuildJob prodJob;

    @BeforeAll
    static void initMybatisLambda() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), PipelineWebhookTrigger.class);
    }

    @BeforeEach
    void setUp() {
        service = new PipelineWebhookTriggerService();
        triggerMapper = mock(PipelineWebhookTriggerMapper.class);
        rebuildService = mock(PipelineRebuildService.class);
        BuildJobMapper buildJobMapper = mock(BuildJobMapper.class);
        EnvMapper envMapper = mock(EnvMapper.class);
        ServiceMapper serviceMapper = mock(ServiceMapper.class);
        PipelineJobSupport support = mock(PipelineJobSupport.class);

        prodJob = new BuildJob();
        prodJob.setId(7L);
        prodJob.setTaskName("order-prod");
        prodJob.setServiceId(1L);
        prodJob.setEnvId(20L);
        when(buildJobMapper.selectById(7L)).thenReturn(prodJob);
        when(envMapper.selectById(20L)).thenReturn(env(20L, "prod", "prod"));
        Service svc = new Service();
        svc.setId(1L);
        svc.setGitRepo(REPO);
        when(serviceMapper.selectById(1L)).thenReturn(svc);
        when(support.resolveAnchorJob(prodJob)).thenReturn(prodJob);
        when(support.resolveLatestJobInGroup(prodJob)).thenReturn(prodJob);
        when(support.configuredGitType(prodJob)).thenReturn("branch");
        when(support.configuredBranch(prodJob)).thenReturn("main");
        when(support.getCurrentUsername()).thenReturn("alice");

        ReflectionTestUtils.setField(service, "webhookSecret", "test-webhook-secret");
        ReflectionTestUtils.setField(service, "buildJobMapper", buildJobMapper);
        ReflectionTestUtils.setField(service, "serviceMapper", serviceMapper);
        ReflectionTestUtils.setField(service, "envMapper", envMapper);
        ReflectionTestUtils.setField(service, "pipelineWebhookTriggerMapper", triggerMapper);
        ReflectionTestUtils.setField(service, "projectAccessService", mock(ProjectAccessService.class));
        ReflectionTestUtils.setField(service, "support", support);
        ReflectionTestUtils.setField(service, "rebuildService", rebuildService);
        ReflectionTestUtils.setField(service, "prodDeployPolicy", ProdDeployTestSupport.communityPolicy());
    }

    @AfterEach
    void tearDown() {
        ProdDeployTestSupport.logout();
    }


    @SuppressWarnings("unchecked")
    private Map<String, Object> push() {
        PipelineWebhookTrigger trigger = new PipelineWebhookTrigger();
        trigger.setAnchorJobId(7L);
        trigger.setEnabled(1);
        when(triggerMapper.selectList(any())).thenReturn(Collections.singletonList(trigger));
        Set<String> repoKeys = Collections.singleton(GitRepoKey.normalize(REPO));
        return service.triggerByGitPush(repoKeys, "main", "bob");
    }

    @Test
    void communityEnablesProdWebhookOnlyWithThePermission() {
        loginWith("pipeline:run", "pipeline:edit");
        assertThrows(BusinessException.class, () -> service.setWebhookEnabled(7L, true));
        verify(triggerMapper, never()).insert(any(PipelineWebhookTrigger.class));

        loginWith("pipeline:run", ProdDeployPolicy.PROD_DEPLOY_PERMISSION);
        assertDoesNotThrow(() -> service.setWebhookEnabled(7L, true));
        verify(triggerMapper).insert(any(PipelineWebhookTrigger.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void communityPushTriggersProdBuild() {
        BuildResponse response = new BuildResponse();
        response.setJobNumber("BUILD-8");
        when(rebuildService.rebuildForWebhook(eq(7L), anyString())).thenReturn(response);

        Map<String, Object> result = push();

        assertEquals(1, ((List<Object>) result.get("triggered")).size());
        assertEquals(0, ((List<Object>) result.get("skipped")).size());
    }

}
