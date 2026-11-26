package com.opsflow.service;

import java.util.Map;

/**
 * 应用版本信息（以 Maven project.version / build-info 为准）。
 */
public interface AppVersionService {

    /**
     * 返回版本详情：version / name / artifact / buildTime。
     */
    Map<String, Object> getVersionInfo();

    /**
     * 当前应用版本号，例如 1.0.0。
     */
    String getVersion();
}
