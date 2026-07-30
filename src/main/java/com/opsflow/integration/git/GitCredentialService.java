package com.opsflow.integration.git;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.opsflow.dao.mapper.ComponentMapper;
import com.opsflow.dao.model.Component;
import com.opsflow.integration.credential.ComponentAuthResolver;
import com.opsflow.integration.credential.ResolvedAuth;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Map;

/**
 * 为 Git HTTPS 操作注入组件凭据
 */
@Slf4j
@org.springframework.stereotype.Component
public class GitCredentialService {

    @Autowired
    private ComponentMapper componentMapper;

    @Autowired
    private ComponentAuthResolver componentAuthResolver;

    /**
     * 将组件 Token 注入 HTTPS Git 仓库地址，供 git clone / ls-remote 使用。
     *
     * @param gitRepo     原始仓库地址
     * @param componentId 服务关联的组件 ID，可为 null（则按仓库类型取最新启用的组件）
     */
    public String injectCredentials(String gitRepo, Long componentId) {
        if (gitRepo == null || gitRepo.isEmpty()) {
            return gitRepo;
        }
        if (!gitRepo.startsWith("http://") && !gitRepo.startsWith("https://")) {
            return gitRepo;
        }
        if (gitRepo.contains("@")) {
            return gitRepo;
        }

        boolean isGitHub = gitRepo.contains("github.com");
        Component gitComponent = resolveComponent(gitRepo, componentId, isGitHub);
        if (gitComponent == null) {
            return gitRepo;
        }

        ResolvedAuth auth = componentAuthResolver.resolve(gitComponent);
        if (!auth.hasAuth()) {
            log.debug("组件未配置可用凭据: componentId={}", gitComponent.getId());
            return gitRepo;
        }

        try {
            String token = resolveGitToken(auth);
            if (token == null || token.isEmpty()) {
                return gitRepo;
            }
            if (gitRepo.startsWith("https://")) {
                if (isGitHub) {
                    return gitRepo.replaceFirst("https://", "https://x-access-token:" + token + "@");
                }
                return gitRepo.replaceFirst("https://", "https://oauth2:" + token + "@");
            }
            if (gitRepo.startsWith("http://")) {
                if (isGitHub) {
                    return gitRepo.replaceFirst("http://", "http://x-access-token:" + token + "@");
                }
                return gitRepo.replaceFirst("http://", "http://oauth2:" + token + "@");
            }
        } catch (Exception e) {
            log.debug("注入 Git 凭据失败", e);
        }
        return gitRepo;
    }

    private Component resolveComponent(String gitRepo, Long componentId, boolean isGitHub) {
        if (componentId != null) {
            Component component = componentMapper.selectById(componentId);
            if (component != null && component.getStatus() != null && component.getStatus() == 1) {
                return component;
            }
            log.warn("服务关联的组件不存在或已禁用: componentId={}", componentId);
        }
        return isGitHub ? findGitHubComponent() : findGitLabComponent();
    }

    private Component findGitLabComponent() {
        QueryWrapper<Component> wrapper = new QueryWrapper<>();
        wrapper.eq("type", "gitlab");
        wrapper.eq("status", 1);
        wrapper.orderByDesc("create_time");
        wrapper.last("LIMIT 1");
        return componentMapper.selectOne(wrapper);
    }

    private Component findGitHubComponent() {
        QueryWrapper<Component> wrapper = new QueryWrapper<>();
        wrapper.eq("type", "github");
        wrapper.eq("status", 1);
        wrapper.orderByDesc("create_time");
        wrapper.last("LIMIT 1");
        return componentMapper.selectOne(wrapper);
    }

    private String resolveGitToken(ResolvedAuth auth) {
        Map<String, String> config = auth.getAuthConfig();
        if (config == null) {
            return null;
        }
        String authType = auth.getAuthType();
        if ("token".equals(authType) || "api_key".equals(authType) || "oauth".equals(authType)) {
            String token = config.get("token");
            if (token == null || token.isEmpty()) {
                token = config.get("apiKey");
            }
            if (token == null || token.isEmpty()) {
                token = config.get("clientSecret");
            }
            return token;
        }
        if ("username_password".equals(authType)) {
            String pass = config.get("password");
            if (pass != null && !pass.isEmpty()) {
                return pass;
            }
        }
        return null;
    }
}
