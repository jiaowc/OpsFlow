package com.opsflow.api.dto;

import lombok.Data;

/**
 * 集群检测请求
 */
@Data
public class ClusterCheckRequest {

    /**
     * 检测代理节点 ID；为空则在本机执行
     */
    private Long nodeId;
}
