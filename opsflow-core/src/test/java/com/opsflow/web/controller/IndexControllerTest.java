package com.opsflow.web.controller;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 根路径重定向到登录页：保留查询参数，只跳同源 /index.html */
class IndexControllerTest {

    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new IndexController()).build();

    @Test
    void 不带参数() throws Exception {
        mvc.perform(get("/")).andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/index.html"));
    }

    @Test
    void 带登录失败提示_原样带上() throws Exception {
        mvc.perform(get("/").queryParam("sso_error", "登录状态校验失败，请重试"))
                .andExpect(redirectedUrl("/index.html?sso_error=%E7%99%BB%E5%BD%95%E7%8A%B6%E6%80%81%E6%A0%A1%E9%AA%8C%E5%A4%B1%E8%B4%A5%EF%BC%8C%E8%AF%B7%E9%87%8D%E8%AF%95"));
    }

    @Test
    void 多个参数和重复参数() throws Exception {
        mvc.perform(get(java.net.URI.create("/?a=1&b=x%20y&a=2")))
                .andExpect(redirectedUrl("/index.html?a=1&a=2&b=x+y"));
    }

    @Test
    void 参数内容拼不出别的地址或响应头() throws Exception {
        String evil = "x&next=https://evil.example/\r\nSet-Cookie: a=b {name}";
        MvcResult result = mvc.perform(get("/").queryParam("sso_error", evil)).andReturn();
        String location = result.getResponse().getRedirectedUrl();
        assertTrue(location.startsWith("/index.html?sso_error="), location);
        assertEquals(1, location.split("&", -1).length, "参数值里的 & 必须被编码：" + location);
        assertFalse(location.contains("\r") || location.contains("\n") || location.contains("{") || location.contains("//evil"), location);
        assertEquals("/index.html?sso_error=x%26next%3Dhttps%3A%2F%2Fevil.example%2F%0D%0ASet-Cookie%3A+a%3Db+%7Bname%7D", location);
    }

    @Test
    void 参数名也编码_无值参数保留() throws Exception {
        mvc.perform(get(java.net.URI.create("/?%2F%2Fevil.example=1&flag")))
                .andExpect(redirectedUrl("/index.html?%2F%2Fevil.example=1&flag="));
    }
}
