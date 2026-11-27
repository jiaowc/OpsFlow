package com.opsflow.service.impl;

import com.opsflow.service.AppVersionService;
import com.opsflow.spi.EditionInfo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.info.BuildProperties;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class AppVersionServiceImpl implements AppVersionService {

    private static final DateTimeFormatter BUILD_TIME_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.of("Asia/Shanghai"));

    private static final String FALLBACK_VERSION = "1.0.0";

    @Autowired(required = false)
    private BuildProperties buildProperties;

    /** 社区版 / 企业版；取不到时不返回 edition，前端按「不显示源码入口」处理 */
    @Autowired(required = false)
    private EditionInfo editionInfo;

    @Override
    public Map<String, Object> getVersionInfo() {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("name", resolveName());
        info.put("version", getVersion());
        info.put("artifact", resolveArtifact());
        info.put("buildTime", resolveBuildTime());
        // 免登录接口：只给版本名（community / enterprise），不带任何 License 详情
        String edition = resolveEdition();
        if (edition != null) {
            info.put("edition", edition);
        }
        return info;
    }

    @Override
    public String getVersion() {
        if (buildProperties != null && buildProperties.getVersion() != null
                && !buildProperties.getVersion().trim().isEmpty()) {
            return buildProperties.getVersion().trim();
        }
        Package pkg = getClass().getPackage();
        if (pkg != null && pkg.getImplementationVersion() != null
                && !pkg.getImplementationVersion().trim().isEmpty()) {
            return pkg.getImplementationVersion().trim();
        }
        return FALLBACK_VERSION;
    }

    private String resolveEdition() {
        if (editionInfo == null) {
            return null;
        }
        try {
            String edition = editionInfo.edition();
            return edition != null && !edition.trim().isEmpty() ? edition.trim() : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private String resolveName() {
        if (buildProperties != null && buildProperties.getName() != null
                && !buildProperties.getName().trim().isEmpty()) {
            return buildProperties.getName().trim();
        }
        return "OpsFlow";
    }

    private String resolveArtifact() {
        if (buildProperties != null && buildProperties.getArtifact() != null
                && !buildProperties.getArtifact().trim().isEmpty()) {
            return buildProperties.getArtifact().trim();
        }
        return "opsflow";
    }

    private String resolveBuildTime() {
        if (buildProperties == null) {
            return null;
        }
        Instant time = buildProperties.getTime();
        if (time == null) {
            return null;
        }
        return BUILD_TIME_FMT.format(time);
    }
}
