package com.opsflow.api.dto;

import lombok.Data;

import java.util.List;

@Data
public class GitRefBatchRequest {

    private List<GitRefQuery> queries;

    @Data
    public static class GitRefQuery {
        private String gitRepo;
        private String type;
    }
}
