package com.opsflow.service.impl;

import com.opsflow.api.dto.GitRefBatchRequest;
import com.opsflow.integration.git.GitRefService;
import com.opsflow.service.ServiceGitRefService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class ServiceGitRefServiceImpl implements ServiceGitRefService {

    @Autowired
    private GitRefService gitRefService;

    @Override
    public List<String> listRefs(String gitRepo, String refType) {
        return gitRefService.listRefs(gitRepo, refType);
    }

    @Override
    public Map<String, List<String>> listRefsBatch(List<GitRefBatchRequest.GitRefQuery> queries) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        if (queries == null) {
            return result;
        }
        Map<String, GitRefBatchRequest.GitRefQuery> uniqueQueries = new LinkedHashMap<>();
        for (GitRefBatchRequest.GitRefQuery query : queries) {
            if (query == null || query.getGitRepo() == null || query.getGitRepo().trim().isEmpty()) {
                continue;
            }
            String type = query.getType() == null || query.getType().trim().isEmpty()
                    ? "branch" : query.getType().trim();
            uniqueQueries.put(buildCacheKey(query.getGitRepo().trim(), type), query);
        }
        for (Map.Entry<String, GitRefBatchRequest.GitRefQuery> entry : uniqueQueries.entrySet()) {
            GitRefBatchRequest.GitRefQuery query = entry.getValue();
            String type = query.getType() == null || query.getType().trim().isEmpty()
                    ? "branch" : query.getType().trim();
            result.put(entry.getKey(), gitRefService.listRefs(query.getGitRepo().trim(), type));
        }
        return result;
    }

    private String buildCacheKey(String gitRepo, String type) {
        return gitRepo + "|" + ("tag".equalsIgnoreCase(type) ? "tag" : "branch");
    }
}
