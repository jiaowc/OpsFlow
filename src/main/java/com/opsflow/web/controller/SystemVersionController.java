package com.opsflow.web.controller;

import com.opsflow.service.AppVersionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 系统版本接口（免登录），便于页面展示与运维核对。
 */
@RestController
@RequestMapping("/api/system")
public class SystemVersionController {

    @Autowired
    private AppVersionService appVersionService;

    @GetMapping("/version")
    public Map<String, Object> version() {
        return appVersionService.getVersionInfo();
    }
}
