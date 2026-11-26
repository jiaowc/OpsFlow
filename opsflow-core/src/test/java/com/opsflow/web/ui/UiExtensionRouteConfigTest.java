package com.opsflow.web.ui;

import com.opsflow.spi.UiExtension;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.handler.SimpleUrlHandlerMapping;
import org.springframework.web.servlet.mvc.ParameterizableViewController;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class UiExtensionRouteConfigTest {

    private static UiExtension ext(String id, String path) {
        return new UiExtension() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public List<MenuItem> menuItems() {
                return Collections.singletonList(new MenuItem(id, path, "menu." + id));
            }
        };
    }

    private static SimpleUrlHandlerMapping routes(List<UiExtension> extensions) {
        UiExtensionRouteConfig config = new UiExtensionRouteConfig();
        ReflectionTestUtils.setField(config, "registry", new UiExtensionRegistry(extensions));
        ViewControllerRegistry viewControllers = new ViewControllerRegistry(new org.springframework.context.support.StaticApplicationContext());
        config.addViewControllers(viewControllers);
        return ReflectionTestUtils.invokeMethod(viewControllers, "buildHandlerMapping");
    }

    @Test
    void 扩展页面路径转发到dashboard_不合法路径不注册() {
        SimpleUrlHandlerMapping mapping = routes(Arrays.asList(ext("report", "/report"), ext("bad", "/bad/../x")));
        assertEquals(Collections.singleton("/report"), mapping.getUrlMap().keySet());
        ParameterizableViewController controller = (ParameterizableViewController) mapping.getUrlMap().get("/report");
        assertEquals("forward:/dashboard.html", controller.getViewName());
    }

    @Test
    void 无扩展时不注册任何路由() {
        assertNull(routes(Collections.emptyList()));
    }
}
