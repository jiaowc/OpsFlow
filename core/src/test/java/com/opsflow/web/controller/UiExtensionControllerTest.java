package com.opsflow.web.controller;

import com.opsflow.spi.UiExtension;
import com.opsflow.web.interceptor.AuthInterceptor;
import com.opsflow.web.security.RequiresPermission;
import com.opsflow.web.ui.UiExtensionRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.method.HandlerMethod;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class UiExtensionControllerTest {

    private static MockMvc mvc(List<UiExtension> extensions) {
        UiExtensionController controller = new UiExtensionController();
        ReflectionTestUtils.setField(controller, "registry", new UiExtensionRegistry(extensions));
        return MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void 没有扩展时返回空列表() throws Exception {
        mvc(new ArrayList<>()).perform(get("/api/ui/extensions"))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"extensions\":[]}", true));
    }

    @Test
    void 返回扩展清单() throws Exception {
        UiExtension ext = new UiExtension() {
            @Override
            public String id() {
                return "report";
            }

            @Override
            public String feature() {
                return "reporting";
            }

            @Override
            public List<MenuItem> menuItems() {
                return Collections.singletonList(new MenuItem("report", "/report", "menu.report")
                        .permission("report:view").loader("loadReportPage"));
            }

            @Override
            public List<String> scripts() {
                return Collections.singletonList("/ext/report/report.js");
            }
        };
        mvc(Collections.singletonList(ext)).perform(get("/api/ui/extensions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.extensions[0].id").value("report"))
                .andExpect(jsonPath("$.extensions[0].feature").value("reporting"))
                .andExpect(jsonPath("$.extensions[0].menuItems[0].path").value("/report"))
                .andExpect(jsonPath("$.extensions[0].menuItems[0].permission").value("report:view"))
                .andExpect(jsonPath("$.extensions[0].menuItems[0].feature").value("reporting"))
                .andExpect(jsonPath("$.extensions[0].menuItems[0].loader").value("loadReportPage"))
                .andExpect(jsonPath("$.extensions[0].scripts[0]").value("/ext/report/report.js"))
                .andExpect(jsonPath("$.extensions[0].fragments").isEmpty())
                .andExpect(jsonPath("$.extensions[0].tabs").isEmpty());
    }

    @Test
    void 登录即可访问_未登录被拦截为401() throws Exception {
        assertNull(UiExtensionController.class.getAnnotation(RequiresPermission.class));
        assertNull(UiExtensionController.class.getMethod("extensions").getAnnotation(RequiresPermission.class));

        AuthInterceptor interceptor = new AuthInterceptor();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/ui/extensions");
        MockHttpServletResponse response = new MockHttpServletResponse();
        HandlerMethod handler = new HandlerMethod(new UiExtensionController(),
                UiExtensionController.class.getMethod("extensions"));
        assertFalse(interceptor.preHandle(request, response, handler));
        assertEquals(401, response.getStatus());
    }

    @Test
    void 清单输出页签() throws Exception {
        UiExtension ext = new UiExtension() {
            @Override
            public String id() {
                return "extra";
            }

            @Override
            public List<Tab> tabs() {
                return Collections.singletonList(new Tab("settings", "extra-tab", "tab.extra")
                        .fragment("/ext/extra/tab.html").feature("feat-extra").permission("system:config")
                        .order(20).loader("loadExtraTab"));
            }
        };
        mvc(Collections.singletonList(ext)).perform(get("/api/ui/extensions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.extensions[0].id").value("extra"))
                .andExpect(jsonPath("$.extensions[0].menuItems").isEmpty())
                .andExpect(jsonPath("$.extensions[0].tabs[0].section").value("settings"))
                .andExpect(jsonPath("$.extensions[0].tabs[0].tab").value("extra-tab"))
                .andExpect(jsonPath("$.extensions[0].tabs[0].labelKey").value("tab.extra"))
                .andExpect(jsonPath("$.extensions[0].tabs[0].fragment").value("/ext/extra/tab.html"))
                .andExpect(jsonPath("$.extensions[0].tabs[0].feature").value("feat-extra"))
                .andExpect(jsonPath("$.extensions[0].tabs[0].permission").value("system:config"))
                .andExpect(jsonPath("$.extensions[0].tabs[0].order").value(20))
                .andExpect(jsonPath("$.extensions[0].tabs[0].loader").value("loadExtraTab"));
    }
}
