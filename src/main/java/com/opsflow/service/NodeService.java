package com.opsflow.service;

import com.opsflow.api.dto.NodeEnvCheckResultDTO;

/**
 * 节点服务
 */
public interface NodeService {

    /**
     * 检测节点环境（根据节点类型：构建节点 / 部署节点）
     */
    NodeEnvCheckResultDTO checkEnvironment(Long nodeId);
}
