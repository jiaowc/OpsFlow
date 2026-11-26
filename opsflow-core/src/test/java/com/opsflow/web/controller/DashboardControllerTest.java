package com.opsflow.web.controller;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class DashboardControllerTest {

    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new DashboardController()).build();

    @Test
    void 登录方式页转发到控制台() throws Exception {
        mvc.perform(get("/authentication"))
                .andExpect(status().isOk())
                .andExpect(forwardedUrl("/dashboard.html"));
    }

    @Test
    void 旧的ldap地址重定向到登录方式页() throws Exception {
        mvc.perform(get("/ldap"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/authentication"));
    }

    @Test
    void 旧的system地址仍重定向到流水线配置() throws Exception {
        mvc.perform(get("/system"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/pipeline-config"));
    }

    @Test
    void 安全设置页直接打开或刷新转发到单页() throws Exception {
        mvc.perform(get("/security")).andExpect(status().isOk()).andExpect(forwardedUrl("/dashboard.html"));
    }

    @Test
    void 其他核心页面照常转发() throws Exception {
        for (String path : new String[]{"/statistics", "/credentials", "/pipeline-config", "/users"}) {
            mvc.perform(get(path)).andExpect(forwardedUrl("/dashboard.html"));
        }
    }
}
