package com.opsflow.web.controller;

import com.opsflow.web.ui.UiExtensionRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 前端扩展清单。登录后即可访问（不需要额外权限），所有用户拿到同一份，只含已开通功能的扩展；
 * 菜单可见性由前端按 data-feature / data-permission 判断，业务 API 各自鉴权。
 */
@RestController
@RequestMapping("/api/ui")
public class UiExtensionController {

    @Autowired
    private UiExtensionRegistry registry;

    @GetMapping("/extensions")
    public Map<String, Object> extensions() {
        return registry.manifest();
    }

    /** 登录页扩展（登录前可访问，见 WebMvcConfig），只含静态资源地址 */
    @GetMapping("/login-extensions")
    public Map<String, Object> loginExtensions() {
        return registry.loginManifest();
    }
}
