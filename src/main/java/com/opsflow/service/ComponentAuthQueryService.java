package com.opsflow.service;

import com.opsflow.dao.model.Component;

import java.util.Map;

/**
 * 解析组件认证信息（含钥匙串）
 */
public interface ComponentAuthQueryService {

    String resolveAuthType(Component component);

    Map<String, String> resolveAuthConfig(Component component);

    boolean hasAuth(Component component);
}
