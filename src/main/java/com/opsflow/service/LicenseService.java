package com.opsflow.service;

import com.opsflow.api.dto.LicenseStatusDTO;

import java.util.List;
import java.util.Map;

public interface LicenseService {

    /** 当前是否启用指定功能 */
    boolean isFeatureEnabled(String feature);

    /** 未启用则抛 BusinessException */
    void requireFeature(String feature);

    LicenseStatusDTO getStatus();

    /** 导入并校验 License，成功则持久化 */
    LicenseStatusDTO importLicense(String rawLicense);

    /** 清除已导入 License */
    void clearLicense();

    List<String> enabledFeatures();
}
