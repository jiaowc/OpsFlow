package com.opsflow.web.controller;

import com.opsflow.api.dto.SystemConfigDTO;
import com.opsflow.dao.mapper.SystemConfigMapper;
import com.opsflow.dao.model.SystemConfig;
import com.opsflow.service.SystemConfigService;
import com.opsflow.spi.SystemConfigExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 通用配置接口的写入转换、读取转换：默认原样透传，实现了的扩展在 save/batch/list/get 四个入口都生效 */
class SystemConfigControllerExtensionTest {

    private final SystemConfigController controller = new SystemConfigController();
    private final SystemConfigService configService = mock(SystemConfigService.class);
    private final SystemConfigMapper mapper = mock(SystemConfigMapper.class);

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(controller, "systemConfigService", configService);
        ReflectionTestUtils.setField(controller, "systemConfigMapper", mapper);
        when(configService.get("demo", "token")).thenReturn("OLD");
        when(mapper.selectList(any())).thenReturn(Arrays.asList(row("demo", "token", "STORED"), row("demo", "url", "http://x")));
        when(mapper.selectOne(any())).thenReturn(row("demo", "token", "STORED"));
    }

    @Test
    void 没实现新方法的扩展_四个入口行为不变() {
        SystemConfigExtension legacy = new SystemConfigExtension() {
            @Override
            public void beforeSave(String configType) {
            }
        };
        use(legacy);

        assertEquals("STORED", controller.saveConfig(dto("demo", "token", "v1")).getConfigValue());
        verify(configService).save("demo", "token", "v1", null);

        controller.saveBatch(batch("demo", "token", "v2"));
        verify(configService).saveBatch("demo", Collections.singletonMap("token", "v2"));

        assertEquals(Arrays.asList("STORED", "http://x"), values(controller.listConfigs("demo")));
        assertEquals(Arrays.asList("STORED", "http://x"), values(controller.getConfigsByType("demo")));
    }

    @Test
    void 实现了的扩展_写入拿到库里原值_读取在四个入口都转换() {
        use(new Tagging("A"));

        assertEquals("A-masked", controller.saveConfig(dto(" demo ", " token ", "v1")).getConfigValue());
        verify(configService).save(eq(" demo "), eq(" token "), eq("A(v1|OLD)"), any());

        controller.saveBatch(batch("demo", "token", "v2"));
        verify(configService).saveBatch("demo", Collections.singletonMap("token", "A(v2|OLD)"));

        assertEquals(Arrays.asList("A-masked", "http://x"), values(controller.listConfigs(null)));
        assertEquals(Arrays.asList("A-masked", "http://x"), values(controller.getConfigsByType("demo")));
    }

    @Test
    void 多个扩展依次串联() {
        use(new Tagging("A"), new Tagging("B"));
        controller.saveBatch(batch("demo", "token", "v"));
        verify(configService).saveBatch("demo", Collections.singletonMap("token", "B(A(v|OLD)|OLD)"));
        assertEquals("A-masked", values(controller.getConfigsByType("demo")).get(0).substring(0, 8));
    }

    /** 只处理 demo.token：写入包一层标记，读取返回固定值 */
    private static final class Tagging implements SystemConfigExtension {
        private final String tag;

        Tagging(String tag) {
            this.tag = tag;
        }

        @Override
        public String beforeStore(String configType, String configKey, String value, String storedValue) {
            return "demo".equals(configType) && "token".equals(configKey) ? tag + "(" + value + "|" + storedValue + ")" : value;
        }

        @Override
        public String maskForRead(String configType, String configKey, String storedValue) {
            if (!"demo".equals(configType) || !"token".equals(configKey)) {
                return storedValue;
            }
            return storedValue.endsWith("-masked") ? storedValue : tag + "-masked";
        }
    }

    private void use(SystemConfigExtension... extensions) {
        ReflectionTestUtils.setField(controller, "extensions", Arrays.asList(extensions));
    }

    private static List<String> values(List<SystemConfigDTO> list) {
        return list.stream().map(SystemConfigDTO::getConfigValue).collect(java.util.stream.Collectors.toList());
    }

    private static SystemConfigDTO dto(String type, String key, String value) {
        SystemConfigDTO dto = new SystemConfigDTO();
        dto.setConfigType(type);
        dto.setConfigKey(key);
        dto.setConfigValue(value);
        return dto;
    }

    private static Map<String, Object> batch(String type, String key, String value) {
        Map<String, Object> body = new HashMap<>();
        body.put("configType", type);
        body.put("values", Collections.singletonMap(key, value));
        return body;
    }

    private static SystemConfig row(String type, String key, String value) {
        SystemConfig c = new SystemConfig();
        c.setConfigType(type);
        c.setConfigKey(key);
        c.setConfigValue(value);
        return c;
    }
}
