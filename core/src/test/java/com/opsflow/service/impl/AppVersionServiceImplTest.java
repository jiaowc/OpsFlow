package com.opsflow.service.impl;

import com.opsflow.spi.DefaultEditionInfo;
import com.opsflow.spi.EditionInfo;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * 免登录的版本接口只多返回版本名 edition；取不到时不返回，前端据此不显示源码入口。
 */
class AppVersionServiceImplTest {

    private static AppVersionServiceImpl withEdition(EditionInfo editionInfo) {
        AppVersionServiceImpl service = new AppVersionServiceImpl();
        ReflectionTestUtils.setField(service, "editionInfo", editionInfo);
        return service;
    }

    @Test
    void communityEditionIsReported() {
        Map<String, Object> info = withEdition(new DefaultEditionInfo()).getVersionInfo();
        assertEquals("community", info.get("edition"));
        assertEquals(Set.of("name", "version", "artifact", "buildTime", "edition"), info.keySet());
    }

    @Test
    void editionOmittedWhenUnavailable() {
        assertFalse(withEdition(null).getVersionInfo().containsKey("edition"));
    }

    @Test
    void editionOmittedWhenLookupFails() {
        EditionInfo broken = new EditionInfo() {
            @Override
            public String edition() {
                throw new IllegalStateException("boom");
            }

            @Override
            public Set<String> enabledFeatures() {
                return Set.of();
            }
        };
        assertFalse(withEdition(broken).getVersionInfo().containsKey("edition"));
    }
}
