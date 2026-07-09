package com.opsflow.service;

import com.opsflow.api.dto.GitRefBatchRequest;

import java.util.List;
import java.util.Map;

/**
 * 查询 Git 仓库分支 / Tag
 */
public interface ServiceGitRefService {

    List<String> listRefs(String gitRepo, String refType);

    Map<String, List<String>> listRefsBatch(List<GitRefBatchRequest.GitRefQuery> queries);
}
