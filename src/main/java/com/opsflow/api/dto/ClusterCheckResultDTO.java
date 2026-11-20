package com.opsflow.api.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 集群连通性检测结果
 */
@Data
public class ClusterCheckResultDTO {

    private Long clusterId;

    private String clusterName;

    /**
     * 代理节点 ID，null 表示本机
     */
    private Long proxyNodeId;

    private String proxyNodeName;

    private Boolean passed;

    private String summary;

    private List<ClusterCheckItemDTO> items = new ArrayList<>();
}
