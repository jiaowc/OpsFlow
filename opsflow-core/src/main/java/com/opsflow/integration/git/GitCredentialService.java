package com.opsflow.integration.git;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.opsflow.dao.mapper.ComponentMapper;
import com.opsflow.dao.model.Component;
import com.opsflow.integration.credential.ComponentAuthResolver;
import com.opsflow.integration.credential.ResolvedAuth;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URLEncoder;
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
     * @deprecated 带凭据的 URL 会被写进 .git/config 并出现在进程参数里，
     *             请改用 {@link #buildCredentialStoreLine(String, Long)} 配合临时 credential store
     */
    @Deprecated
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

    /**
     * 生成 git credential-store 文件中的一行：{@code scheme://user:token@host[:port]}。
     * 配合 {@code git -c credential.helper= -c credential.helper="store --file=<临时文件>"} 使用，
     * 仓库 URL 本身不带凭据，token 不会写进 .git/config，也不会出现在进程参数里。
     *
     * @param gitRepo     原始仓库地址（不带凭据）
     * @param componentId 服务关联的组件 ID，可为 null（则按仓库类型取最新启用的组件）
     * @return 存储行；非 http(s) 地址、地址已自带用户信息或没有可用凭据时返回 null
     */
    public String buildCredentialStoreLine(String gitRepo, Long componentId) {
        if (gitRepo == null || gitRepo.isEmpty()) {
            return null;
        }
        if (!gitRepo.startsWith("http://") && !gitRepo.startsWith("https://")) {
            return null;
        }
        String scheme;
        String hostPort;
        try {
            URI uri = URI.create(gitRepo.trim());
            if (uri.getRawUserInfo() != null || uri.getHost() == null) {
                return null;
            }
            scheme = uri.getScheme();
            hostPort = uri.getPort() > 0 ? uri.getHost() + ":" + uri.getPort() : uri.getHost();
        } catch (IllegalArgumentException e) {
            log.debug("无法解析 Git 仓库地址: {}", e.getMessage());
            return null;
        }

        boolean isGitHub = gitRepo.contains("github.com");
        Component gitComponent = resolveComponent(gitRepo, componentId, isGitHub);
        if (gitComponent == null) {
            return null;
        }
        ResolvedAuth auth = componentAuthResolver.resolve(gitComponent);
        if (!auth.hasAuth()) {
            log.debug("组件未配置可用凭据: componentId={}", gitComponent.getId());
            return null;
        }
        String token = resolveGitToken(auth);
        if (token == null || token.isEmpty()) {
            return null;
        }
        return formatStoreLine(scheme, hostPort, isGitHub ? "x-access-token" : "oauth2", token);
    }

    /**
     * 按 credential-store 文件格式拼接一行，user / token 做 URL 编码（git 读取时会解码）。
     */
    static String formatStoreLine(String scheme, String hostPort, String user, String token) {
        return scheme + "://" + urlEncode(user) + ":" + urlEncode(token) + "@" + hostPort;
    }

    private static String urlEncode(String value) {
        try {
            // URLEncoder 把空格编码成 "+"，而 git 的 URL 解码不把 "+" 当空格，统一改成 %20
            return URLEncoder.encode(value, "UTF-8").replace("+", "%20");
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
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
