package com.opsflow.service.impl.deploy;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opsflow.common.constant.BuildStatus;
import com.opsflow.common.exception.BusinessException;
import com.opsflow.dao.mapper.BuildJobMapper;
import com.opsflow.dao.mapper.EnvMapper;
import com.opsflow.dao.mapper.ServiceMapper;
import com.opsflow.dao.model.BuildJob;
import com.opsflow.dao.model.DeployTask;
import com.opsflow.dao.model.Env;
import com.opsflow.dao.model.Pipeline;
import com.opsflow.dao.model.Service;
import com.opsflow.integration.harbor.HarborCredentialService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CD Job 的创建：模块解析、环境匹配、流水线参数。
 * 这里用真实的 {@link DeployModuleImageResolver}，连同 CD 侧的严格镜像解析一起验证。
 */
@SuppressWarnings("unchecked")
class CdJobFactoryTest {

    private CdJobFactory factory;
    private DeployModuleImageResolver resolver;
    private EnvMapper envMapper;
    private BuildJobMapper buildJobMapper;
    private ServiceMapper serviceMapper;

    @BeforeEach
    void setUp() {
        envMapper = mock(EnvMapper.class);
        buildJobMapper = mock(BuildJobMapper.class);
        serviceMapper = mock(ServiceMapper.class);
        resolver = new DeployModuleImageResolver();
        ReflectionTestUtils.setField(resolver, "serviceMapper", serviceMapper);
        ReflectionTestUtils.setField(resolver, "harborCredentialService", mock(HarborCredentialService.class));
        factory = new CdJobFactory();
        ReflectionTestUtils.setField(factory, "envMapper", envMapper);
        ReflectionTestUtils.setField(factory, "buildJobMapper", buildJobMapper);
        ReflectionTestUtils.setField(factory, "deployModuleImageResolver", resolver);
    }

    private static DeployTask task() {
        DeployTask task = new DeployTask();
        task.setId(1L);
        task.setTaskName("订单上线");
        task.setClusterId(2L);
        task.setK8sNamespace(" prod ");
        task.setCreatorId(7L);
        task.setCreatorName("alice");
        return task;
    }

    private static Env env(long id) {
        Env env = new Env();
        env.setId(id);
        return env;
    }

    private static Pipeline pipeline() {
        Pipeline pipeline = new Pipeline();
        pipeline.setId(4L);
        return pipeline;
    }

    private void givenService(Integer port) {
        Service service = new Service();
        service.setId(100L);
        service.setCode("order");
        service.setServicePort(port);
        when(serviceMapper.selectOne(any(Wrapper.class))).thenReturn(service);
    }

    // ---------- 模块解析（严格规则） ----------

    @Test
    void parsesRelativeAndFullModuleAddresses() {
        DeployModuleImageResolver.ParsedModule relative = resolver.parseModule(" prod/order:1.2.0 ");
        assertEquals("prod", relative.project);
        assertEquals("order", relative.serviceCode);
        assertEquals("1.2.0", relative.version);
        assertNull(relative.imageFullName);

        DeployModuleImageResolver.ParsedModule full = resolver.parseModule("harbor.example.com/a/prod/order:1.2.0");
        assertEquals("harbor.example.com/a", full.registry);
        assertEquals("prod", full.project);
        assertEquals("harbor.example.com/a/prod/order:1.2.0", full.imageFullName);
    }

    @Test
    void rejectsMalformedModuleAddresses() {
        for (String bad : new String[]{"order", "prod/order", "order:1.0", "prod/order:", ":1.0", null}) {
            BusinessException e = assertThrows(BusinessException.class, () -> resolver.parseModule(bad), String.valueOf(bad));
            assertTrue(e.getMessage().startsWith("上线模块格式无效"), e.getMessage());
        }
        assertEquals("上线模块不完整: /order:1.0",
                assertThrows(BusinessException.class, () -> resolver.parseModule("/order:1.0")).getMessage());
    }

    @Test
    void completesRelativeAddressWithDefaultRegistryOrFails() {
        assertEquals("harbor.example.com/prod/order:1.2.0",
                resolver.resolveImageFullName("prod/order:1.2.0", " harbor.example.com "));
        assertEquals("other.io/prod/order:1.2.0",
                resolver.resolveImageFullName("other.io/prod/order:1.2.0", "harbor.example.com"));
        assertEquals("Harbor 仓库地址未配置，无法拼接完整镜像地址", assertThrows(BusinessException.class,
                () -> resolver.resolveImageFullName("prod/order:1.2.0", " ")).getMessage());
    }

    // ---------- 环境匹配 ----------

    @Test
    void resolvesEnvByClusterAndNamespaceFirst() {
        Env first = env(9L);
        when(envMapper.selectList(any(Wrapper.class))).thenReturn(Arrays.asList(first, env(10L)));
        assertSame(first, factory.resolveEnv(task()));
        verify(envMapper, never()).selectById(any());
    }

    @Test
    void fallsBackToStoredEnvIdsWhenNoExactMatch() {
        when(envMapper.selectList(any(Wrapper.class))).thenReturn(Collections.emptyList());
        Env stored = env(12L);
        when(envMapper.selectById(12L)).thenReturn(stored);
        DeployTask task = task();
        task.setDeployEnvs("[11,12]");
        assertSame(stored, factory.resolveEnv(task), "跳过已不存在的环境 ID，取第一个存在的");

        task.setDeployEnvs("not-json");
        assertNull(factory.resolveEnv(task));
    }

    @Test
    void envIsNotResolvedWithoutClusterOrNamespace() {
        DeployTask noCluster = task();
        noCluster.setClusterId(null);
        assertNull(factory.resolveEnv(noCluster));

        DeployTask noNamespace = task();
        noNamespace.setK8sNamespace(" ");
        noNamespace.setDeployEnvs("[9]");
        assertNull(factory.resolveEnv(noNamespace), "缺 Namespace 时不走环境 ID 回退");
        verify(envMapper, never()).selectList(any(Wrapper.class));
    }

    // ---------- 建 Job ----------

    @Test
    void createsPendingJobWithPipelineParameters() throws Exception {
        givenService(9000);

        BuildJob job = factory.createCdJob(task(), pipeline(), env(9L), "prod/order:1.2.0", "harbor.example.com", 2);

        verify(buildJobMapper).insert(job);
        assertEquals(BuildStatus.PENDING, job.getStatus());
        assertEquals("订单上线 / order", job.getTaskName());
        assertEquals(100L, job.getServiceId());
        assertEquals(9L, job.getEnvId());
        assertEquals(1L, job.getDeployTaskId());
        assertEquals(4L, job.getPipelineTemplateId());
        assertEquals("tag", job.getGitType());
        assertEquals("1.2.0", job.getBranch());
        assertEquals("1.2.0", job.getImageTag());
        assertEquals("harbor.example.com/prod/order:1.2.0", job.getImageFullName());

        JsonNode params = new ObjectMapper().readTree(job.getBuildParameters());
        assertEquals("harbor.example.com/prod/order:1.2.0", params.get("imageFullName").asText());
        assertEquals("harbor.example.com/prod/order:1.2.0", params.get("image").asText());
        assertEquals("prod", params.get("harborProject").asText());
        assertEquals(2, params.get("deploySequence").asInt());
        assertEquals(2L, params.get("clusterId").asLong());
        assertEquals(" prod ", params.get("k8sNamespace").asText(), "按任务里存的值原样传递");
        assertEquals("9000", params.get("servicePort").asText());
        assertEquals("9000", params.get("port").asText());
        assertEquals("order", params.get("serviceName").asText());
        assertEquals("order", params.get("serviceCode").asText());
    }

    @Test
    void portDefaultsTo8080WhenServiceHasNone() throws Exception {
        givenService(null);
        BuildJob job = factory.createCdJob(task(), pipeline(), env(9L), "prod/order:1.2.0", "harbor.example.com", 1);
        assertEquals("8080", new ObjectMapper().readTree(job.getBuildParameters()).get("port").asText());
    }

    @Test
    void failsWhenServiceCannotBeFound() {
        when(serviceMapper.selectOne(any(Wrapper.class))).thenReturn(null);
        assertEquals("找不到服务: order（模块: prod/order:1.2.0）", assertThrows(BusinessException.class,
                () -> factory.createCdJob(task(), pipeline(), env(9L), "prod/order:1.2.0", "harbor.example.com", 1)).getMessage());
        verify(buildJobMapper, never()).insert(any());
    }
}
