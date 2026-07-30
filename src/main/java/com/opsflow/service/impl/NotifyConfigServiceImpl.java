package com.opsflow.service.impl;

import com.opsflow.service.NotifyConfigService;
import com.opsflow.service.SystemConfigService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class NotifyConfigServiceImpl implements NotifyConfigService {

    private static final String TYPE = "notify";

    @Autowired
    private SystemConfigService systemConfigService;

    @Override
    public boolean isInboxEnabled() {
        return systemConfigService.getBoolean(TYPE, "inboxEnabled", true);
    }

    @Override
    public boolean isDefaultInbox() {
        return systemConfigService.getBoolean(TYPE, "defaultInbox", true);
    }

    @Override
    public boolean isDefaultFeishu() {
        return systemConfigService.getBoolean(TYPE, "defaultFeishu", false);
    }
}
