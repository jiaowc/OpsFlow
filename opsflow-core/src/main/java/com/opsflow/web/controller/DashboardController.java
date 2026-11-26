package com.opsflow.web.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 控制台核心页面的路由：统一转发到 dashboard.html，由前端按路径展示对应模块。
 * <p>路径与前端路由表 {@code static/js/routes.js} 保持一致，{@code node scripts/dev/check-routes.js} 会核对两边。
 * 扩展页面（{@link com.opsflow.spi.UiExtension} 声明的菜单）的路由由 UiExtensionRouteConfig 注册。</p>
 */
@Controller
public class DashboardController {

    /** 统一转发目标：单页 dashboard，由前端路由切换模块 */
    private static final String DASHBOARD = "forward:/dashboard.html";

    @GetMapping({
        "/statistics",
        "/pipeline",
        "/projects",
        "/services",
        "/environments",
        "/clusters",
        "/nodes",
        "/users",
        "/pipeline-config",
        "/credentials",
        "/authentication",
        "/security"
    })
    public String page() {
        return DASHBOARD;
    }

    /** 兼容旧入口，重定向到流水线配置 */
    @GetMapping("/system")
    public String system() {
        return "redirect:/pipeline-config";
    }

    /** 兼容旧入口：LDAP 配置并入登录方式页（LDAP 是该页第一个页签，默认激活） */
    @GetMapping("/ldap")
    public String ldap() {
        return "redirect:/authentication";
    }
}
