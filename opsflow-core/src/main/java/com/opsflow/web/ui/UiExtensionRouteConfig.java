package com.opsflow.web.ui;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 扩展菜单的页面路径（如 /audit）转发到 dashboard.html，由前端路由展示对应区块。
 * 核心页面的路径在 DashboardController 里，两边由 {@link UiExtensionRegistry} 去重之外不做冲突检查：
 * 与核心路径重名时 DashboardController 的 @GetMapping 优先。
 */
@Configuration
public class UiExtensionRouteConfig implements WebMvcConfigurer {

    static final String DASHBOARD = "forward:/dashboard.html";

    @Autowired
    private UiExtensionRegistry registry;

    @Override
    public void addViewControllers(ViewControllerRegistry viewControllers) {
        for (String path : registry.pagePaths()) {
            viewControllers.addViewController(path).setViewName(DASHBOARD);
        }
    }
}
