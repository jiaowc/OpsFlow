package com.opsflow.web.controller;

import com.opsflow.spi.EditionInfo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 当前版本和已启用的功能码。登录后即可访问（不需要额外权限），前端据此显示或隐藏商业功能入口。
 */
@RestController
@RequestMapping("/api/edition")
public class EditionController {

    @Autowired
    private EditionInfo editionInfo;

    @GetMapping
    public Map<String, Object> edition() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("edition", editionInfo.edition());
        result.put("features", new ArrayList<>(editionInfo.enabledFeatures()));
        return result;
    }
}
