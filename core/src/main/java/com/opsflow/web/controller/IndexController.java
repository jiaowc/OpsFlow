package com.opsflow.web.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

import javax.servlet.http.HttpServletRequest;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.StringJoiner;

/**
 * 首页控制器
 */
@Controller
public class IndexController {

    private static final String LOGIN_PAGE = "/index.html";

    /**
     * 根路径重定向到登录页，查询参数原样带上（如扩展登录失败时的错误提示）。
     * <p>目标固定为同源的 {@value #LOGIN_PAGE}，不取请求里的路径或主机；参数名和值解码后重新编码，
     * 不会因为参数内容拼出别的地址或响应头。</p>
     */
    @GetMapping("/")
    public String index(HttpServletRequest request) throws UnsupportedEncodingException {
        return "redirect:" + loginUrl(request.getParameterMap());
    }

    static String loginUrl(Map<String, String[]> params) throws UnsupportedEncodingException {
        if (params == null || params.isEmpty()) {
            return LOGIN_PAGE;
        }
        StringJoiner query = new StringJoiner("&");
        for (Map.Entry<String, String[]> entry : params.entrySet()) {
            String name = URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8.name());
            String[] values = entry.getValue();
            if (values == null || values.length == 0) {
                query.add(name);
                continue;
            }
            for (String value : values) {
                query.add(name + "=" + URLEncoder.encode(value != null ? value : "", StandardCharsets.UTF_8.name()));
            }
        }
        return LOGIN_PAGE + "?" + query;
    }
}
