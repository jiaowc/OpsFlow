package com.opsflow.web.controller;

import com.opsflow.api.dto.SystemConfigDTO;
import com.opsflow.common.exception.BusinessException;
import com.opsflow.dao.mapper.SystemConfigMapper;
import com.opsflow.dao.model.SystemConfig;
import com.opsflow.service.SystemConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * LDAP 配置只能走专用接口（负责加密绑定密码）；通用配置接口不能写，列表里绑定密码打码。
 */
class SystemConfigControllerLdapGuardTest {

    private SystemConfigController controller;
    private SystemConfigService configService;
    private SystemConfigMapper mapper;

    @BeforeEach
    void setUp() {
        controller = new SystemConfigController();
        configService = mock(SystemConfigService.class);
        mapper = mock(SystemConfigMapper.class);
        ReflectionTestUtils.setField(controller, "systemConfigService", configService);
        ReflectionTestUtils.setField(controller, "systemConfigMapper", mapper);
    }

    private static SystemConfig row(String type, String key, String value) {
        SystemConfig c = new SystemConfig();
        c.setConfigType(type);
        c.setConfigKey(key);
        c.setConfigValue(value);
        return c;
    }

    @Test
    void genericWritesOfLdapTypeAreRejected() {
        SystemConfigDTO dto = new SystemConfigDTO();
        dto.setConfigType("LDAP");
        dto.setConfigKey("bindPassword");
        dto.setConfigValue("plain");
        assertThrows(BusinessException.class, () -> controller.saveConfig(dto));

        Map<String, Object> batch = new HashMap<>();
        batch.put("configType", " ldap ");
        batch.put("values", Map.of("bindPassword", "plain"));
        assertThrows(BusinessException.class, () -> controller.saveBatch(batch));

        verify(configService, never()).save(anyString(), anyString(), anyString(), any());
        verify(configService, never()).saveBatch(anyString(), any());
    }

    @Test
    void twoFactorPolicyOnlyAcceptsKnownValues() {
        SystemConfigDTO dto = new SystemConfigDTO();
        dto.setConfigType("security");
        dto.setConfigKey("twoFactorPolicy");
        dto.setConfigValue("always");
        assertThrows(BusinessException.class, () -> controller.saveConfig(dto));

        Map<String, Object> bad = new HashMap<>();
        bad.put("configType", "security");
        bad.put("values", Map.of("twoFactorPolicy", ""));
        assertThrows(BusinessException.class, () -> controller.saveBatch(bad));
        verify(configService, never()).saveBatch(anyString(), any());

        Map<String, Object> good = new HashMap<>();
        good.put("configType", "security");
        good.put("values", Map.of("twoFactorPolicy", "required"));
        controller.saveBatch(good);
        verify(configService).saveBatch("security", Map.of("twoFactorPolicy", "required"));
    }

    @Test
    void listMasksLdapBindPassword() {
        List<SystemConfig> rows = Arrays.asList(
                row("ldap", "bindPassword", "enc:v1:abc"),
                row("ldap", "url", "ldap://x"),
                row("other", "bindPassword", "keep"));
        when(mapper.selectList(any())).thenReturn(rows);

        List<SystemConfigDTO> list = controller.listConfigs(null);
        assertEquals("******", list.get(0).getConfigValue());
        assertEquals("ldap://x", list.get(1).getConfigValue());
        assertEquals("keep", list.get(2).getConfigValue());
        assertEquals("******", controller.getConfigsByType("ldap").get(0).getConfigValue());
    }
}
