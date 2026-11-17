package com.opsflow.service.impl.deploy;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.opsflow.dao.mapper.ServiceMapper;
import com.opsflow.dao.model.Service;
import com.opsflow.integration.harbor.HarborCredentialService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 上线模块镜像地址规范化：记录从 DeployTaskController 搬出来时的既有行为
 */
class DeployModuleImageResolverTest {

    private DeployModuleImageResolver resolver;
    private HarborCredentialService harborCredentialService;
    private ServiceMapper serviceMapper;

    @BeforeEach
    void setUp() {
        resolver = new DeployModuleImageResolver();
        harborCredentialService = mock(HarborCredentialService.class);
        serviceMapper = mock(ServiceMapper.class);
        ReflectionTestUtils.setField(resolver, "harborCredentialService", harborCredentialService);
        ReflectionTestUtils.setField(resolver, "serviceMapper", serviceMapper);
    }

    private List<String> normalize(String registry, String... modules) {
        when(harborCredentialService.getRegistryHost(null)).thenReturn(registry);
        return resolver.normalizeDeployModules(Arrays.asList(modules));
    }

    @Test
    void relativePathIsPrefixedWithRegistry() {
        assertEquals(Collections.singletonList("harbor.example.com/prod/order:1.2.0"),
                normalize("harbor.example.com", "prod/order:1.2.0"));
    }

    @Test
    void alreadyFullAddressIsKept() {
        assertEquals(Collections.singletonList("harbor.example.com/prod/order:1.2.0"),
                normalize("harbor.example.com", "harbor.example.com/prod/order:1.2.0"));
    }

    @Test
    void addressWithAnotherRegistryHostIsKept() {
        // 首段含 . 或 : 视为已带 registry，不再拼接默认仓库
        assertEquals(Arrays.asList("other.registry.io/prod/order:1.2.0", "10.0.0.5:5000/prod/order:1.2.0"),
                normalize("harbor.example.com", "other.registry.io/prod/order:1.2.0", "10.0.0.5:5000/prod/order:1.2.0"));
    }

    @Test
    void localhostRegistryWithoutDotIsStillPrefixedUnlessItMatches() {
        // 本地 Harbor 的 registry 是 localhost：以它开头的不变；其他相对路径照常补全
        assertEquals(Arrays.asList("localhost/dev/ruoyi-vue:1", "localhost/dev/ruoyi-ui:2"),
                normalize("localhost", "localhost/dev/ruoyi-vue:1", "dev/ruoyi-ui:2"));
    }

    @Test
    void blankEntriesAreSkippedAndValuesTrimmed() {
        assertEquals(Collections.singletonList("harbor.example.com/prod/order:1.2.0"),
                normalize("harbor.example.com", "  ", "", "  prod/order:1.2.0  "));
    }

    @Test
    void withoutRegistryModulesAreReturnedAsIs() {
        assertEquals(Collections.singletonList("prod/order:1.2.0"), normalize(null, " prod/order:1.2.0 "));
        assertEquals(Collections.singletonList("prod/order:1.2.0"), normalize("  ", "prod/order:1.2.0"));
    }

    @Test
    void nullOrEmptyListIsReturnedUntouched() {
        assertNull(resolver.normalizeDeployModules(null));
        List<String> empty = Collections.emptyList();
        assertSame(empty, resolver.normalizeDeployModules(empty));
        verify(harborCredentialService, never()).getRegistryHost(any());
    }

    @Test
    void serviceCodeIsLastPathSegmentWithoutTag() {
        assertEquals("order", resolver.extractServiceCode("harbor.example.com/prod/order:1.2.0"));
        assertEquals("order", resolver.extractServiceCode("prod/order:1.2.0"));
        assertEquals("order", resolver.extractServiceCode(" order:1.2.0 "));
        assertEquals("order", resolver.extractServiceCode("prod/order"));
        assertNull(resolver.extractServiceCode(null));
        assertNull(resolver.extractServiceCode("   "));
    }

    @Test
    @SuppressWarnings("unchecked")
    void serviceIsFoundByCodeFirst() {
        Service byCode = new Service();
        when(serviceMapper.selectOne(any(Wrapper.class))).thenReturn(byCode);
        assertSame(byCode, resolver.findServiceByCode("order"));
        verify(serviceMapper, times(1)).selectOne(any(Wrapper.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void serviceFallsBackToNameWhenCodeDoesNotMatch() {
        Service byName = new Service();
        when(serviceMapper.selectOne(any(Wrapper.class))).thenReturn(null, byName);
        assertSame(byName, resolver.findServiceByCode("order"));
        verify(serviceMapper, times(2)).selectOne(any(Wrapper.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void blankCodeDoesNotQuery() {
        assertNull(resolver.findServiceByCode(" "));
        assertNull(resolver.findServiceByCode(null));
        verify(serviceMapper, never()).selectOne(any(Wrapper.class));
    }
}
