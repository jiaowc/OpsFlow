package com.opsflow.integration.git;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opsflow.dao.mapper.ComponentMapper;
import lombok.extern.slf4j.Slf4j;
import com.opsflow.integration.credential.ComponentAuthResolver;
import com.opsflow.integration.credential.ResolvedAuth;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.util.Base64Utils;
import org.springframework.web.client.RestTemplate;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从 Git 仓库获取分支 / Tag 列表
 */
@Slf4j
@org.springframework.stereotype.Component
public class GitRefService {

    private static final Pattern GITHUB_HTTPS = Pattern.compile("https?://github\\.com/([^/]+)/([^/.]+)(?:\\.git)?/?");
    private static final Pattern GITLAB_HTTPS = Pattern.compile("https?://([^/]+)/(.+?)(?:\\.git)?/?$");
    private static final Pattern SSH_GIT = Pattern.compile("git@([^:]+):(.+?)(?:\\.git)?$");
    private static final long CACHE_TTL_MS = 5 * 60 * 1000L;
    private static final int HTTP_CONNECT_TIMEOUT_MS = 3000;
    private static final int HTTP_READ_TIMEOUT_MS = 8000;
    private static final int GIT_LS_REMOTE_TIMEOUT_SEC = 12;

    @Autowired
    private ComponentMapper componentMapper;

    @Autowired
    private ComponentAuthResolver componentAuthResolver;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final RestTemplate restTemplate = createRestTemplate();
    private final ConcurrentHashMap<String, CacheEntry> refCache = new ConcurrentHashMap<>();

    private RestTemplate createRestTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(HTTP_CONNECT_TIMEOUT_MS);
        factory.setReadTimeout(HTTP_READ_TIMEOUT_MS);
        return new RestTemplate(factory);
    }

    public List<String> listRefs(String gitRepo, String refType) {
        if (gitRepo == null || gitRepo.trim().isEmpty()) {
            return Collections.emptyList();
        }
        String type = "tag".equalsIgnoreCase(refType) ? "tag" : "branch";
        gitRepo = gitRepo.trim();
        String cacheKey = buildCacheKey(gitRepo, type);

        CacheEntry cached = refCache.get(cacheKey);
        if (cached != null && !cached.isExpired()) {
            return new ArrayList<>(cached.getRefs());
        }

        try {
            List<String> refs = fetchRefs(gitRepo, type);
            if (!refs.isEmpty()) {
                refCache.put(cacheKey, CacheEntry.of(refs));
            }
            return refs;
        } catch (Exception e) {
            log.error("获取 Git 引用失败: repo={}, type={}", gitRepo, type, e);
            if (cached != null) {
                return new ArrayList<>(cached.getRefs());
            }
            throw new RuntimeException("获取 Git " + ("tag".equals(type) ? "Tag" : "分支") + " 失败: " + e.getMessage(), e);
        }
    }

    private List<String> fetchRefs(String gitRepo, String type) throws Exception {
        if (isGitHubRepo(gitRepo)) {
            List<String> refs = listFromGitHubApi(gitRepo, type);
            if (!refs.isEmpty()) {
                return refs;
            }
            return listFromGitRemote(gitRepo, type);
        }
        if (isGitLabRepo(gitRepo)) {
            List<String> refs = listFromGitLabApi(gitRepo, type);
            if (!refs.isEmpty()) {
                return refs;
            }
            return listFromGitRemote(gitRepo, type);
        }
        List<String> refs = listFromGitLabApi(gitRepo, type);
        if (!refs.isEmpty()) {
            return refs;
        }
        refs = listFromGitHubApi(gitRepo, type);
        if (!refs.isEmpty()) {
            return refs;
        }
        return listFromGitRemote(gitRepo, type);
    }

    private boolean isGitHubRepo(String gitRepo) {
        String lower = gitRepo.toLowerCase();
        return lower.contains("github.com/") || lower.startsWith("git@github.com:");
    }

    private boolean isGitLabRepo(String gitRepo) {
        if (isGitHubRepo(gitRepo)) {
            return false;
        }
        if (gitRepo.startsWith("git@")) {
            return true;
        }
        return gitRepo.startsWith("http://") || gitRepo.startsWith("https://");
    }

    private String buildCacheKey(String gitRepo, String type) {
        return gitRepo + "|" + type;
    }

    private static class CacheEntry {
        private final List<String> refs;
        private final long expireAt;

        private CacheEntry(List<String> refs, long expireAt) {
            this.refs = refs;
            this.expireAt = expireAt;
        }

        static CacheEntry of(List<String> refs) {
            return new CacheEntry(refs, System.currentTimeMillis() + CACHE_TTL_MS);
        }

        boolean isExpired() {
            return System.currentTimeMillis() > expireAt;
        }

        List<String> getRefs() {
            return refs;
        }
    }

    private List<String> listFromGitLabApi(String gitRepo, String type) {
        GitLabContext ctx = resolveGitLabContext(gitRepo);
        if (ctx == null) {
            return Collections.emptyList();
        }
        String encodedProject;
        try {
            encodedProject = URLEncoder.encode(ctx.projectPath, StandardCharsets.UTF_8.name());
        } catch (java.io.UnsupportedEncodingException e) {
            throw new RuntimeException("URL 编码失败", e);
        }
        String endpoint = "tag".equals(type) ? "tags" : "branches";
        String url = ctx.apiBase + "/api/v4/projects/" + encodedProject + "/repository/" + endpoint + "?per_page=100";

        HttpHeaders headers = buildGitLabHeaders(ctx);
        HttpEntity<String> entity = new HttpEntity<>(headers);
        ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);
        if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
            return Collections.emptyList();
        }

        Set<String> names = new LinkedHashSet<>();
        try {
            JsonNode root = objectMapper.readTree(response.getBody());
            if (root.isArray()) {
                for (JsonNode node : root) {
                    if (node.has("name")) {
                        names.add(node.get("name").asText());
                    }
                }
            }
        } catch (Exception e) {
            log.debug("解析 GitLab 响应失败", e);
            return Collections.emptyList();
        }
        return new ArrayList<>(names);
    }

    private List<String> listFromGitHubApi(String gitRepo, String type) {
        Matcher matcher = GITHUB_HTTPS.matcher(gitRepo);
        if (!matcher.find()) {
            return Collections.emptyList();
        }
        String owner = matcher.group(1);
        String repo = matcher.group(2);
        String endpoint = "tag".equals(type) ? "tags" : "branches";
        String url = "https://api.github.com/repos/" + owner + "/" + repo + "/" + endpoint + "?per_page=100";

        HttpHeaders headers = buildGitHubHeaders();
        HttpEntity<String> entity = new HttpEntity<>(headers);
        try {
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);
            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                return Collections.emptyList();
            }
            Set<String> names = new LinkedHashSet<>();
            JsonNode root = objectMapper.readTree(response.getBody());
            if (root.isArray()) {
                for (JsonNode node : root) {
                    if (node.has("name")) {
                        names.add(node.get("name").asText());
                    }
                }
            }
            return new ArrayList<>(names);
        } catch (Exception e) {
            log.debug("GitHub API 获取失败: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    private List<String> listFromGitRemote(String gitRepo, String type) throws Exception {
        String authUrl = injectGitCredentials(gitRepo);
        String lsFlag = "tag".equals(type) ? "--tags" : "--heads";
        ProcessBuilder builder = new ProcessBuilder("git", "ls-remote", lsFlag, authUrl);
        builder.redirectErrorStream(true);
        Process process = builder.start();

        Set<String> names = new LinkedHashSet<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String ref = line.trim();
                if (ref.isEmpty()) {
                    continue;
                }
                int tab = ref.indexOf('\t');
                if (tab <= 0) {
                    continue;
                }
                String refName = ref.substring(tab + 1);
                if ("tag".equals(type) && refName.startsWith("refs/tags/")) {
                    String tag = refName.substring("refs/tags/".length());
                    if (!tag.endsWith("^{}")) {
                        names.add(tag);
                    }
                } else if ("branch".equals(type) && refName.startsWith("refs/heads/")) {
                    names.add(refName.substring("refs/heads/".length()));
                }
            }
        }
        if (!process.waitFor(GIT_LS_REMOTE_TIMEOUT_SEC, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new RuntimeException("git ls-remote 超时");
        }
        int exit = process.exitValue();
        if (exit != 0 && names.isEmpty()) {
            throw new RuntimeException("git ls-remote 执行失败，退出码: " + exit);
        }
        return new ArrayList<>(names);
    }

    private String injectGitCredentials(String gitRepo) {
        if (gitRepo == null) {
            return gitRepo;
        }
        boolean isGitHub = gitRepo.contains("github.com");
        com.opsflow.dao.model.Component gitComponent = isGitHub ? findGitHubComponent() : findGitLabComponent();
        if (gitComponent == null) {
            return gitRepo;
        }
        ResolvedAuth auth = componentAuthResolver.resolve(gitComponent);
        if (!auth.hasAuth()) {
            return gitRepo;
        }
        try {
            Map<String, String> config = auth.getAuthConfig();
            String token = resolveGitToken(auth);
            if (token == null || token.isEmpty()) {
                return gitRepo;
            }
            if (gitRepo.startsWith("https://") && !gitRepo.contains("@")) {
                if (isGitHub) {
                    return gitRepo.replaceFirst("https://", "https://x-access-token:" + token + "@");
                }
                return gitRepo.replaceFirst("https://", "https://oauth2:" + token + "@");
            }
            if (gitRepo.startsWith("http://") && !gitRepo.contains("@")) {
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

    private GitLabContext resolveGitLabContext(String gitRepo) {
        com.opsflow.dao.model.Component gitlab = findGitLabComponent();
        String apiBase = null;
        String projectPath = null;

        if (gitlab != null && gitlab.getUrl() != null) {
            apiBase = gitlab.getUrl().replaceAll("/+$", "");
            try {
                URI componentUri = URI.create(apiBase);
                String componentHost = componentUri.getHost();
                Matcher ssh = SSH_GIT.matcher(gitRepo);
                if (ssh.find() && componentHost != null && componentHost.equalsIgnoreCase(ssh.group(1))) {
                    projectPath = ssh.group(2);
                }
                Matcher https = GITLAB_HTTPS.matcher(gitRepo);
                if (projectPath == null && https.find() && componentHost != null && componentHost.equalsIgnoreCase(https.group(1))) {
                    projectPath = https.group(2);
                }
            } catch (Exception e) {
                log.debug("解析 GitLab 地址失败", e);
            }
        }

        if (projectPath == null) {
            Matcher ssh = SSH_GIT.matcher(gitRepo);
            if (ssh.find()) {
                apiBase = "https://" + ssh.group(1);
                projectPath = ssh.group(2);
            } else {
                Matcher https = GITLAB_HTTPS.matcher(gitRepo);
                if (https.find() && !https.group(1).equalsIgnoreCase("github.com")) {
                    apiBase = "https://" + https.group(1);
                    projectPath = https.group(2);
                }
            }
        }

        if (projectPath == null || apiBase == null) {
            return null;
        }
        GitLabContext ctx = new GitLabContext();
        ctx.apiBase = apiBase;
        ctx.projectPath = projectPath;
        populateGitLabAuth(gitlab, ctx);
        return ctx;
    }

    private void populateGitLabAuth(com.opsflow.dao.model.Component gitlab, GitLabContext ctx) {
        if (gitlab == null) {
            return;
        }
        ResolvedAuth auth = componentAuthResolver.resolve(gitlab);
        if (!auth.hasAuth()) {
            return;
        }
        try {
            Map<String, String> config = auth.getAuthConfig();
            String authType = auth.getAuthType();
            if ("token".equals(authType) || "api_key".equals(authType)) {
                String token = config.get("token");
                if (token == null) {
                    token = config.get("apiKey");
                }
                ctx.token = token;
                ctx.authMode = "token";
            } else if ("username_password".equals(authType)) {
                String user = config.get("username");
                String pass = config.get("password");
                if (user != null && pass != null) {
                    String raw = user + ":" + pass;
                    ctx.token = Base64Utils.encodeToString(raw.getBytes(StandardCharsets.UTF_8));
                    ctx.authMode = "basic";
                }
            } else if ("oauth".equals(authType)) {
                String token = config.get("clientSecret");
                if (token == null) {
                    token = config.get("token");
                }
                ctx.token = token;
                ctx.authMode = "token";
            }
        } catch (Exception e) {
            log.debug("读取 GitLab 组件凭据失败", e);
        }
    }

    private com.opsflow.dao.model.Component findGitLabComponent() {
        QueryWrapper<com.opsflow.dao.model.Component> wrapper = new QueryWrapper<>();
        wrapper.eq("type", "gitlab");
        wrapper.eq("status", 1);
        wrapper.orderByDesc("create_time");
        wrapper.last("LIMIT 1");
        return componentMapper.selectOne(wrapper);
    }

    private com.opsflow.dao.model.Component findGitHubComponent() {
        QueryWrapper<com.opsflow.dao.model.Component> wrapper = new QueryWrapper<>();
        wrapper.eq("type", "github");
        wrapper.eq("status", 1);
        wrapper.orderByDesc("create_time");
        wrapper.last("LIMIT 1");
        return componentMapper.selectOne(wrapper);
    }

    private HttpHeaders buildGitHubHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(Collections.singletonList(MediaType.APPLICATION_JSON));
        headers.set("User-Agent", "OpsFlow");
        headers.set("Accept", "application/vnd.github+json");

        com.opsflow.dao.model.Component github = findGitHubComponent();
        if (github == null) {
            return headers;
        }
        ResolvedAuth auth = componentAuthResolver.resolve(github);
        if (!auth.hasAuth()) {
            return headers;
        }
        String token = resolveGitToken(auth);
        if (token != null && !token.isEmpty()) {
            headers.set("Authorization", "Bearer " + token);
        }
        return headers;
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

    private HttpHeaders buildGitLabHeaders(GitLabContext ctx) {
        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(Collections.singletonList(MediaType.APPLICATION_JSON));
        if (ctx.token != null && !ctx.token.isEmpty()) {
            if ("basic".equals(ctx.authMode)) {
                headers.set("Authorization", "Basic " + ctx.token);
            } else {
                headers.set("PRIVATE-TOKEN", ctx.token);
            }
        }
        return headers;
    }

    private static class GitLabContext {
        private String apiBase;
        private String projectPath;
        private String token;
        private String authMode;
    }
}
