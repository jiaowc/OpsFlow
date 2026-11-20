package com.opsflow.web.controller;

import com.opsflow.service.impl.PipelineTemplateServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 模版变量检查接口：页面依赖这里返回的字段名，改名会让保存前的检查失效。
 */
class PipelineTemplateControllerTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        PipelineTemplateController controller = new PipelineTemplateController();
        // 变量检查不访问数据库，service 不需要注入 mapper
        ReflectionTestUtils.setField(controller, "pipelineTemplateService", new PipelineTemplateServiceImpl());
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void reportsUnknownVariableWithLineAndSuggestion() throws Exception {
        String body = "{\"type\":\"deployment\",\"content\":\"name: ${serviceName}\\nimage: ${imgae}\"}";

        mockMvc.perform(post("/api/pipeline-template/check-variables")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.passed").value(false))
                .andExpect(jsonPath("$.issues[0].placeholder").value("${imgae}"))
                .andExpect(jsonPath("$.issues[0].lines[0]").value(2))
                .andExpect(jsonPath("$.issues[0].suggestion").value("image"))
                .andExpect(jsonPath("$.issues[0].message").isNotEmpty())
                .andExpect(jsonPath("$.used[0].name").value("serviceName"))
                .andExpect(jsonPath("$.supported[0].name").isNotEmpty())
                .andExpect(jsonPath("$.supported[0].description").isNotEmpty());
    }

    @Test
    void passesWhenAllVariablesAreSupported() throws Exception {
        String body = "{\"type\":\"service\",\"content\":\"port: ${servicePort}\\ntype: ${serviceType}\"}";

        mockMvc.perform(post("/api/pipeline-template/check-variables")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.passed").value(true))
                .andExpect(jsonPath("$.issues").isEmpty())
                .andExpect(jsonPath("$.passthrough").isEmpty());
    }
}
