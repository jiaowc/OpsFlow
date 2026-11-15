package com.opsflow.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.opsflow.common.constant.BuildStatus;
import com.opsflow.common.exception.BusinessException;
import com.opsflow.dao.mapper.BuildJobMapper;
import com.opsflow.dao.mapper.EnvMapper;
import com.opsflow.dao.model.BuildJob;
import com.opsflow.dao.model.Env;
import com.opsflow.integration.harbor.HarborClient;
import com.opsflow.service.DeployImageVerifyService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class DeployImageVerifyServiceImpl implements DeployImageVerifyService {

    @Autowired
    private BuildJobMapper buildJobMapper;

    @Autowired
    private EnvMapper envMapper;

    @Autowired(required = false)
    private HarborClient harborClient;

    @Override
    public Map<String, String> verifiedEnvByImage(Collection<String> imageFullNames) {
        if (imageFullNames == null || imageFullNames.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<Long, String> nonProdEnvs = nonProdEnvNames();
        if (nonProdEnvs.isEmpty()) {
            return Collections.emptyMap();
        }
        List<BuildJob> jobs = buildJobMapper.selectList(new LambdaQueryWrapper<BuildJob>()
                .select(BuildJob::getImageFullName, BuildJob::getEnvId)
                .eq(BuildJob::getStatus, BuildStatus.SUCCESS)
                .in(BuildJob::getImageFullName, imageFullNames)
                .in(BuildJob::getEnvId, nonProdEnvs.keySet())
                .orderByDesc(BuildJob::getId));
        Map<String, String> result = new LinkedHashMap<>();
        for (BuildJob job : jobs) {
            result.putIfAbsent(job.getImageFullName(), nonProdEnvs.get(job.getEnvId()));
        }
        return result;
    }

    @Override
    public Map<String, String> verifiedTags(String serviceCode, String harborProject) {
        if (!StringUtils.hasText(serviceCode) || !StringUtils.hasText(harborProject)) {
            return Collections.emptyMap();
        }
        Map<Long, String> nonProdEnvs = nonProdEnvNames();
        if (nonProdEnvs.isEmpty()) {
            return Collections.emptyMap();
        }
        // 与 Harbor 版本接口的仓库匹配一致：仓库名等于服务名，或以 /服务名 结尾
        String projectPath = "/" + harborProject.trim() + "/";
        String serviceSuffix = "/" + serviceCode.trim() + ":";
        List<BuildJob> jobs = buildJobMapper.selectList(new LambdaQueryWrapper<BuildJob>()
                .select(BuildJob::getImageFullName, BuildJob::getEnvId)
                .eq(BuildJob::getStatus, BuildStatus.SUCCESS)
                .like(BuildJob::getImageFullName, projectPath)
                .like(BuildJob::getImageFullName, serviceSuffix)
                .in(BuildJob::getEnvId, nonProdEnvs.keySet())
                .orderByDesc(BuildJob::getId)
                .last("LIMIT 500"));
        Map<String, String> result = new LinkedHashMap<>();
        for (BuildJob job : jobs) {
            String image = job.getImageFullName();
            int idx = image.lastIndexOf(serviceSuffix);
            if (idx < 0 || image.indexOf(projectPath) > idx) {
                continue;
            }
            String tag = image.substring(idx + serviceSuffix.length());
            if (StringUtils.hasText(tag)) {
                result.putIfAbsent(tag, nonProdEnvs.get(job.getEnvId()));
            }
        }
        return result;
    }

    @Override
    public void assertImagesExist(List<String> imageFullNames) {
        if (harborClient == null || imageFullNames == null) {
            return;
        }
        List<String> missing = new ArrayList<>();
        for (String image : imageFullNames) {
            if (StringUtils.hasText(image) && !harborClient.verifyImagePushed(image)) {
                missing.add(image);
            }
        }
        if (!missing.isEmpty()) {
            throw new BusinessException("Harbor 中找不到以下镜像（或 Harbor 暂时无法访问）: " + String.join(", ", missing));
        }
    }

    private Map<Long, String> nonProdEnvNames() {
        return envMapper.selectList(null).stream()
                .filter(env -> !env.isProdEnv())
                .collect(Collectors.toMap(Env::getId, env -> env.getName() != null ? env.getName() : String.valueOf(env.getId()),
                        (a, b) -> a, LinkedHashMap::new));
    }
}
