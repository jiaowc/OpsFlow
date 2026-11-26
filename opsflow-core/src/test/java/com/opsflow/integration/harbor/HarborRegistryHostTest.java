package com.opsflow.integration.harbor;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 从 Harbor 组件地址、镜像全名里取出仓库主机（docker login 的目标）。
 */
class HarborRegistryHostTest {

    @Test
    void registryHostFromComponentUrl() {
        assertEquals("harbor.local", HarborCredentialService.extractRegistryHost("https://harbor.local"));
        assertEquals("harbor.local:8443", HarborCredentialService.extractRegistryHost(" http://harbor.local:8443/harbor/projects "));
        assertEquals("harbor.local", HarborCredentialService.extractRegistryHost("harbor.local/library"));
        assertEquals("harbor.local", HarborCredentialService.extractRegistryHost("harbor.local"));
    }

    @Test
    void blankComponentUrlHasNoHost() {
        assertNull(HarborCredentialService.extractRegistryHost(null));
        assertNull(HarborCredentialService.extractRegistryHost("   "));
        assertNull(HarborCredentialService.extractRegistryHost("https://"));
    }

    @Test
    void registryFromImageIsTheFirstPathSegment() {
        assertEquals("harbor.local", HarborCredentialService.extractRegistryFromImage("harbor.local/dev/ruoyi-vue:v1"));
        assertEquals("harbor.local:8443",
                HarborCredentialService.extractRegistryFromImage(" harbor.local:8443/dev/ruoyi-vue:v1 "));
        assertEquals("harbor.local", HarborCredentialService.extractRegistryFromImage("https://harbor.local/dev/app:1"));
    }

    @Test
    void imageWithoutSlashHasNoRegistry() {
        // 例如直接写 nginx:1.27：调用方会回退到组件里配置的 Harbor 地址
        assertNull(HarborCredentialService.extractRegistryFromImage("nginx:1.27"));
        assertNull(HarborCredentialService.extractRegistryFromImage("/dev/app:1"));
        assertNull(HarborCredentialService.extractRegistryFromImage(null));
        assertNull(HarborCredentialService.extractRegistryFromImage("  "));
    }
}
