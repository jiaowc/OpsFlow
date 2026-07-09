package com.opsflow.service.impl;

import com.opsflow.dao.model.Component;
import com.opsflow.integration.credential.ComponentAuthResolver;
import com.opsflow.integration.credential.ResolvedAuth;
import com.opsflow.service.ComponentAuthQueryService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.Map;

@Service
public class ComponentAuthQueryServiceImpl implements ComponentAuthQueryService {

    @Autowired
    private ComponentAuthResolver componentAuthResolver;

    @Override
    public String resolveAuthType(Component component) {
        return componentAuthResolver.resolve(component).getAuthType();
    }

    @Override
    public Map<String, String> resolveAuthConfig(Component component) {
        ResolvedAuth auth = componentAuthResolver.resolve(component);
        return auth.getAuthConfig() != null ? auth.getAuthConfig() : Collections.emptyMap();
    }

    @Override
    public boolean hasAuth(Component component) {
        return componentAuthResolver.resolve(component).hasAuth();
    }
}
