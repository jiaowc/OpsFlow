package com.opsflow.service;

import com.opsflow.api.dto.ClusterCheckResultDTO;

public interface ClusterConnectivityService {

    /**
     * 检测集群连通性。
     *
     * @param clusterId  集群 ID
     * @param proxyNodeId 代理节点 ID，null 表示本机
     */
    ClusterCheckResultDTO check(Long clusterId, Long proxyNodeId);
}
