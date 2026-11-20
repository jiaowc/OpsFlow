package com.opsflow.service.impl.pipeline;

import com.opsflow.dao.mapper.*;
import com.opsflow.dao.model.*;
import com.opsflow.integration.ssh.SshClient;
import com.opsflow.integration.ssh.SshCommandResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 构建节点选择：解析 Pipeline 的 buildNodeIds，多节点时按负载选择。
 * <p>由 {@link com.opsflow.service.impl.PipelineRunServiceImpl} 拆分而来，行为保持不变。</p>
 */
@Slf4j
@Component
public class BuildNodeSelector {
    @Autowired
    private BuildNodeMapper buildNodeMapper;

    @Autowired
    private SshClient sshClient;

    public Long selectBuildNodeId(Pipeline pipeline) {
        if (pipeline == null) {
            return null;
        }
        List<Long> candidateIds = parseBuildNodeIds(pipeline.getBuildNodeIds());
        if (candidateIds.isEmpty()) {
            return null;
        }
        if (candidateIds.size() == 1) {
            return candidateIds.get(0);
        }

        BuildNode bestNode = null;
        double bestScore = Double.MAX_VALUE;
        for (Long nodeId : candidateIds) {
            BuildNode node = buildNodeMapper.selectById(nodeId);
            if (node == null) {
                continue;
            }
            double score = readNodeLoadScore(node);
            if (score < bestScore) {
                bestScore = score;
                bestNode = node;
            }
        }
        if (bestNode != null) {
            log.info("多构建节点调度选择低负载节点: {}({}), score={}", bestNode.getName(), bestNode.getHost(), bestScore);
            return bestNode.getId();
        }
        return candidateIds.get(0);
    }

    public Long firstBuildNodeId(Pipeline pipeline) {
        List<Long> ids = parseBuildNodeIds(pipeline.getBuildNodeIds());
        return ids.isEmpty() ? null : ids.get(0);
    }

    List<Long> parseBuildNodeIds(String buildNodeIds) {
        List<Long> ids = new ArrayList<>();
        if (buildNodeIds != null && !buildNodeIds.trim().isEmpty()) {
            for (String part : buildNodeIds.split(",")) {
                String text = part == null ? "" : part.trim();
                if (text.isEmpty()) continue;
                try {
                    Long id = Long.parseLong(text);
                    if (!ids.contains(id)) {
                        ids.add(id);
                    }
                } catch (NumberFormatException ignore) {
                }
            }
        }
        return ids;
    }

    private double readNodeLoadScore(BuildNode node) {
        try {
            SshCommandResult result = sshClient.executeCheck(node,
                "cores=$(getconf _NPROCESSORS_ONLN 2>/dev/null || nproc 2>/dev/null || echo 1); "
                    + "load=$(cat /proc/loadavg 2>/dev/null | awk '{print $1}'); "
                    + "if [ -z \"$load\" ]; then load=$(uptime | awk -F'load average: ' '{print $2}' | cut -d, -f1 | tr -d ' '); fi; "
                    + "echo \"$cores $load\"");
            if (!result.isSuccess() || result.getOutput() == null || result.getOutput().trim().isEmpty()) {
                return Double.MAX_VALUE;
            }
            String[] parts = result.getOutput().trim().split("\\s+");
            double cores = parts.length > 0 ? Double.parseDouble(parts[0]) : 1D;
            double load = parts.length > 1 ? Double.parseDouble(parts[1]) : Double.MAX_VALUE / 2;
            if (cores <= 0) {
                cores = 1D;
            }
            return load / cores;
        } catch (Exception e) {
            log.warn("读取节点负载失败，回退默认顺序: nodeId={}, error={}", node.getId(), e.getMessage());
            return Double.MAX_VALUE;
        }
    }
}
