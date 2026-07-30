package com.opsflow.integration.credential;

import lombok.Data;

import java.util.Collections;
import java.util.Map;

/**
 * 组件解析后的认证信息
 */
@Data
public class ResolvedAuth {

    private String authType;

    private Map<String, String> authConfig = Collections.emptyMap();

    public boolean hasAuth() {
        return authType != null && !authType.trim().isEmpty()
                && authConfig != null && !authConfig.isEmpty();
    }
}
