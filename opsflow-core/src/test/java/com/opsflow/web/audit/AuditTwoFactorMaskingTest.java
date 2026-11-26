package com.opsflow.web.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opsflow.dao.model.AuditLog;
import com.opsflow.service.AuditLogService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/** 两步验证、安全设置、重置、单点登录测试连接的审计：不记明文动态码和恢复码，动作名是中文 */
class AuditTwoFactorMaskingTest {

    private final AuditLogService service = mock(AuditLogService.class);
    private final AuditLogInterceptor interceptor = new AuditLogInterceptor();

    AuditTwoFactorMaskingTest() {
        ReflectionTestUtils.setField(interceptor, "auditLogService", service);
        ReflectionTestUtils.setField(interceptor, "objectMapper", new ObjectMapper());
    }

    @Test
    void 动态码和恢复码不落明文_输错的也一样() {
        AuditLog verify = record("/api/auth/two-factor/verify", body("code", "764359"));
        assertEquals("{\"code\":\"******\"}", verify.getRequestSummary());
        assertEquals("两步验证登录", verify.getAction());

        AuditLog recovery = record("/api/auth/two-factor/verify", body("recoveryCode", "stpp-6qbn"));
        assertEquals("{\"recoveryCode\":\"******\"}", recovery.getRequestSummary());

        for (String path : new String[]{"/api/auth/two-factor/enroll/confirm", "/api/auth/two-factor/recovery-codes/regenerate",
                "/api/auth/two-factor/replace", "/api/auth/two-factor/disable"}) {
            Map<String, Object> b = body("code", "123456");
            b.put("recoveryCode", "abcd-efgh");
            String summary = record(path, b).getRequestSummary();
            assertFalse(summary.contains("123456") || summary.contains("abcd-efgh"), path + " → " + summary);
        }
    }

    @Test
    void 恢复码列表_绑定地址_密钥不论类型都打码() {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("recoveryCodes", Arrays.asList("aaaa-bbbb", "cccc-dddd"));
        b.put("otpauthUri", "otpauth://totp/OpsFlow:alice?secret=JBSWY3DPEHPK3PXP");
        b.put("secret", "JBSWY3DPEHPK3PXP");
        String summary = record("/api/auth/two-factor/enroll/confirm", b).getRequestSummary();
        assertFalse(summary.contains("aaaa") || summary.contains("JBSWY3DP"), summary);
        assertEquals("{\"recoveryCodes\":\"******\",\"otpauthUri\":\"******\",\"secret\":\"******\"}", summary);
    }

    @Test
    void 其他模块的code是业务编码_照常记录() {
        assertEquals("{\"code\":\"DEV\"}", record("/api/role/create", body("code", "DEV")).getRequestSummary());
    }

    @Test
    void 其他新接口的动作名() {
        assertEquals("开始绑定两步验证", record("/api/auth/two-factor/enroll/start", null).getAction());
        assertEquals("确认绑定两步验证", record("/api/auth/two-factor/enroll/confirm", null).getAction());
        assertEquals("重新生成恢复码", record("/api/auth/two-factor/recovery-codes/regenerate", null).getAction());
        assertEquals("更换两步验证设备", record("/api/auth/two-factor/replace", null).getAction());
        assertEquals("关闭两步验证", record("/api/auth/two-factor/disable", null).getAction());
        assertEquals("重置两步验证", record("/api/user/12/two-factor/reset", null).getAction());

        AuditLog test = record("/api/system/sso/demo/test", body("secret", "s"));
        assertEquals("测试单点登录连接", test.getAction());
        assertNull(test.getRequestSummary(), "系统配置模块只记路径");

        Map<String, Object> security = new HashMap<>();
        security.put("configType", "security");
        security.put("values", body("twoFactorPolicy", "required"));
        assertEquals("保存安全设置", record("/api/system/config/batch", security).getAction());
        Map<String, Object> notify = new HashMap<>();
        notify.put("configType", "notify");
        assertEquals("批量操作", record("/api/system/config/batch", notify).getAction());

        // 原有映射不受影响
        assertEquals("执行构建", AuditActionResolver.actionName("POST", "/api/task/3/start"));
        assertEquals("登录", AuditActionResolver.actionName("POST", "/api/auth/login"));
    }

    @Test
    void 响应体不写进审计_失败时只记固定提示() {
        MockHttpServletRequest request = request("/api/auth/two-factor/verify", body("code", "764359"));
        request.setAttribute(AuditResponseBodyAdvice.ATTR_FAILED, Boolean.TRUE);
        request.setAttribute(AuditLogInterceptor.ATTR_ERROR_MESSAGE, "动态码不正确");
        AuditLog log = run(request);
        assertEquals(0, log.getSuccess());
        assertEquals("动态码不正确", log.getErrorMessage());
        assertFalse(String.valueOf(log).contains("764359"));
    }

    private AuditLog record(String path, Object body) {
        return run(request(path, body));
    }

    private AuditLog run(MockHttpServletRequest request) {
        org.mockito.Mockito.clearInvocations(service);
        MockHttpServletResponse response = new MockHttpServletResponse();
        assertTrue(interceptor.preHandle(request, response, null));
        interceptor.afterCompletion(request, response, null, null);
        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(service).record(captor.capture());
        return captor.getValue();
    }

    private static MockHttpServletRequest request(String path, Object body) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
        if (body != null) {
            request.setAttribute(AuditRequestBodyAdvice.ATTR_BODY, body);
        }
        return request;
    }

    private static Map<String, Object> body(String key, Object value) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(key, value);
        return m;
    }
}
