package com.opsflow.service;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * 上线镜像校验：镜像是否已在非生产环境部署成功、是否存在于 Harbor。
 */
public interface DeployImageVerifyService {

    /**
     * 完整镜像地址 → 最近一次部署成功的非生产环境名称；未验证过的镜像不在结果中
     */
    Map<String, String> verifiedEnvByImage(Collection<String> imageFullNames);

    /**
     * 某服务在 Harbor 某项目下，已在非生产环境部署成功的版本：tag → 环境名称
     */
    Map<String, String> verifiedTags(String serviceCode, String harborProject);

    /**
     * 校验镜像在 Harbor 中存在；Harbor 未配置时跳过
     *
     * @throws com.opsflow.common.exception.BusinessException 存在找不到的镜像
     */
    void assertImagesExist(List<String> imageFullNames);
}
