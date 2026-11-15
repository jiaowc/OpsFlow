package com.opsflow.service;

import java.util.List;
import java.util.Map;

/**
 * 服务运行实例（K8s Deployment / Pod）只读视图
 */
public interface WorkloadInstanceService {

    /**
     * 流水线任务对应服务在其环境中的 Deployment 概况与 Pod 列表
     */
    Map<String, Object> listInstances(Long jobId);

    /**
     * Pod 日志（只读）
     *
     * @param previous true 读取上一次（已重启）容器的日志
     */
    String podLogs(Long jobId, String podName, String container, Integer tailLines, boolean previous);

    /**
     * Pod 相关事件，按时间倒序
     */
    List<Map<String, Object>> podEvents(Long jobId, String podName);
}
