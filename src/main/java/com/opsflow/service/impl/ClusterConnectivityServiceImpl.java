package com.opsflow.service.impl;

import com.opsflow.api.dto.ClusterCheckItemDTO;
import com.opsflow.api.dto.ClusterCheckResultDTO;
import com.opsflow.common.exception.BusinessException;
import com.opsflow.dao.mapper.BuildNodeMapper;
import com.opsflow.dao.mapper.ClusterMapper;
import com.opsflow.dao.model.BuildNode;
import com.opsflow.dao.model.Cluster;
import com.opsflow.integration.k8s.K8sCredentialService;
import com.opsflow.integration.pipeline.NodeCommandHelper;
import com.opsflow.service.ClusterConnectivityService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class ClusterConnectivityServiceImpl implements ClusterConnectivityService {

    private static final int CHECK_TIMEOUT_MS = 45000;

    @Autowired
    private ClusterMapper clusterMapper;

    @Autowired
    private BuildNodeMapper buildNodeMapper;

    @Autowired
    private K8sCredentialService k8sCredentialService;

    @Autowired
    private NodeCommandHelper nodeCommandHelper;

    @Override
    public ClusterCheckResultDTO check(Long clusterId, Long proxyNodeId) {
        Cluster cluster = clusterMapper.selectById(clusterId);
        if (cluster == null) {
            throw new BusinessException("集群不存在");
        }

        ClusterCheckResultDTO result = new ClusterCheckResultDTO();
        result.setClusterId(cluster.getId());
        result.setClusterName(cluster.getName());
        result.setItems(new ArrayList<ClusterCheckItemDTO>());

        BuildNode proxyNode = null;
        if (proxyNodeId != null) {
            proxyNode = buildNodeMapper.selectById(proxyNodeId);
            if (proxyNode == null) {
                throw new BusinessException("代理节点不存在");
            }
            result.setProxyNodeId(proxyNode.getId());
            result.setProxyNodeName(describeNode(proxyNode));
        } else {
            result.setProxyNodeId(null);
            result.setProxyNodeName("本机");
        }

        List<ClusterCheckItemDTO> items = result.getItems();

        String kubeconfig = k8sCredentialService.resolveKubeconfigContent(clusterId, null);
        ClusterCheckItemDTO kubeItem = new ClusterCheckItemDTO();
        kubeItem.setKey("kubeconfig");
        kubeItem.setName("Kubeconfig 配置");
        kubeItem.setRequired(true);
        if (kubeconfig == null || kubeconfig.trim().isEmpty()) {
            kubeItem.setPassed(false);
            kubeItem.setMessage("集群未关联有效的 Kubeconfig 钥匙串");
            items.add(kubeItem);
            result.setPassed(false);
            result.setSummary("请先在集群中关联钥匙串中的 Kubeconfig");
            return result;
        }
        kubeItem.setPassed(true);
        kubeItem.setDetail("已加载");
        kubeItem.setMessage("使用集群关联的钥匙串");
        items.add(kubeItem);

        String sessionKey = "cluster-check-" + clusterId + "-" + System.currentTimeMillis();
        String kubeSetup = k8sCredentialService.buildRemoteKubeconfigSetup(sessionKey, clusterId, null);

        items.add(runCheck(proxyNodeId, "kubectl_client", "kubectl 客户端", true,
                kubeSetup + "kubectl version --client 2>&1 | head -n 3"));

        items.add(runCheck(proxyNodeId, "cluster_info", "集群连通 (cluster-info)", true,
                kubeSetup + "kubectl cluster-info --request-timeout=15s 2>&1 | head -n 8"));

        items.add(runCheck(proxyNodeId, "api_ready", "API Ready", false,
                kubeSetup + "kubectl get --raw=/readyz --request-timeout=10s 2>&1"));

        boolean passed = items.stream()
                .filter(item -> Boolean.TRUE.equals(item.getRequired()))
                .allMatch(item -> Boolean.TRUE.equals(item.getPassed()));
        result.setPassed(passed);
        result.setSummary(passed
                ? "集群连通检测通过（代理: " + result.getProxyNodeName() + "）"
                : "集群连通检测未通过（代理: " + result.getProxyNodeName() + "），请检查 kubeconfig 与网络");
        return result;
    }

    private ClusterCheckItemDTO runCheck(Long proxyNodeId, String key, String name, boolean required, String command) {
        ClusterCheckItemDTO item = new ClusterCheckItemDTO();
        item.setKey(key);
        item.setName(name);
        item.setRequired(required);
        try {
            NodeCommandHelper.CommandResult cmd = nodeCommandHelper.runOnNode(proxyNodeId, command, CHECK_TIMEOUT_MS);
            String output = cmd.getOutput() != null ? cmd.getOutput().trim() : "";
            if (output.length() > 500) {
                output = output.substring(0, 500) + "...";
            }
            item.setPassed(cmd.isSuccess());
            item.setDetail(output.isEmpty() ? "-" : output);
            if (cmd.isSuccess()) {
                item.setMessage("通过");
            } else {
                item.setMessage(cmd.getErrorMessage() != null ? cmd.getErrorMessage() : "命令执行失败");
            }
        } catch (Exception e) {
            item.setPassed(false);
            item.setDetail("-");
            item.setMessage(e.getMessage() != null ? e.getMessage() : "检测异常");
        }
        return item;
    }

    private String describeNode(BuildNode node) {
        String name = node.getName() != null && !node.getName().trim().isEmpty()
                ? node.getName().trim()
                : "node-" + node.getId();
        String host = node.getHost() != null && !node.getHost().trim().isEmpty()
                ? node.getHost().trim()
                : "";
        return host.isEmpty() ? name : name + " (" + host + ")";
    }
}
